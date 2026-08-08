package io.github.simpledi;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

final class ReloadableContexts {
    private ReloadableContexts() {}

    static ReloadableBeanContext load(
            Supplier<XmlBeans.Builder> builderFactory,
            Path configuration,
            ReloadPolicy reloadPolicy,
            ReloadPolicy shutdownPolicy,
            Duration drainTimeout,
            LeaseDiagnostics leaseDiagnostics,
            GenerationClassLoaderOwnership classLoaderOwnership,
            GenerationHandoff handoff,
            List<ReloadListener> listeners) {
        return new DefaultReloadableBeanContext(builderFactory, configuration, reloadPolicy, shutdownPolicy,
                drainTimeout, leaseDiagnostics, classLoaderOwnership, handoff, listeners);
    }

    private static final class DefaultReloadableBeanContext implements ReloadableBeanContext {
        private final Supplier<XmlBeans.Builder> builderFactory;
        private final ReloadPolicy reloadPolicy;
        private final ReloadPolicy shutdownPolicy;
        private final Duration drainTimeout;
        private final LeaseDiagnostics leaseDiagnostics;
        private final GenerationClassLoaderOwnership classLoaderOwnership;
        private final GenerationHandoff handoff;
        private final List<ReloadListener> listeners;
        private final byte[] revisionKey = new byte[32];
        private final RevisionIdentityRegistry revisionIdentities = new RevisionIdentityRegistry();
        private final ReentrantLock reloadLock = new ReentrantLock();
        private final Object monitor = new Object();
        private final LinkedHashMap<Long, Generation> generations = new LinkedHashMap<>();
        private final Set<Prepared> preparations = Collections.newSetFromMap(new IdentityHashMap<>());
        private volatile Generation active;
        private volatile boolean closed;

        private DefaultReloadableBeanContext(
                Supplier<XmlBeans.Builder> builderFactory,
                Path configuration,
                ReloadPolicy reloadPolicy,
                ReloadPolicy shutdownPolicy,
                Duration drainTimeout,
                LeaseDiagnostics leaseDiagnostics,
                GenerationClassLoaderOwnership classLoaderOwnership,
                GenerationHandoff handoff,
                List<ReloadListener> listeners) {
            this.builderFactory = Objects.requireNonNull(builderFactory, "builderFactory");
            this.reloadPolicy = Objects.requireNonNull(reloadPolicy, "reloadPolicy");
            this.shutdownPolicy = Objects.requireNonNull(shutdownPolicy, "shutdownPolicy");
            this.drainTimeout = Objects.requireNonNull(drainTimeout, "drainTimeout");
            this.leaseDiagnostics = Objects.requireNonNull(leaseDiagnostics, "leaseDiagnostics");
            this.classLoaderOwnership = Objects.requireNonNull(classLoaderOwnership, "classLoaderOwnership");
            this.handoff = Objects.requireNonNull(handoff, "handoff");
            this.listeners = List.copyOf(listeners);
            new SecureRandom().nextBytes(revisionKey);
            initialize(configuration);
        }

        private void initialize(Path configuration) {
            long started = System.nanoTime();
            emit(new ReloadEvent(ReloadEvent.Kind.PREPARE_STARTED, 0, 1, null, 0, 0, null));
            XmlBeans.Builder.ReloadPreparation preparation = newBuilder()
                    .prepareForReload(configuration, revisionKey, revisionIdentities);
            try {
                validateOwnedClassLoader(preparation.classLoader());
            } catch (Throwable failure) {
                disposePreparation(preparation, null, failure);
                throw rethrow("Invalid generation classloader ownership", failure);
            }
            if (!preparation.validation().valid()) {
                emitSafe(new ReloadEvent(ReloadEvent.Kind.PREPARE_FAILED, 0, 1, null,
                        System.nanoTime() - started, 0, null));
                BeanException failure;
                try {
                    preparation.validation().throwIfInvalid();
                    throw new AssertionError("invalid validation unexpectedly succeeded");
                } catch (BeanException error) {
                    failure = error;
                }
                disposePreparation(preparation, null, failure);
                throw failure;
            }
            BeanContext context;
            long startup = System.nanoTime();
            try {
                emit(new ReloadEvent(ReloadEvent.Kind.PREPARE_SUCCEEDED, 0, 1, preparation.revision(),
                        System.nanoTime() - started, 0, null));
                emit(new ReloadEvent(ReloadEvent.Kind.CANDIDATE_STARTING, 0, 1, preparation.revision(), 0, 0, null));
                context = preparation.start();
                emit(new ReloadEvent(ReloadEvent.Kind.CANDIDATE_STARTED, 0, 1, preparation.revision(),
                        System.nanoTime() - startup, 0, null));
                context = preparation.transfer();
            } catch (Throwable failure) {
                disposePreparation(preparation, null, failure);
                emitSafe(new ReloadEvent(ReloadEvent.Kind.CANDIDATE_FAILED, 0, 1, preparation.revision(),
                        System.nanoTime() - startup, 0, ReloadFailure.describe(failure)));
                throw rethrow("Cannot start initial reloadable context", failure);
            }
            Generation initial = new Generation(this, 1, preparation.revision(), preparation.validation(), context,
                    preparation.classLoader(), classLoaderOwnership);
            synchronized (monitor) {
                generations.put(1L, initial);
                active = initial;
            }
            emitSafe(new ReloadEvent(ReloadEvent.Kind.GENERATION_ACTIVATED, 0, 1, initial.revision,
                    0, 0, null));
        }

        @Override
        public ContextLease acquire() {
            while (true) {
                Generation selected = active;
                if (selected == null || closed) throw new IllegalStateException("ReloadableBeanContext is closed");
                ContextLease lease = selected.tryAcquire(leaseDiagnostics);
                if (lease == null) continue;
                if (!closed && active == selected) return lease;
                try {
                    lease.close();
                } catch (Throwable cleanupFailure) {
                    IllegalStateException failure = new IllegalStateException("ReloadableBeanContext closed during lease acquisition");
                    failure.addSuppressed(cleanupFailure);
                    throw failure;
                }
                if (closed) throw new IllegalStateException("ReloadableBeanContext is closed");
            }
        }

        @Override
        public long generation() {
            Generation selected = requireActive();
            return selected.number;
        }

        @Override
        public ConfigurationRevision revision() {
            return requireActive().revision;
        }

        @Override
        public ContextSnapshot snapshot() {
            try (ContextLease lease = acquire()) {
                return lease.context().snapshot();
            }
        }

        @Override
        public PreparedReload prepare(Path configuration) {
            Objects.requireNonNull(configuration, "configuration");
            reloadLock.lock();
            try {
                if (closed) return Prepared.closed(this);
                Generation base = requireActive();
                long candidateNumber = base.number + 1;
                long started = System.nanoTime();
                emit(new ReloadEvent(ReloadEvent.Kind.PREPARE_STARTED, base.number, candidateNumber,
                        null, 0, base.activeLeaseCount(), null));
                XmlBeans.Builder.ReloadPreparation candidate;
                try {
                    candidate = newBuilder().prepareForReload(configuration.toAbsolutePath().normalize(),
                            revisionKey, revisionIdentities);
                } catch (Throwable failure) {
                    emitSafe(new ReloadEvent(ReloadEvent.Kind.PREPARE_FAILED, base.number, candidateNumber,
                            null, System.nanoTime() - started, base.activeLeaseCount(), ReloadFailure.describe(failure)));
                    Prepared result = Prepared.failed(this, base, null, null, null,
                            ReloadResult.Status.VALIDATION_FAILED, failure);
                    track(result);
                    return result;
                }
                try {
                    validateOwnedClassLoader(candidate.classLoader());
                } catch (Throwable failure) {
                    disposePreparation(candidate, base.classLoader, failure);
                    emitSafe(new ReloadEvent(ReloadEvent.Kind.CANDIDATE_FAILED, base.number, candidateNumber,
                            candidate.revision(), System.nanoTime() - started, base.activeLeaseCount(),
                            ReloadFailure.describe(failure)));
                    Prepared result = Prepared.failed(this, base, null, candidate.validation(),
                            base.validation.diff(candidate.validation()), ReloadResult.Status.STARTUP_FAILED, failure);
                    track(result);
                    return result;
                }
                ValidationResult validation = candidate.validation();
                ConfigurationDiff diff = base.validation.diff(validation);
                if (!validation.valid()) {
                    emitSafe(new ReloadEvent(ReloadEvent.Kind.PREPARE_FAILED, base.number, candidateNumber,
                            candidate.revision(), System.nanoTime() - started, base.activeLeaseCount(), null));
                    Prepared result = Prepared.failed(this, base, candidate, validation, diff,
                            ReloadResult.Status.VALIDATION_FAILED, null);
                    track(result);
                    return result;
                }
                try {
                    emit(new ReloadEvent(ReloadEvent.Kind.PREPARE_SUCCEEDED, base.number, candidateNumber,
                            candidate.revision(), System.nanoTime() - started, base.activeLeaseCount(), null));
                } catch (Throwable failure) {
                    disposePreparation(candidate, base.classLoader, failure);
                    Prepared result = Prepared.failed(this, base, null, validation, diff,
                            ReloadResult.Status.STARTUP_FAILED, failure);
                    track(result);
                    return result;
                }
                if (base.revision.equals(candidate.revision())) {
                    disposePreparation(candidate, base.classLoader, null);
                    Prepared result = Prepared.unchanged(this, base, validation, diff, candidate.revision());
                    track(result);
                    return result;
                }
                if (classLoaderOwnership == GenerationClassLoaderOwnership.CLOSE_ON_RETIREMENT
                        && base.classLoader == candidate.classLoader()) {
                    IllegalStateException failure = new IllegalStateException(
                            "CLOSE_ON_RETIREMENT requires a distinct classloader for every changed generation");
                    disposePreparation(candidate, base.classLoader, failure);
                    Prepared result = Prepared.failed(this, base, null, validation, diff,
                            ReloadResult.Status.STARTUP_FAILED, failure);
                    track(result);
                    return result;
                }
                long startup = System.nanoTime();
                try {
                    emit(new ReloadEvent(ReloadEvent.Kind.CANDIDATE_STARTING, base.number, candidateNumber,
                            candidate.revision(), 0, base.activeLeaseCount(), null));
                    candidate.start();
                    emit(new ReloadEvent(ReloadEvent.Kind.CANDIDATE_STARTED, base.number, candidateNumber,
                            candidate.revision(), System.nanoTime() - startup, base.activeLeaseCount(), null));
                } catch (Throwable failure) {
                    disposePreparation(candidate, base.classLoader, failure);
                    emitSafe(new ReloadEvent(ReloadEvent.Kind.CANDIDATE_FAILED, base.number, candidateNumber,
                            candidate.revision(), System.nanoTime() - startup, base.activeLeaseCount(), ReloadFailure.describe(failure)));
                    Prepared result = Prepared.failed(this, base, null, validation, diff,
                            ReloadResult.Status.STARTUP_FAILED, failure);
                    track(result);
                    return result;
                }
                Prepared result = Prepared.ready(this, base, candidate, validation, diff);
                track(result);
                return result;
            } finally {
                reloadLock.unlock();
            }
        }

        @Override
        public ReloadResult reload(Path configuration) {
            try (PreparedReload prepared = prepare(configuration)) {
                return prepared.activate();
            }
        }

        private ReloadResult activate(Prepared prepared) {
            reloadLock.lock();
            try {
                if (!prepared.beginActivation()) return prepared.currentResult();
                untrack(prepared);
                if (closed) return prepared.finishFailure(ReloadResult.Status.MANAGER_CLOSED, null, currentNumber());
                if (prepared.terminalStatus != null) {
                    ReloadFailure completionFailure = prepared.closeCandidate(prepared.failure);
                    return prepared.finish(prepared.terminalStatus, prepared.baseGeneration(), currentNumber(),
                            completionFailure);
                }
                if (!prepared.changed) {
                    return prepared.finish(ReloadResult.Status.UNCHANGED, prepared.base.number,
                            prepared.base.number, null);
                }
                Generation previous = active;
                if (previous != prepared.base) {
                    return prepared.closeCandidateAndFinish(ReloadResult.Status.STALE_CANDIDATE, null,
                            previous == null ? 0 : previous.number);
                }
                if (reloadPolicy == ReloadPolicy.REJECT_WHILE_BUSY && previous.activeLeaseCount() != 0) {
                    return prepared.closeCandidateAndFinish(ReloadResult.Status.BUSY, null, previous.number);
                }
                BeanContext candidateContext = prepared.candidate.start();
                long handoffStarted = System.nanoTime();
                try {
                    emit(new ReloadEvent(ReloadEvent.Kind.HANDOFF_STARTING, previous.number, previous.number + 1,
                            prepared.revision(), 0, previous.activeLeaseCount(), null));
                    handoff.transfer(previous.requireContext(), candidateContext, prepared.diff);
                    emit(new ReloadEvent(ReloadEvent.Kind.HANDOFF_COMPLETED, previous.number, previous.number + 1,
                            prepared.revision(), System.nanoTime() - handoffStarted,
                            previous.activeLeaseCount(), null));
                } catch (Throwable failure) {
                    emitSafe(new ReloadEvent(ReloadEvent.Kind.HANDOFF_FAILED, previous.number, previous.number + 1,
                            prepared.revision(), System.nanoTime() - handoffStarted,
                            previous.activeLeaseCount(), ReloadFailure.describe(failure)));
                    return prepared.finishFailure(ReloadResult.Status.HANDOFF_FAILED, failure, previous.number);
                }
                candidateContext = prepared.candidate.transfer();
                Generation replacement = new Generation(this, previous.number + 1, prepared.revision(),
                        prepared.validation, candidateContext, prepared.candidate.classLoader(), classLoaderOwnership);
                synchronized (monitor) {
                    if (closed || active != previous) {
                        replacement.forceClose();
                        return prepared.finishFailure(closed ? ReloadResult.Status.MANAGER_CLOSED
                                : ReloadResult.Status.STALE_CANDIDATE, null, currentNumber());
                    }
                    generations.put(replacement.number, replacement);
                    active = replacement;
                }
                Throwable retirementFailure = null;
                try {
                    previous.retire();
                } catch (Throwable failure) {
                    retirementFailure = failure;
                }
                emitSafe(new ReloadEvent(ReloadEvent.Kind.GENERATION_ACTIVATED, previous.number,
                        replacement.number, replacement.revision, 0, previous.activeLeaseCount(), null));
                emitSafe(new ReloadEvent(ReloadEvent.Kind.GENERATION_RETIRED, previous.number,
                        replacement.number, previous.revision, 0, previous.activeLeaseCount(), null));
                Throwable policyFailure = applyRetirementPolicy(previous, reloadPolicy);
                if (retirementFailure == null) retirementFailure = policyFailure;
                else if (policyFailure != null && policyFailure != retirementFailure) retirementFailure.addSuppressed(policyFailure);
                return prepared.finish(ReloadResult.Status.ACTIVATED, previous.number,
                        replacement.number, ReloadFailure.describe(retirementFailure));
            } finally {
                reloadLock.unlock();
            }
        }

        private Throwable applyRetirementPolicy(Generation generation, ReloadPolicy policy) {
            try {
                if (policy == ReloadPolicy.IMMEDIATE) {
                    generation.forceClose();
                } else if (policy == ReloadPolicy.GRACEFUL_WITH_TIMEOUT) {
                    if (!generation.awaitDrained(drainTimeout)) {
                        emitSafe(new ReloadEvent(ReloadEvent.Kind.DRAIN_TIMED_OUT, generation.number,
                                currentNumber(), generation.revision, 0, generation.activeLeaseCount(), null));
                    }
                }
                return null;
            } catch (Throwable failure) {
                return failure;
            }
        }

        @Override
        public List<RetiredGeneration> retiredGenerations() {
            List<RetiredGeneration> result = new ArrayList<>();
            synchronized (monitor) {
                for (Generation generation : generations.values()) {
                    if (generation != active) result.add(generation.snapshot());
                }
            }
            return List.copyOf(result);
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public void close() {
            reloadLock.lock();
            try {
                if (closed) return;
                closed = true;
                emitSafe(new ReloadEvent(ReloadEvent.Kind.MANAGER_CLOSING, currentNumber(), 0,
                        active == null ? null : active.revision, 0,
                        active == null ? 0 : active.activeLeaseCount(), null));
                List<Prepared> pending;
                List<Generation> all;
                synchronized (monitor) {
                    pending = new ArrayList<>(preparations);
                    preparations.clear();
                    all = new ArrayList<>(generations.values());
                    active = null;
                }
                BeanException failure = null;
                for (Prepared preparation : pending) {
                    try {
                        preparation.close();
                    } catch (Throwable error) {
                        failure = append(failure, "Prepared candidate cleanup failed", error);
                    }
                }
                for (int i = all.size() - 1; i >= 0; i--) {
                    try {
                        all.get(i).retire();
                    } catch (Throwable error) {
                        failure = append(failure, "Generation retirement failed", error);
                    }
                }
                for (int i = all.size() - 1; i >= 0; i--) {
                    Generation generation = all.get(i);
                    try {
                        if (shutdownPolicy == ReloadPolicy.IMMEDIATE
                                || shutdownPolicy == ReloadPolicy.REJECT_WHILE_BUSY) {
                            generation.forceClose();
                        } else if (shutdownPolicy == ReloadPolicy.GRACEFUL) {
                            generation.awaitDrained(null);
                            generation.forceClose();
                        } else {
                            if (!generation.awaitDrained(drainTimeout)) generation.forceClose();
                        }
                    } catch (Throwable error) {
                        failure = append(failure, "Generation cleanup failed", error);
                    }
                }
                emitSafe(new ReloadEvent(ReloadEvent.Kind.MANAGER_CLOSED, 0, 0, null, 0, 0, ReloadFailure.describe(failure)));
                if (failure != null) throw failure;
            } finally {
                reloadLock.unlock();
            }
        }

        private void onLeaseDrain(Generation generation) {
            if (generation.isRetired()) generation.closeIfDrained();
        }

        private void onDestroyed(Generation generation, Throwable failure) {
            if (failure == null) {
                emitSafe(new ReloadEvent(ReloadEvent.Kind.DRAIN_COMPLETED, generation.number,
                        currentNumber(), generation.revision, 0, 0, null));
            } else {
                emitSafe(new ReloadEvent(ReloadEvent.Kind.GENERATION_DESTROY_FAILED, generation.number,
                        currentNumber(), generation.revision, 0, 0, ReloadFailure.describe(failure)));
            }
        }

        private void validateOwnedClassLoader(ClassLoader loader) {
            if (classLoaderOwnership == GenerationClassLoaderOwnership.CLOSE_ON_RETIREMENT
                    && !(loader instanceof AutoCloseable)) {
                throw new IllegalStateException("CLOSE_ON_RETIREMENT requires an AutoCloseable classloader: "
                        + loader.getClass().getName());
            }
        }

        private void disposePreparation(XmlBeans.Builder.ReloadPreparation preparation,
                                        ClassLoader protectedClassLoader, Throwable primary) {
            if (preparation == null) return;
            Throwable failure = null;
            try {
                preparation.close();
            } catch (Throwable error) {
                failure = error;
            }
            ClassLoader loader = preparation.classLoader();
            if (classLoaderOwnership == GenerationClassLoaderOwnership.CLOSE_ON_RETIREMENT
                    && loader != null && loader != protectedClassLoader) {
                try {
                    if (!(loader instanceof AutoCloseable closeable)) {
                        throw new IllegalStateException("Generation classloader is not AutoCloseable: "
                                + loader.getClass().getName());
                    }
                    closeable.close();
                } catch (Throwable error) {
                    if (failure == null) failure = error;
                    else if (error != failure) failure.addSuppressed(error);
                }
            }
            if (failure != null) {
                if (primary != null && failure != primary) primary.addSuppressed(failure);
                else throw rethrow("Cannot dispose reload candidate", failure);
            }
        }

        private XmlBeans.Builder newBuilder() {
            return Objects.requireNonNull(builderFactory.get(), "builderFactory returned null");
        }

        private Generation requireActive() {
            Generation selected = active;
            if (selected == null || closed) throw new IllegalStateException("ReloadableBeanContext is closed");
            return selected;
        }

        private long currentNumber() {
            Generation selected = active;
            return selected == null ? 0 : selected.number;
        }

        private void track(Prepared prepared) {
            synchronized (monitor) {
                if (closed) {
                    prepared.close();
                    return;
                }
                preparations.add(prepared);
            }
        }

        private void untrack(Prepared prepared) {
            synchronized (monitor) {
                preparations.remove(prepared);
            }
        }

        private void emit(ReloadEvent event) {
            for (ReloadListener listener : listeners) {
                try {
                    listener.onEvent(event);
                } catch (VirtualMachineError fatal) {
                    throw fatal;
                } catch (Throwable failure) {
                    throw new BeanException("ReloadListener failed for " + event.kind(), failure);
                }
            }
        }

        private void emitSafe(ReloadEvent event) {
            try {
                emit(event);
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable ignored) {
                // Publication and destruction cannot be rolled back for a nonfatal observer failure.
            }
        }

        private static BeanException append(BeanException aggregate, String message, Throwable failure) {
            BeanException result = aggregate == null ? new BeanException(message) : aggregate;
            result.addSuppressed(failure);
            return result;
        }
    }

    private enum GenerationState { ACTIVE, RETIRED, CLOSED }

    private static final class Generation {
        private final DefaultReloadableBeanContext owner;
        private final long number;
        private final ConfigurationRevision revision;
        private final ValidationResult validation;
        private volatile ClassLoader classLoader;
        private final GenerationClassLoaderOwnership classLoaderOwnership;
        private final AtomicLong nextLease = new AtomicLong();
        private final LinkedHashMap<Long, Lease> leases = new LinkedHashMap<>();
        private final List<String> forcedLeaseDiagnostics = new ArrayList<>();
        private volatile BeanContext context;
        private GenerationState state = GenerationState.ACTIVE;
        private Instant retiredAt;
        private Instant closedAt;
        private String destructionFailureText;
        private boolean closing;
        private Thread closingThread;

        private Generation(DefaultReloadableBeanContext owner, long number, ConfigurationRevision revision,
                           ValidationResult validation, BeanContext context, ClassLoader classLoader,
                           GenerationClassLoaderOwnership classLoaderOwnership) {
            this.owner = owner;
            this.number = number;
            this.revision = revision;
            this.validation = validation;
            this.context = context;
            this.classLoader = classLoader;
            this.classLoaderOwnership = classLoaderOwnership;
        }

        private ContextLease tryAcquire(LeaseDiagnostics diagnostics) {
            synchronized (this) {
                if (state != GenerationState.ACTIVE || context == null) return null;
                long id = nextLease.incrementAndGet();
                String trace = diagnostics == LeaseDiagnostics.CAPTURE_STACK ? captureTrace() : "";
                Lease lease = new Lease(this, id, trace);
                leases.put(id, lease);
                return lease;
            }
        }

        private void release(long id) {
            boolean drained;
            synchronized (this) {
                if (leases.remove(id) == null) return;
                drained = leases.isEmpty();
                notifyAll();
            }
            if (drained) owner.onLeaseDrain(this);
        }

        private synchronized int activeLeaseCount() {
            return leases.size();
        }

        private synchronized boolean isRetired() {
            return state == GenerationState.RETIRED;
        }

        private void retire() {
            boolean close;
            synchronized (this) {
                if (state != GenerationState.ACTIVE) return;
                state = GenerationState.RETIRED;
                retiredAt = Instant.now();
                close = leases.isEmpty();
                notifyAll();
            }
            if (close) closeIfDrained();
        }

        private boolean awaitDrained(Duration timeout) {
            long remaining = timeout == null ? Long.MAX_VALUE : timeout.toNanos();
            long end = timeout == null ? Long.MAX_VALUE : System.nanoTime() + remaining;
            synchronized (this) {
                while (!leases.isEmpty() && state != GenerationState.CLOSED) {
                    try {
                        if (timeout == null) {
                            wait();
                        } else {
                            if (remaining <= 0) return false;
                            TimeUnit.NANOSECONDS.timedWait(this, remaining);
                            remaining = end - System.nanoTime();
                        }
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new BeanException("Interrupted while draining generation " + number, failure);
                    }
                }
                return leases.isEmpty();
            }
        }

        private void closeIfDrained() {
            synchronized (this) {
                if (!leases.isEmpty() || state == GenerationState.CLOSED || closing) return;
                closing = true;
                closingThread = Thread.currentThread();
            }
            closeContext(null);
        }

        private void forceClose() {
            List<Lease> forced;
            synchronized (this) {
                while (closing && state != GenerationState.CLOSED && closingThread != Thread.currentThread()) {
                    try {
                        wait();
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new BeanException("Interrupted while waiting for generation " + number + " to close", failure);
                    }
                }
                if (state == GenerationState.CLOSED || closing) return;
                if (state == GenerationState.ACTIVE) {
                    state = GenerationState.RETIRED;
                    retiredAt = Instant.now();
                }
                closing = true;
                closingThread = Thread.currentThread();
                forced = new ArrayList<>(leases.values());
                for (Lease lease : forced) {
                    if (!lease.acquisitionTrace().isBlank()) forcedLeaseDiagnostics.add(lease.acquisitionTrace());
                }
            }
            BeanException leaseFailure = null;
            for (int i = forced.size() - 1; i >= 0; i--) {
                try {
                    forced.get(i).closeFromGeneration();
                } catch (Throwable error) {
                    leaseFailure = DefaultReloadableBeanContext.append(leaseFailure,
                            "Forced lease cleanup failed", error);
                }
            }
            closeContext(leaseFailure);
        }

        private void closeContext(Throwable priorFailure) {
            BeanContext selected = context;
            ClassLoader selectedClassLoader = classLoader;
            Throwable failure = priorFailure;
            try {
                if (selected != null) selected.close();
            } catch (Throwable error) {
                if (failure == null) failure = error;
                else if (error != failure) failure.addSuppressed(error);
            }
            if (classLoaderOwnership == GenerationClassLoaderOwnership.CLOSE_ON_RETIREMENT) {
                try {
                    if (!(selectedClassLoader instanceof AutoCloseable closeable)) {
                        throw new IllegalStateException("Generation classloader is not AutoCloseable: "
                                + selectedClassLoader.getClass().getName());
                    }
                    closeable.close();
                } catch (Throwable error) {
                    if (failure == null) failure = error;
                    else if (error != failure) failure.addSuppressed(error);
                }
            }
            synchronized (this) {
                context = null;
                classLoader = null;
                state = GenerationState.CLOSED;
                closedAt = Instant.now();
                closing = false;
                closingThread = null;
                destructionFailureText = failure == null ? null : throwableSummary(failure);
                notifyAll();
            }
            owner.onDestroyed(this, failure);
            if (failure != null) throw rethrow("Cannot close generation " + number, failure);
        }

        private BeanContext requireContext() {
            BeanContext selected = context;
            if (selected == null) throw new IllegalStateException("Context generation " + number + " is closed");
            return selected;
        }

        private synchronized RetiredGeneration snapshot() {
            String stateText = state.name().toLowerCase();
            List<String> diagnostics = new ArrayList<>(forcedLeaseDiagnostics);
            for (Lease lease : leases.values()) {
                if (!lease.acquisitionTrace().isBlank()) diagnostics.add(lease.acquisitionTrace());
            }
            return new RetiredGeneration(number, revision, stateText, leases.size(), retiredAt, closedAt,
                    destructionFailureText, diagnostics);
        }

        private static String captureTrace() {
            return StackWalker.getInstance().walk(stream -> stream
                    .skip(3).limit(24).map(StackWalker.StackFrame::toString)
                    .reduce((left, right) -> left + "\n" + right).orElse(""));
        }
    }

    private static final class Lease implements ContextLease {
        private final Generation generation;
        private final long id;
        private final String trace;
        private final BeanContext view;
        private final List<LeaseHandle<?>> handles = new ArrayList<>();
        private boolean closed;

        private Lease(Generation generation, long id, String trace) {
            this.generation = generation;
            this.id = id;
            this.trace = trace;
            this.view = new LeaseContext(this);
        }

        @Override public long generation() { return generation.number; }
        @Override public synchronized BeanContext context() {
            ensureOpen();
            return view;
        }
        @Override public synchronized boolean isClosed() { return closed; }
        @Override public String acquisitionTrace() { return trace; }

        @Override
        public void close() {
            List<LeaseHandle<?>> owned;
            synchronized (this) {
                if (closed) return;
                closed = true;
                owned = new ArrayList<>(handles);
                handles.clear();
            }
            BeanException failure = null;
            for (int i = owned.size() - 1; i >= 0; i--) {
                try {
                    owned.get(i).closeFromLease();
                } catch (Throwable error) {
                    failure = DefaultReloadableBeanContext.append(failure, "Lease-owned prototype cleanup failed", error);
                }
            }
            try {
                generation.release(id);
            } catch (Throwable error) {
                failure = DefaultReloadableBeanContext.append(failure, "Generation release failed", error);
            }
            if (failure != null) throw failure;
        }

        private void closeFromGeneration() {
            close();
        }

        private synchronized void ensureOpen() {
            if (closed) throw new IllegalStateException("ContextLease is closed");
        }

        private BeanContext delegate() {
            ensureOpen();
            return generation.requireContext();
        }

        private <T> BeanHandle<T> own(BeanHandle<T> handle) {
            Objects.requireNonNull(handle, "handle");
            LeaseHandle<T> wrapped = new LeaseHandle<>(this, handle);
            synchronized (this) {
                if (closed) {
                    try {
                        handle.close();
                    } catch (Throwable failure) {
                        throw rethrow("Lease closed while registering prototype handle", failure);
                    }
                    throw new IllegalStateException("ContextLease is closed");
                }
                handles.add(wrapped);
            }
            return wrapped;
        }

        private synchronized void released(LeaseHandle<?> handle) {
            handles.remove(handle);
        }
    }

    private static final class LeaseHandle<T> implements BeanHandle<T> {
        private final Lease owner;
        private final BeanHandle<T> delegate;
        private boolean closed;

        private LeaseHandle(Lease owner, BeanHandle<T> delegate) {
            this.owner = owner;
            this.delegate = delegate;
        }

        @Override public synchronized T value() {
            if (closed) throw new IllegalStateException("BeanHandle is closed");
            return delegate.value();
        }

        @Override public synchronized boolean isClosed() {
            return closed || delegate.isClosed();
        }

        @Override public void close() {
            closeInternal(true);
        }

        private void closeFromLease() {
            closeInternal(false);
        }

        private void closeInternal(boolean unregister) {
            synchronized (this) {
                if (closed) return;
                closed = true;
            }
            try {
                delegate.close();
            } finally {
                if (unregister) owner.released(this);
            }
        }
    }

    private static final class LeaseContext implements BeanContext {
        private final Lease lease;
        private LeaseContext(Lease lease) { this.lease = lease; }
        private BeanContext delegate() { return lease.delegate(); }
        @Override public Object require(String id) { return delegate().require(id); }
        @Override public <T> T require(String id, Class<T> type) { return delegate().require(id, type); }
        @Override public <T> T require(Class<T> type) { return delegate().require(type); }
        @Override public <T> T require(TypeRef<T> type) { return delegate().require(type); }
        @Override public Optional<Object> find(String id) { return delegate().find(id); }
        @Override public <T> Optional<T> find(String id, Class<T> type) { return delegate().find(id, type); }
        @Override public <T> Optional<T> find(Class<T> type) { return delegate().find(type); }
        @Override public <T> Optional<T> find(TypeRef<T> type) { return delegate().find(type); }
        @Override public <T> Map<String, T> beansOfType(Class<T> type) { return delegate().beansOfType(type); }
        @Override public <T> Map<String, T> beansOfType(TypeRef<T> type) { return delegate().beansOfType(type); }
        @Override public Type beanType(String id) { return delegate().beanType(id); }
        @Override public BeanHandle<Object> create(String id) { return lease.own(delegate().create(id)); }
        @Override public <T> BeanHandle<T> create(String id, Class<T> type) {
            return lease.own(delegate().create(id, type));
        }
        @Override public boolean contains(String id) { return delegate().contains(id); }
        @Override public Set<String> beanNames() { return delegate().beanNames(); }
        @Override public Set<String> aliases() { return delegate().aliases(); }
        @Override public boolean isClosed() { return lease.isClosed() || delegate().isClosed(); }
        @Override public ContextSnapshot snapshot() { return delegate().snapshot(); }
        @Override public void close() { throw new UnsupportedOperationException("Close the ContextLease, not its context view"); }
    }

    private static final class Prepared implements PreparedReload {
        private final DefaultReloadableBeanContext owner;
        private final Generation base;
        private XmlBeans.Builder.ReloadPreparation candidate;
        private final ValidationResult validation;
        private final ConfigurationDiff diff;
        private final ConfigurationRevision revision;
        private final boolean changed;
        private final ReloadResult.Status terminalStatus;
        private final ReloadFailure failure;
        private boolean activationStarted;
        private boolean closed;
        private ReloadResult result;

        private Prepared(DefaultReloadableBeanContext owner, Generation base,
                         XmlBeans.Builder.ReloadPreparation candidate, ValidationResult validation,
                         ConfigurationDiff diff, ConfigurationRevision revision, boolean changed,
                         ReloadResult.Status terminalStatus, ReloadFailure failure) {
            this.owner = owner;
            this.base = base;
            this.candidate = candidate;
            this.validation = validation;
            this.diff = diff;
            this.revision = revision;
            this.changed = changed;
            this.terminalStatus = terminalStatus;
            this.failure = failure;
        }

        static Prepared ready(DefaultReloadableBeanContext owner, Generation base,
                              XmlBeans.Builder.ReloadPreparation candidate, ValidationResult validation,
                              ConfigurationDiff diff) {
            return new Prepared(owner, base, candidate, validation, diff, candidate.revision(), true, null, null);
        }

        static Prepared unchanged(DefaultReloadableBeanContext owner, Generation base,
                                  ValidationResult validation, ConfigurationDiff diff,
                                  ConfigurationRevision revision) {
            return new Prepared(owner, base, null, validation, diff, revision, false, null, null);
        }

        static Prepared failed(DefaultReloadableBeanContext owner, Generation base,
                               XmlBeans.Builder.ReloadPreparation candidate, ValidationResult validation,
                               ConfigurationDiff diff, ReloadResult.Status status, Throwable failure) {
            ConfigurationRevision revision = candidate == null ? null : candidate.revision();
            return new Prepared(owner, base, candidate, validation, diff, revision, false, status,
                    ReloadFailure.describe(failure));
        }

        static Prepared closed(DefaultReloadableBeanContext owner) {
            return new Prepared(owner, null, null, null, null, null, false,
                    ReloadResult.Status.MANAGER_CLOSED, null);
        }

        @Override public long baseGeneration() { return base == null ? 0 : base.number; }
        @Override public ConfigurationRevision revision() { return revision; }
        @Override public ValidationResult validation() { return validation; }
        @Override public ConfigurationDiff diff() { return diff; }
        @Override public boolean changed() { return changed; }
        @Override public ReloadResult activate() { return owner.activate(this); }
        @Override public synchronized boolean isClosed() { return closed; }

        private synchronized boolean beginActivation() {
            if (result != null || closed || activationStarted) return false;
            activationStarted = true;
            return true;
        }

        private synchronized ReloadResult currentResult() {
            if (result != null) return result;
            return new ReloadResult(ReloadResult.Status.STALE_CANDIDATE, baseGeneration(), owner.currentNumber(), revision,
                    validation, diff, failure);
        }

        private synchronized ReloadResult finish(ReloadResult.Status status, long previous,
                                                 long active, ReloadFailure completionFailure) {
            closed = true;
            candidate = null;
            result = new ReloadResult(status, previous, active, revision, validation, diff, completionFailure);
            return result;
        }

        private ReloadResult finishFailure(ReloadResult.Status status, Throwable error, long active) {
            ReloadFailure completionFailure = closeCandidate(ReloadFailure.describe(error));
            return finish(status, baseGeneration(), active, completionFailure);
        }

        private ReloadResult closeCandidateAndFinish(ReloadResult.Status status, Throwable error, long active) {
            return finishFailure(status, error, active);
        }

        private ReloadFailure closeCandidate(ReloadFailure primary) {
            XmlBeans.Builder.ReloadPreparation selected;
            synchronized (this) {
                selected = candidate;
                candidate = null;
            }
            if (selected == null) return primary;
            try {
                owner.disposePreparation(selected, base == null ? null : base.classLoader, null);
                return primary;
            } catch (Throwable cleanupFailure) {
                return ReloadFailure.combine(primary, cleanupFailure);
            }
        }

        @Override
        public void close() {
            synchronized (this) {
                if (closed) return;
                closed = true;
            }
            owner.untrack(this);
            ReloadFailure cleanupFailure = closeCandidate(null);
            if (cleanupFailure != null) throw new BeanException(cleanupFailure.summary());
        }
    }

    private static RuntimeException rethrow(String message, Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) throw fatal;
        if (failure instanceof RuntimeException runtime) return runtime;
        if (failure instanceof Error error) throw error;
        return new BeanException(message, failure);
    }


    private static String throwableSummary(Throwable failure) {
        StringBuilder result = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        appendThrowableSummary(failure, result, seen);
        return result.toString();
    }

    private static void appendThrowableSummary(Throwable failure, StringBuilder result, Set<Throwable> seen) {
        if (failure == null || !seen.add(failure)) return;
        if (!result.isEmpty()) result.append(" | ");
        result.append(failure.getClass().getName());
        if (failure.getMessage() != null) result.append(": ").append(failure.getMessage());
        appendThrowableSummary(failure.getCause(), result, seen);
        for (Throwable suppressed : failure.getSuppressed()) appendThrowableSummary(suppressed, result, seen);
    }
}
