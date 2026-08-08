package io.github.simpledi.internal;

import io.github.simpledi.BeanContext;
import io.github.simpledi.BeanContextListener;
import io.github.simpledi.BeanLifecycleContext;
import io.github.simpledi.BeanLifecycleInterceptor;
import io.github.simpledi.BeanScope;
import io.github.simpledi.ContextSnapshot;
import io.github.simpledi.BeanEvent;
import io.github.simpledi.BeanException;
import io.github.simpledi.BeanHandle;
import io.github.simpledi.SourceLocation;
import io.github.simpledi.TypeRef;
import io.github.simpledi.internal.Definitions.AutoClosePolicy;
import io.github.simpledi.internal.Definitions.BeanDef;
import io.github.simpledi.internal.Definitions.CallDef;
import io.github.simpledi.internal.Definitions.Document;
import io.github.simpledi.internal.Definitions.FactoryDef;
import io.github.simpledi.internal.Definitions.Ownership;
import io.github.simpledi.internal.Definitions.PropertyDef;
import io.github.simpledi.internal.Definitions.Scope;
import io.github.simpledi.internal.ExecutableResolver.BoundExecutable;
import io.github.simpledi.internal.GraphCompiler.CompiledBean;
import io.github.simpledi.internal.GraphCompiler.CompiledCall;
import io.github.simpledi.internal.GraphCompiler.CompiledInjection;
import io.github.simpledi.internal.GraphCompiler.CompiledProperty;

import java.lang.invoke.MethodHandle;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.function.Consumer;

/** Thread-safe runtime for a fully precompiled graph. User code never runs while the context monitor is held. */
public final class DefaultBeanContext implements BeanContext {
    private enum ContextState { OPEN, CLOSING, CLOSED }

    private record LifecycleEntry(
            String label,
            Object instance,
            Method destroyMethod,
            AutoClosePolicy autoClosePolicy,
            SourceLocation location,
            Ownership ownership,
            boolean retainedByContext,
            BeanLifecycleContext lifecycleContext) {}

    private record IdentityOwner(String label) {}

    private static final class IdentityWeakReference extends WeakReference<Object> {
        private final int identityHash;

        private IdentityWeakReference(Object value, ReferenceQueue<Object> queue) {
            super(value, queue);
            this.identityHash = System.identityHashCode(value);
        }

        @Override
        public int hashCode() {
            return identityHash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof IdentityWeakReference reference)) return false;
            Object left = get();
            return left != null && left == reference.get();
        }
    }

    private static final class BeanSlot {
        private final String id;
        private final CreationTransaction transaction;
        private final Thread creator;
        private final CompletableFuture<Object> future = new CompletableFuture<>();

        private BeanSlot(String id, CreationTransaction transaction) {
            this.id = id;
            this.transaction = transaction;
            this.creator = Thread.currentThread();
        }
    }

    private static final class CreationTransaction {
        private final long id;
        private final Thread owner;
        private final boolean captureCallerOwned;
        private final List<LifecycleEntry> pendingLifecycle = new ArrayList<>();
        private final LinkedHashMap<String, Object> singletonValues = new LinkedHashMap<>();
        private final LinkedHashMap<String, BeanSlot> singletonSlots = new LinkedHashMap<>();
        private final LinkedHashMap<String, Object> customValues = new LinkedHashMap<>();
        private final List<Runnable> commitActions = new ArrayList<>();
        private final List<Consumer<Throwable>> rollbackActions = new ArrayList<>();

        private CreationTransaction(long id, boolean captureCallerOwned) {
            this.id = id;
            this.owner = Thread.currentThread();
            this.captureCallerOwned = captureCallerOwned;
        }
    }

    private record TransactionResult<T>(T value, List<LifecycleEntry> callerOwned) {}

    @FunctionalInterface
    private interface Action<T> {
        T run();
    }

    private final Document document;
    private final Object monitor = new Object();
    private final LinkedHashMap<String, Object> instances = new LinkedHashMap<>();
    private final LinkedHashMap<String, BeanSlot> singletonSlots = new LinkedHashMap<>();
    private final List<LifecycleEntry> lifecycle = new ArrayList<>();
    private final IdentityHashMap<Object, IdentityOwner> knownIdentities = new IdentityHashMap<>();
    private final ReferenceQueue<Object> callerIdentityQueue = new ReferenceQueue<>();
    private final LinkedHashMap<IdentityWeakReference, IdentityOwner> callerIdentities = new LinkedHashMap<>();
    private final ConcurrentHashMap<Executable, Invoker> invokers = new ConcurrentHashMap<>();
    private final IdentityHashMap<Thread, BeanSlot> waitingOn = new IdentityHashMap<>();
    private final Set<String> beanNames;
    private final Set<String> aliases;
    private final LinkedHashMap<String, Type> declaredTypes = new LinkedHashMap<>();
    private final ThreadLocal<CreationTransaction> currentTransaction = new ThreadLocal<>();
    private final ThreadLocal<Deque<String>> creationPath = ThreadLocal.withInitial(ArrayDeque::new);
    private final ThreadLocal<Deque<Ownership>> ownershipPath = ThreadLocal.withInitial(ArrayDeque::new);
    private final ThreadLocal<Integer> operationDepth = ThreadLocal.withInitial(() -> 0);

    private DefaultConverterRegistry converters;
    private ClassLoader classLoader;
    private GraphCompiler compiler;
    private Map<String, CompiledBean> plans;
    private ValueResolver valueResolver;
    private ContextState contextState = ContextState.OPEN;
    private int activeOperations;
    private long nextTransactionId;
    private int liveChildren;
    private Runnable parentRelease;
    private List<BeanContextListener> listeners;
    private List<BeanLifecycleInterceptor> lifecycleInterceptors;
    private LinkedHashMap<String, BeanScope> scopes;

    public DefaultBeanContext(Document document,
                              ClassLoader classLoader,
                              DefaultConverterRegistry converters,
                              PropertyExpander expander,
                              GraphCompiler compiler) {
        this(document, classLoader, converters, expander, compiler, Map.of(), () -> {}, List.of(), List.of(),
                new LinkedHashMap<>());
    }

    public DefaultBeanContext(Document document,
                              ClassLoader classLoader,
                              DefaultConverterRegistry converters,
                              PropertyExpander expander,
                              GraphCompiler compiler,
                              Map<String, ExternalBinding> externalBindings) {
        this(document, classLoader, converters, expander, compiler, externalBindings, () -> {}, List.of(), List.of(),
                new LinkedHashMap<>());
    }

    public DefaultBeanContext(Document document,
                              ClassLoader classLoader,
                              DefaultConverterRegistry converters,
                              PropertyExpander expander,
                              GraphCompiler compiler,
                              Map<String, ExternalBinding> externalBindings,
                              Runnable parentRelease) {
        this(document, classLoader, converters, expander, compiler, externalBindings, parentRelease, List.of(), List.of(),
                new LinkedHashMap<>());
    }

    public DefaultBeanContext(Document document,
                              ClassLoader classLoader,
                              DefaultConverterRegistry converters,
                              PropertyExpander expander,
                              GraphCompiler compiler,
                              Map<String, ExternalBinding> externalBindings,
                              Runnable parentRelease,
                              List<BeanContextListener> listeners,
                              List<BeanLifecycleInterceptor> lifecycleInterceptors,
                              LinkedHashMap<String, BeanScope> scopes) {
        this.document = document;
        this.classLoader = classLoader;
        this.converters = converters;
        this.compiler = compiler;
        this.parentRelease = Objects.requireNonNull(parentRelease, "parentRelease");
        this.listeners = new ArrayList<>(Objects.requireNonNull(listeners, "listeners"));
        this.lifecycleInterceptors = new ArrayList<>(Objects.requireNonNull(lifecycleInterceptors, "lifecycleInterceptors"));
        this.scopes = new LinkedHashMap<>(Objects.requireNonNull(scopes, "scopes"));
        this.plans = compiler.compile();

        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (Map.Entry<String, ExternalBinding> entry : externalBindings.entrySet()) {
            String id = entry.getKey();
            ExternalBinding binding = entry.getValue();
            Object instance = binding.instance();
            names.add(id);
            instances.put(id, instance);
            declaredTypes.put(id, binding.declaredType());
            IdentityOwner previous = knownIdentities.put(instance, new IdentityOwner("external binding '" + id + "'"));
            if (previous != null) {
                throw new IllegalArgumentException("External binding '" + id
                        + "' duplicates object identity owned by " + previous.label());
            }
        }
        names.addAll(document.beans().keySet());
        plans.forEach((id, plan) -> declaredTypes.put(id, plan.declaredType()));
        this.beanNames = Collections.unmodifiableSet(names);
        this.aliases = Collections.unmodifiableSet(new LinkedHashSet<>(document.aliases().keySet()));
        this.valueResolver = new ValueResolver(classLoader, converters, expander,
                this::resolveBeanBoundary, this::resolveOptionalBoundary, this::resolveAnonymousBoundary);
    }

    public DefaultBeanContext start() {
        try {
            emit(BeanEvent.context(BeanEvent.Kind.CONTEXT_STARTING));
            operation(() -> transaction(false, () -> {
                for (Map.Entry<String, BeanDef> entry : document.beans().entrySet()) {
                    if (entry.getValue().scope() == Scope.SINGLETON && !entry.getValue().lazy()) {
                        createBean(entry.getKey());
                    }
                }
                return this;
            }));
            emit(BeanEvent.context(BeanEvent.Kind.CONTEXT_STARTED));
            return this;
        } catch (Throwable failure) {
            try {
                close();
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw propagate(failure);
        }
    }

    @Override
    public Object require(String id) {
        return operation(() -> transaction(false, () -> createBean(canonical(id, null))).value());
    }

    @Override
    public <T> T require(String id, Class<T> type) {
        return operation(() -> transaction(false, () -> cast(id, createBean(canonical(id, null)), type)).value());
    }

    @Override
    public <T> T require(Class<T> type) {
        return operation(() -> transaction(false, () -> {
            List<String> matches = matchingNames(type);
            if (matches.isEmpty()) throw new BeanException("No bean assignable to " + type.getTypeName());
            if (matches.size() > 1) {
                throw new BeanException("Multiple beans assignable to " + type.getTypeName()
                        + ": " + String.join(", ", matches) + ". Require by id.");
            }
            return type.cast(createBean(matches.get(0)));
        }).value());
    }

    @Override
    public <T> T require(TypeRef<T> type) {
        Objects.requireNonNull(type, "type");
        return operation(() -> transaction(false, () -> {
            List<String> matches = matchingNames(type.type());
            if (matches.isEmpty()) throw new BeanException("No bean assignable to " + Types.display(type.type()));
            if (matches.size() > 1) {
                throw new BeanException("Multiple beans assignable to " + Types.display(type.type())
                        + ": " + String.join(", ", matches) + ". Require by id.");
            }
            return this.<T>castGeneric(createBean(matches.get(0)));
        }).value());
    }

    @Override
    public Optional<Object> find(String id) {
        return operation(() -> transaction(false, () -> {
            String canonical = optionalCanonical(id);
            return canonical == null ? Optional.empty() : Optional.of(createBean(canonical));
        }).value());
    }

    @Override
    public <T> Optional<T> find(String id, Class<T> type) {
        return operation(() -> transaction(false, () -> {
            String canonical = optionalCanonical(id);
            if (canonical == null) return Optional.<T>empty();
            return Optional.of(type.cast(createBean(canonical)));
        }).value());
    }

    @Override
    public <T> Optional<T> find(Class<T> type) {
        return operation(() -> transaction(false, () -> {
            List<String> matches = matchingNames(type);
            if (matches.isEmpty()) return Optional.<T>empty();
            if (matches.size() > 1) {
                throw new BeanException("Multiple beans assignable to " + type.getTypeName()
                        + ": " + String.join(", ", matches) + ". Find by id.");
            }
            return Optional.of(type.cast(createBean(matches.get(0))));
        }).value());
    }

    @Override
    public <T> Optional<T> find(TypeRef<T> type) {
        Objects.requireNonNull(type, "type");
        return operation(() -> transaction(false, () -> {
            List<String> matches = matchingNames(type.type());
            if (matches.isEmpty()) return Optional.<T>empty();
            if (matches.size() > 1) {
                throw new BeanException("Multiple beans assignable to " + Types.display(type.type())
                        + ": " + String.join(", ", matches) + ". Find by id.");
            }
            return Optional.of(this.<T>castGeneric(createBean(matches.get(0))));
        }).value());
    }

    @Override
    public <T> Map<String, T> beansOfType(Class<T> type) {
        return operation(() -> transaction(false, () -> {
            LinkedHashMap<String, T> result = new LinkedHashMap<>();
            for (String id : matchingNames(type)) result.put(id, type.cast(createBean(id)));
            return Collections.unmodifiableMap(result);
        }).value());
    }

    @Override
    public <T> Map<String, T> beansOfType(TypeRef<T> type) {
        Objects.requireNonNull(type, "type");
        return operation(() -> transaction(false, () -> {
            LinkedHashMap<String, T> result = new LinkedHashMap<>();
            for (String id : matchingNames(type.type())) result.put(id, this.<T>castGeneric(createBean(id)));
            return Collections.unmodifiableMap(result);
        }).value());
    }

    @Override
    public Type beanType(String id) {
        return operation(() -> declaredTypes.get(canonical(id, null)));
    }

    @Override
    public BeanHandle<Object> create(String id) {
        return create(id, Object.class);
    }

    @Override
    public <T> BeanHandle<T> create(String id, Class<T> type) {
        return operation(() -> {
            if (currentTransaction.get() != null) {
                throw new IllegalStateException("BeanContext.create() cannot be nested inside bean creation");
            }
            String canonical = canonical(id, null);
            CompiledBean plan = plans.get(canonical);
            if (plan == null || plan.definition().scope() != Scope.PROTOTYPE) {
                throw new BeanException("Bean '" + id + "' is not a prototype");
            }
            if (plan.definition().ownership() != Ownership.CALLER) {
                throw new BeanException("Bean '" + id + "' is context-owned; use require(), not create()");
            }
            TransactionResult<T> result = transaction(true, () -> cast(id, createBean(canonical), type));
            return new DetachedBeanHandle<>(result.value(), result.callerOwned(),
                    List.copyOf(lifecycleInterceptors));
        });
    }

    @Override
    public boolean contains(String id) {
        return operation(() -> optionalCanonical(id) != null);
    }

    @Override
    public Set<String> beanNames() {
        return beanNames;
    }

    @Override
    public Set<String> aliases() {
        return aliases;
    }

    @Override
    public boolean isClosed() {
        synchronized (monitor) {
            return contextState != ContextState.OPEN;
        }
    }

    @Override
    public ContextSnapshot snapshot() {
        synchronized (monitor) {
            List<String> created = new ArrayList<>();
            for (String id : document.beans().keySet()) {
                if (instances.containsKey(id)) created.add(id);
            }
            return new ContextSnapshot(contextState.name().toLowerCase(), created,
                    new ArrayList<>(singletonSlots.keySet()), activeOperations, liveChildren, lifecycle.size());
        }
    }

    /** Registers a live child and returns an idempotent release action. */
    public Runnable retainChild() {
        synchronized (monitor) {
            ensureOpenLocked();
            liveChildren++;
        }
        AtomicBoolean released = new AtomicBoolean();
        return () -> {
            if (!released.compareAndSet(false, true)) return;
            synchronized (monitor) {
                if (liveChildren <= 0) throw new IllegalStateException("Child context lease underflow");
                liveChildren--;
                monitor.notifyAll();
            }
        };
    }

    @Override
    public void close() {
        if (operationDepth.get() > 0) {
            throw new IllegalStateException("BeanContext.close() cannot be called from an active context operation");
        }

        List<LifecycleEntry> toDestroy;
        List<BeanScope> scopesToClose;
        Runnable releaseParent;
        boolean interrupted = false;
        synchronized (monitor) {
            if (contextState == ContextState.CLOSED) return;
            if (contextState == ContextState.CLOSING) {
                while (contextState != ContextState.CLOSED) {
                    try {
                        monitor.wait();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                if (interrupted) Thread.currentThread().interrupt();
                return;
            }
            if (liveChildren != 0) {
                throw new IllegalStateException("Cannot close BeanContext while " + liveChildren
                        + " child context(s) are still open");
            }
            contextState = ContextState.CLOSING;
            while (activeOperations != 0) {
                try {
                    monitor.wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            toDestroy = List.copyOf(lifecycle);
            scopesToClose = scopes == null ? List.of() : new ArrayList<>(scopes.values());
        }

        BeanException failure = null;
        try {
            emit(BeanEvent.context(BeanEvent.Kind.CONTEXT_CLOSING));
        } catch (Throwable eventFailure) {
            failure = appendFailure(failure, eventFailure, "Context closing listener failed");
        }
        for (int i = scopesToClose.size() - 1; i >= 0; i--) {
            try {
                scopesToClose.get(i).close();
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable scopeFailure) {
                failure = appendFailure(failure, scopeFailure, "Custom scope shutdown failed");
            }
        }
        BeanException destructionFailure = destroyEntries(toDestroy, "context shutdown");
        if (destructionFailure != null) {
            failure = failure == null ? destructionFailure
                    : appendFailure(failure, destructionFailure, "Context shutdown failed");
        }

        synchronized (monitor) {
            lifecycle.clear();
            knownIdentities.clear();
            callerIdentities.clear();
            while (callerIdentityQueue.poll() != null) {
                // Drain stale references so a retained closed context holds no caller identity metadata.
            }
            instances.clear();
            singletonSlots.clear();
            waitingOn.clear();
            declaredTypes.clear();
            invokers.clear();
            if (converters != null) converters.clear();
            converters = null;
            valueResolver = null;
            plans = null;
            compiler = null;
            if (scopes != null) scopes.clear();
            scopes = null;
            classLoader = null;
            releaseParent = parentRelease;
            parentRelease = null;
            contextState = ContextState.CLOSED;
            monitor.notifyAll();
        }
        try {
            releaseParent.run();
        } catch (Throwable releaseFailure) {
            if (releaseFailure instanceof VirtualMachineError fatal) throw fatal;
            failure = appendFailure(failure, releaseFailure, "Failed to release parent context");
        }
        try {
            emit(BeanEvent.context(BeanEvent.Kind.CONTEXT_CLOSED));
        } catch (Throwable eventFailure) {
            failure = appendFailure(failure, eventFailure, "Context closed listener failed");
        } finally {
            synchronized (monitor) {
                if (listeners != null) listeners.clear();
                listeners = null;
                if (lifecycleInterceptors != null) lifecycleInterceptors.clear();
                lifecycleInterceptors = null;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (failure != null) throw failure;
    }

    private static BeanException appendFailure(BeanException aggregate, Throwable failure, String message) {
        if (aggregate == null) {
            BeanException result = new BeanException(message);
            result.addSuppressed(failure);
            return result;
        }
        aggregate.addSuppressed(failure);
        return aggregate;
    }

    private <T> T operation(Action<T> action) {
        beginOperation();
        try {
            return action.run();
        } finally {
            endOperation();
        }
    }

    private void beginOperation() {
        int depth = operationDepth.get();
        synchronized (monitor) {
            if (contextState == ContextState.CLOSED
                    || (contextState == ContextState.CLOSING && depth == 0)) {
                throw new IllegalStateException("BeanContext is closed");
            }
            activeOperations++;
        }
        operationDepth.set(depth + 1);
    }

    private void endOperation() {
        int depth = operationDepth.get() - 1;
        if (depth == 0) operationDepth.remove();
        else operationDepth.set(depth);
        synchronized (monitor) {
            activeOperations--;
            if (activeOperations == 0) monitor.notifyAll();
        }
    }

    private <T> TransactionResult<T> transaction(boolean captureCallerOwned, Action<T> action) {
        CreationTransaction existing = currentTransaction.get();
        if (existing != null) {
            return new TransactionResult<>(action.run(), List.of());
        }

        CreationTransaction transaction;
        synchronized (monitor) {
            transaction = new CreationTransaction(++nextTransactionId, captureCallerOwned);
        }
        currentTransaction.set(transaction);
        try {
            T value = action.run();
            List<LifecycleEntry> callerOwned = commit(transaction);
            return new TransactionResult<>(value, callerOwned);
        } catch (Throwable failure) {
            rollback(transaction, failure);
            throw propagate(failure);
        } finally {
            currentTransaction.remove();
            clearThreadCreationState();
        }
    }

    private List<LifecycleEntry> commit(CreationTransaction transaction) {
        for (Runnable action : transaction.commitActions) action.run();
        List<Map.Entry<BeanSlot, Object>> completions = new ArrayList<>();
        List<LifecycleEntry> callerOwned = new ArrayList<>();
        synchronized (monitor) {
            for (Map.Entry<String, Object> entry : transaction.singletonValues.entrySet()) {
                instances.put(entry.getKey(), entry.getValue());
                BeanSlot slot = transaction.singletonSlots.get(entry.getKey());
                if (slot != null) {
                    singletonSlots.remove(entry.getKey(), slot);
                    completions.add(Map.entry(slot, entry.getValue()));
                }
            }
            for (LifecycleEntry entry : transaction.pendingLifecycle) {
                if (entry.ownership() == Ownership.CONTEXT) {
                    lifecycle.add(entry);
                } else if (entry.ownership() == Ownership.CALLER) {
                    knownIdentities.remove(entry.instance());
                    rememberCallerIdentity(entry.instance(), new IdentityOwner("caller-owned bean '" + entry.label() + "'"));
                    if (transaction.captureCallerOwned) callerOwned.add(entry);
                } else if (entry.ownership() == Ownership.EXTERNAL) {
                    if (!entry.retainedByContext()) {
                        knownIdentities.remove(entry.instance());
                        rememberCallerIdentity(entry.instance(),
                                new IdentityOwner("externally owned bean '" + entry.label() + "'"));
                    }
                }
            }
        }
        for (Map.Entry<BeanSlot, Object> completion : completions) {
            completion.getKey().future.complete(completion.getValue());
        }
        return List.copyOf(callerOwned);
    }

    private void rollback(CreationTransaction transaction, Throwable failure) {
        emitFailure(BeanEvent.context(BeanEvent.Kind.ROLLBACK_STARTING), failure);
        BeanException cleanup = null;
        for (int i = transaction.rollbackActions.size() - 1; i >= 0; i--) {
            try {
                transaction.rollbackActions.get(i).accept(failure);
            } catch (Throwable rollbackFailure) {
                if (cleanup == null) cleanup = new BeanException("Custom scope rollback failed");
                cleanup.addSuppressed(rollbackFailure);
            }
        }
        BeanException lifecycleCleanup = destroyEntries(transaction.pendingLifecycle, "creation rollback");
        if (lifecycleCleanup != null) {
            if (cleanup == null) cleanup = lifecycleCleanup;
            else cleanup.addSuppressed(lifecycleCleanup);
        }
        List<BeanSlot> failedSlots = new ArrayList<>();
        synchronized (monitor) {
            for (LifecycleEntry entry : transaction.pendingLifecycle) {
                knownIdentities.remove(entry.instance());
            }
            for (Map.Entry<String, BeanSlot> entry : transaction.singletonSlots.entrySet()) {
                if (singletonSlots.remove(entry.getKey(), entry.getValue())) failedSlots.add(entry.getValue());
            }
        }
        for (BeanSlot slot : failedSlots) slot.future.completeExceptionally(failure);
        if (cleanup != null) failure.addSuppressed(cleanup);
        emitFailure(BeanEvent.context(BeanEvent.Kind.ROLLBACK_COMPLETED), failure);
    }

    private Object resolveBeanBoundary(String id) {
        if (currentTransaction.get() != null) return createBean(canonical(id, null));
        return operation(() -> transaction(false, () -> createBean(canonical(id, null))).value());
    }

    private Optional<Object> resolveOptionalBoundary(String id) {
        if (currentTransaction.get() != null) {
            String canonical = optionalCanonical(id);
            return canonical == null ? Optional.empty() : Optional.of(createBean(canonical));
        }
        return operation(() -> transaction(false, () -> {
            String canonical = optionalCanonical(id);
            return canonical == null ? Optional.empty() : Optional.of(createBean(canonical));
        }).value());
    }

    private Object resolveAnonymousBoundary(BeanDef definition) {
        if (currentTransaction.get() != null) return createAnonymous(definition);
        return operation(() -> transaction(false, () -> createAnonymous(definition)).value());
    }

    private Object createBean(String requestedId) {
        CreationTransaction transaction = requireTransaction();
        String id = canonical(requestedId, null);

        synchronized (monitor) {
            Object existing = instances.get(id);
            if (existing != null) return existing;
        }
        Object transactionValue = transaction.singletonValues.get(id);
        if (transactionValue != null) return transactionValue;

        Deque<String> path = creationPath.get();
        if (path.contains(id)) {
            List<String> cycle = new ArrayList<>(path);
            cycle.add(id);
            SourceLocation location = plans.get(id) == null ? null : plans.get(id).definition().location();
            if (location == null) throw new BeanException("Circular dependency during creation: " + String.join(" -> ", cycle));
            throw new BeanException(location, "Circular dependency during creation: " + String.join(" -> ", cycle));
        }

        CompiledBean plan = plans.get(id);
        if (plan == null) {
            synchronized (monitor) {
                Object external = instances.get(id);
                if (external != null) return external;
            }
            throw new BeanException("Unknown bean reference '" + id + "'");
        }
        return switch (plan.definition().scope()) {
            case SINGLETON -> createSingleton(id, plan, transaction);
            case PROTOTYPE -> createPrototype(id, plan);
            case CUSTOM -> createCustomScoped(id, plan, transaction);
        };
    }

    private Object createCustomScoped(String id, CompiledBean plan, CreationTransaction transaction) {
        String scopeName = plan.definition().scopeName();
        String localKey = scopeName + "\u0000" + id;
        Object local = transaction.customValues.get(localKey);
        if (local != null) return local;
        BeanScope scope = scopes.get(scopeName);
        if (scope == null) {
            throw new BeanException(plan.definition().location(),
                    "No BeanScope registered for custom scope '" + scopeName + "'");
        }
        BeanScope.Reservation reservation = scope.reserve(id);
        if (!reservation.creator()) return reservation.await().value();

        int lifecycleStart = transaction.pendingLifecycle.size();
        Object instance;
        try {
            enterCreation(id, Ownership.CALLER, plan.definition().location());
            try {
                for (String dependency : plan.definition().dependsOn()) {
                    createBean(canonical(dependency, plan.definition().location()));
                }
                instance = createFromPlan(id, plan, Ownership.CALLER);
            } finally {
                exitCreation();
            }
        } catch (Throwable failure) {
            reservation.cancel(failure);
            throw failure;
        }

        List<LifecycleEntry> scopedEntries = new ArrayList<>();
        for (int i = transaction.pendingLifecycle.size() - 1; i >= lifecycleStart; i--) {
            LifecycleEntry entry = transaction.pendingLifecycle.get(i);
            if (entry.ownership() == Ownership.CALLER) {
                scopedEntries.add(0, entry);
                transaction.pendingLifecycle.remove(i);
            }
        }
        DetachedBeanHandle<Object> handle = new DetachedBeanHandle<>(instance, scopedEntries,
                List.copyOf(lifecycleInterceptors));
        transaction.customValues.put(localKey, instance);
        transaction.commitActions.add(() -> {
            synchronized (monitor) {
                for (LifecycleEntry entry : scopedEntries) {
                    knownIdentities.remove(entry.instance());
                    rememberCallerIdentity(entry.instance(),
                            new IdentityOwner("custom-scoped bean '" + entry.label() + "'"));
                }
            }
            reservation.publish(handle);
        });
        transaction.rollbackActions.add(failure -> {
            synchronized (monitor) {
                for (LifecycleEntry entry : scopedEntries) knownIdentities.remove(entry.instance());
            }
            reservation.cancel(failure);
            handle.close();
        });
        return instance;
    }

    private Object createSingleton(String id, CompiledBean plan, CreationTransaction transaction) {
        BeanSlot slot;
        boolean creator = false;
        synchronized (monitor) {
            Object existing = instances.get(id);
            if (existing != null) return existing;
            Object local = transaction.singletonValues.get(id);
            if (local != null) return local;
            slot = singletonSlots.get(id);
            if (slot == null) {
                slot = new BeanSlot(id, transaction);
                singletonSlots.put(id, slot);
                transaction.singletonSlots.put(id, slot);
                creator = true;
            }
        }
        if (!creator) return await(slot);

        enterCreation(id, plan.definition().ownership(), plan.definition().location());
        try {
            for (String dependency : plan.definition().dependsOn()) {
                createBean(canonical(dependency, plan.definition().location()));
            }
            Object instance = createFromPlan(id, plan, plan.definition().ownership());
            transaction.singletonValues.put(id, instance);
            return instance;
        } finally {
            exitCreation();
        }
    }

    private Object createPrototype(String id, CompiledBean plan) {
        enterCreation(id, plan.definition().ownership(), plan.definition().location());
        try {
            for (String dependency : plan.definition().dependsOn()) {
                createBean(canonical(dependency, plan.definition().location()));
            }
            return createFromPlan(id, plan, plan.definition().ownership());
        } finally {
            exitCreation();
        }
    }

    private Object await(BeanSlot slot) {
        Thread current = Thread.currentThread();
        synchronized (monitor) {
            waitingOn.put(current, slot);
            List<String> cycle = waitCycle(current, slot);
            if (cycle != null) {
                waitingOn.remove(current);
                throw new BeanException("Cross-thread circular dependency: " + String.join(" -> ", cycle));
            }
        }
        try {
            return slot.future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BeanException("Interrupted while waiting for singleton '" + slot.id + "'", e);
        } catch (ExecutionException e) {
            throw propagate(unwrap(e));
        } finally {
            synchronized (monitor) {
                waitingOn.remove(current);
            }
        }
    }

    private List<String> waitCycle(Thread start, BeanSlot first) {
        List<String> ids = new ArrayList<>();
        BeanSlot slot = first;
        Set<Thread> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (slot != null) {
            ids.add(slot.id);
            Thread creator = slot.creator;
            if (creator == start) {
                ids.add(first.id);
                return ids;
            }
            if (!visited.add(creator)) return null;
            slot = waitingOn.get(creator);
        }
        return null;
    }

    private void enterCreation(String label, Ownership ownership, SourceLocation location) {
        Deque<String> path = creationPath.get();
        if (path.contains(label)) {
            List<String> cycle = new ArrayList<>(path);
            cycle.add(label);
            throw new BeanException(location, "Circular dependency during creation: " + String.join(" -> ", cycle));
        }
        path.addLast(label);
        ownershipPath.get().addLast(ownership);
    }

    private void exitCreation() {
        Deque<String> path = creationPath.get();
        Deque<Ownership> owners = ownershipPath.get();
        if (!path.isEmpty()) path.removeLast();
        if (!owners.isEmpty()) owners.removeLast();
    }

    private Object createAnonymous(BeanDef definition) {
        String label = "<nested@" + definition.location().line() + ":" + definition.location().column() + ">";
        Ownership ownership = currentOwnership();
        enterCreation(label, ownership, definition.location());
        try {
            CompiledBean plan = compiler.nested(definition);
            for (String dependency : definition.dependsOn()) createBean(canonical(dependency, definition.location()));
            return createFromPlan(label, plan, ownership);
        } finally {
            exitCreation();
        }
    }

    private Ownership currentOwnership() {
        Deque<Ownership> owners = ownershipPath.get();
        if (owners.isEmpty()) return Ownership.CONTEXT;
        Ownership ownership = owners.peekLast();
        return ownership == Ownership.INHERIT ? Ownership.CONTEXT : ownership;
    }

    private Object createFromPlan(String label, CompiledBean plan, Ownership ownership) {
        BeanDef definition = plan.definition();
        long started = System.nanoTime();
        try {
            emit(new BeanEvent(BeanEvent.Kind.BEAN_CREATING, label, definition.location(), 0, null));
            BoundExecutable creator = plan.creator();
            List<Definitions.ValueDef> values;
            Object target = null;
            if (definition.factory() == null) {
                values = creator.orderedValues(definition.constructorArgs());
            } else {
                FactoryDef factory = definition.factory();
                values = creator.orderedValues(factory.args());
                if (plan.factoryBean() != null) target = createBean(plan.factoryBean());
            }

            Object[] resolved = valueResolver.resolveAll(values, creator.inputTypes());
            List<Object[]> preparedInjections = new ArrayList<>(plan.injections().size());
            for (CompiledInjection injection : plan.injections()) {
                if (injection instanceof CompiledProperty property) {
                    preparedInjections.add(new Object[] {
                            valueResolver.resolve(property.definition().value(), property.binding().inputTypes()[0])
                    });
                } else if (injection instanceof CompiledCall call) {
                    preparedInjections.add(valueResolver.resolveAll(
                            call.binding().orderedValues(call.definition().args()), call.binding().inputTypes()));
                }
            }

            Object instance = invoke(creator.executable(), target, creator.pack(resolved), definition.location());
            if (instance == null) {
                throw new BeanException(definition.location(), "Bean creator produced null for " + definition.className());
            }
            if (!plan.productType().isInstance(instance)) {
                throw new BeanException(definition.location(), "Created " + instance.getClass().getTypeName()
                        + " but bean declares " + plan.productType().getTypeName());
            }
            BeanLifecycleContext lifecycleContext = registerPending(label, instance, plan.destroyMethod(),
                    definition.autoClosePolicy(), definition.location(), ownership,
                    definition.scope() == Scope.SINGLETON, plan.declaredType(), definition.scopeName());
            interceptAfterConstruction(lifecycleContext, instance);

            for (int i = 0; i < plan.injections().size(); i++) {
                CompiledInjection injection = plan.injections().get(i);
                Object[] prepared = preparedInjections.get(i);
                if (injection instanceof CompiledProperty property) {
                    invoke(property.binding().executable(), instance, property.binding().pack(prepared),
                            property.definition().location());
                } else if (injection instanceof CompiledCall call) {
                    invoke(call.binding().executable(), instance, call.binding().pack(prepared), call.definition().location());
                }
            }
            interceptBeforeInitialization(lifecycleContext, instance);
            if (plan.initMethod() != null) invoke(plan.initMethod(), instance, new Object[0], definition.location());
            interceptAfterInitialization(lifecycleContext, instance);
            emit(new BeanEvent(BeanEvent.Kind.BEAN_CREATED, label, definition.location(),
                    System.nanoTime() - started, null));
            return instance;
        } catch (Throwable failure) {
            emitFailure(new BeanEvent(BeanEvent.Kind.BEAN_FAILED, label, definition.location(),
                    System.nanoTime() - started, failure), failure);
            throw propagate(failure);
        }
    }

    private BeanLifecycleContext registerPending(String label, Object instance, Method destroyMethod,
                                 AutoClosePolicy autoClosePolicy, SourceLocation location,
                                 Ownership ownership, boolean retainedByContext,
                                 Type declaredType, String scopeName) {
        CreationTransaction transaction = requireTransaction();
        Ownership effective = ownership == Ownership.INHERIT ? currentOwnership() : ownership;
        BeanLifecycleContext lifecycleContext = new BeanLifecycleContext(label, declaredType, scopeName,
                effective.name().toLowerCase(), location);
        LifecycleEntry entry = new LifecycleEntry(label, instance, destroyMethod, autoClosePolicy,
                location, effective, retainedByContext, lifecycleContext);
        synchronized (monitor) {
            expungeCallerIdentities();
            IdentityOwner existing = knownIdentities.get(instance);
            if (existing == null) {
                existing = callerIdentities.get(new IdentityWeakReference(instance, null));
            }
            if (existing != null) {
                throw new BeanException(location, "Creator for '" + label + "' returned the same object already owned by "
                        + existing.label() + ". Use <alias> or a single external binding instead.");
            }
            knownIdentities.put(instance, new IdentityOwner("bean '" + label + "' in transaction " + transaction.id));
            transaction.pendingLifecycle.add(entry);
        }
        return lifecycleContext;
    }

    private void interceptAfterConstruction(BeanLifecycleContext context, Object bean) {
        for (BeanLifecycleInterceptor interceptor : lifecycleInterceptors) {
            invokeInterceptor(() -> interceptor.afterConstruction(context, bean), context,
                    "afterConstruction");
        }
    }

    private void interceptBeforeInitialization(BeanLifecycleContext context, Object bean) {
        for (BeanLifecycleInterceptor interceptor : lifecycleInterceptors) {
            invokeInterceptor(() -> interceptor.beforeInitialization(context, bean), context,
                    "beforeInitialization");
        }
    }

    private void interceptAfterInitialization(BeanLifecycleContext context, Object bean) {
        for (int i = lifecycleInterceptors.size() - 1; i >= 0; i--) {
            BeanLifecycleInterceptor interceptor = lifecycleInterceptors.get(i);
            invokeInterceptor(() -> interceptor.afterInitialization(context, bean), context,
                    "afterInitialization");
        }
    }

    @FunctionalInterface
    private interface InterceptorAction { void run() throws Exception; }

    private static void invokeInterceptor(InterceptorAction action, BeanLifecycleContext context, String phase) {
        try {
            action.run();
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            throw new BeanException(context.location(), "BeanLifecycleInterceptor failed during " + phase
                    + " for bean '" + context.beanId() + "': " + failure, failure);
        }
    }

    @FunctionalInterface
    private interface Invoker {
        Object invoke(Object target, Object[] arguments) throws Throwable;
    }

    private Object invoke(Executable executable, Object target, Object[] args, SourceLocation location) {
        try {
            return invokers.computeIfAbsent(executable, this::createInvoker).invoke(target, args);
        } catch (BeanException e) {
            throw e;
        } catch (Throwable e) {
            Throwable root = unwrap(e);
            if (root instanceof VirtualMachineError fatal) throw fatal;
            Deque<String> path = creationPath.get();
            String suffix = path.isEmpty() ? "" : " while creating " + String.join(" -> ", path);
            throw new BeanException(location, "Invocation failed for " + ExecutableResolver.signature(executable)
                    + suffix + ": " + root, root);
        }
    }

    private Invoker createInvoker(Executable executable) {
        try {
            MethodHandle handle;
            if (executable instanceof Constructor<?> constructor) {
                handle = MethodHandles.lookup().unreflectConstructor(constructor).asFixedArity();
                return (target, arguments) -> handle.invokeWithArguments(arguments);
            }
            Method method = (Method) executable;
            handle = MethodHandles.lookup().unreflect(method).asFixedArity();
            return (target, arguments) -> {
                if (Modifier.isStatic(method.getModifiers())) return handle.invokeWithArguments(arguments);
                List<Object> invocation = new ArrayList<>(arguments.length + 1);
                invocation.add(target);
                Collections.addAll(invocation, arguments);
                return handle.invokeWithArguments(invocation);
            };
        } catch (IllegalAccessException inaccessibleToMethodHandles) {
            if (executable instanceof Constructor<?> constructor) {
                return (target, arguments) -> constructor.newInstance(arguments);
            }
            Method method = (Method) executable;
            return (target, arguments) -> method.invoke(target, arguments);
        }
    }

    private BeanException destroyEntries(List<LifecycleEntry> entries, String action) {
        BeanException failure = null;
        for (int i = entries.size() - 1; i >= 0; i--) {
            LifecycleEntry entry = entries.get(i);
            try {
                destroy(entry);
            } catch (Throwable error) {
                if (failure == null) {
                    failure = new BeanException(entry.location(),
                            "One or more bean destruction operations failed during " + action);
                }
                failure.addSuppressed(error);
            }
        }
        return failure;
    }

    private void destroy(LifecycleEntry entry) {
        if (entry.ownership() == Ownership.EXTERNAL) return;
        long started = System.nanoTime();
        Throwable failure = null;
        try {
            emit(new BeanEvent(BeanEvent.Kind.BEAN_DESTROYING, entry.label(), entry.location(), 0, null));
        } catch (Throwable listenerFailure) {
            failure = listenerFailure;
        }
        try {
            interceptBeforeDestruction(entry);
        } catch (Throwable interceptorFailure) {
            if (failure == null) failure = interceptorFailure;
            else failure.addSuppressed(interceptorFailure);
        }
        try {
            performDestroyActions(entry);
        } catch (Throwable destructionFailure) {
            if (failure == null) failure = destructionFailure;
            else failure.addSuppressed(destructionFailure);
        }
        try {
            interceptAfterDestruction(entry, failure);
        } catch (Throwable interceptorFailure) {
            if (failure == null) failure = interceptorFailure;
            else failure.addSuppressed(interceptorFailure);
        }
        if (failure == null) {
            emit(new BeanEvent(BeanEvent.Kind.BEAN_DESTROYED, entry.label(), entry.location(),
                    System.nanoTime() - started, null));
            return;
        }
        emitFailure(new BeanEvent(BeanEvent.Kind.BEAN_DESTROY_FAILED, entry.label(), entry.location(),
                System.nanoTime() - started, failure), failure);
        throw propagate(failure);
    }

    private void interceptBeforeDestruction(LifecycleEntry entry) {
        BeanException aggregate = null;
        for (int i = lifecycleInterceptors.size() - 1; i >= 0; i--) {
            BeanLifecycleInterceptor interceptor = lifecycleInterceptors.get(i);
            try {
                invokeInterceptor(() -> interceptor.beforeDestruction(entry.lifecycleContext(), entry.instance()),
                        entry.lifecycleContext(), "beforeDestruction");
            } catch (BeanException failure) {
                if (aggregate == null) aggregate = new BeanException(entry.location(),
                        "One or more BeanLifecycleInterceptor callbacks failed before destruction");
                aggregate.addSuppressed(failure);
            }
        }
        if (aggregate != null) throw aggregate;
    }

    private void interceptAfterDestruction(LifecycleEntry entry, Throwable failure) {
        BeanException aggregate = null;
        for (BeanLifecycleInterceptor interceptor : lifecycleInterceptors) {
            try {
                invokeInterceptor(() -> interceptor.afterDestruction(entry.lifecycleContext(), entry.instance(), failure),
                        entry.lifecycleContext(), "afterDestruction");
            } catch (BeanException callbackFailure) {
                if (aggregate == null) aggregate = new BeanException(entry.location(),
                        "One or more BeanLifecycleInterceptor callbacks failed after destruction");
                aggregate.addSuppressed(callbackFailure);
            }
        }
        if (aggregate != null) throw aggregate;
    }

    private void performDestroyActions(LifecycleEntry entry) {
        Method explicit = entry.destroyMethod();
        boolean autoClose = entry.instance() instanceof AutoCloseable
                && !(explicit != null && explicit.getName().equals("close") && explicit.getParameterCount() == 0);
        switch (entry.autoClosePolicy()) {
            case FALLBACK -> {
                if (explicit != null) invoke(explicit, entry.instance(), new Object[0], entry.location());
                else if (autoClose) closeInstance(entry);
            }
            case BEFORE -> {
                Throwable failure = null;
                if (autoClose) {
                    try { closeInstance(entry); } catch (Throwable error) { failure = error; }
                }
                if (explicit != null) {
                    try { invoke(explicit, entry.instance(), new Object[0], entry.location()); }
                    catch (Throwable error) {
                        if (failure == null) failure = error;
                        else failure.addSuppressed(error);
                    }
                }
                if (failure != null) throw propagate(failure);
            }
            case AFTER -> {
                Throwable failure = null;
                if (explicit != null) {
                    try { invoke(explicit, entry.instance(), new Object[0], entry.location()); }
                    catch (Throwable error) { failure = error; }
                }
                if (autoClose) {
                    try { closeInstance(entry); } catch (Throwable error) {
                        if (failure == null) failure = error;
                        else failure.addSuppressed(error);
                    }
                }
                if (failure != null) throw propagate(failure);
            }
            case NEVER -> {
                if (explicit != null) invoke(explicit, entry.instance(), new Object[0], entry.location());
            }
        }
    }

    private static void closeInstance(LifecycleEntry entry) {
        try {
            ((AutoCloseable) entry.instance()).close();
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            throw new BeanException(entry.location(), "AutoCloseable.close() failed for bean '"
                    + entry.label() + "' (" + entry.instance().getClass().getTypeName() + ")", failure);
        }
    }

    private void rememberCallerIdentity(Object instance, IdentityOwner owner) {
        expungeCallerIdentities();
        callerIdentities.put(new IdentityWeakReference(instance, callerIdentityQueue), owner);
    }

    private void expungeCallerIdentities() {
        IdentityWeakReference reference;
        while ((reference = (IdentityWeakReference) callerIdentityQueue.poll()) != null) {
            callerIdentities.remove(reference);
        }
    }

    private CreationTransaction requireTransaction() {
        CreationTransaction transaction = currentTransaction.get();
        if (transaction == null) throw new IllegalStateException("No active bean creation transaction");
        if (transaction.owner != Thread.currentThread()) {
            throw new IllegalStateException("Bean creation transaction used by a different thread");
        }
        return transaction;
    }

    private String canonical(String id, SourceLocation location) {
        if (declaredTypes.containsKey(id)) return id;
        String canonical = document.aliases().get(id);
        if (canonical != null) return canonical;
        if (location == null) throw new BeanException("No bean named '" + id + "'");
        throw new BeanException(location, "Unknown bean reference '" + id + "'");
    }

    private String optionalCanonical(String id) {
        if (declaredTypes.containsKey(id)) return id;
        return document.aliases().get(id);
    }

    private <T> T cast(String id, Object value, Class<T> type) {
        if (!type.isInstance(value)) {
            throw new BeanException("Bean '" + id + "' is " + value.getClass().getTypeName()
                    + ", not " + type.getTypeName());
        }
        return type.cast(value);
    }

    private <T> List<String> matchingNames(Class<T> type) {
        return matchingNames((Type) type);
    }

    private List<String> matchingNames(Type type) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, Type> entry : declaredTypes.entrySet()) {
            if (Types.assignable(entry.getValue(), type)) result.add(entry.getKey());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private <T> T castGeneric(Object value) {
        return (T) value;
    }

    private void ensureOpenLocked() {
        if (contextState != ContextState.OPEN) throw new IllegalStateException("BeanContext is closed");
    }

    private void clearThreadCreationState() {
        Deque<String> path = creationPath.get();
        Deque<Ownership> owners = ownershipPath.get();
        path.clear();
        owners.clear();
        creationPath.remove();
        ownershipPath.remove();
    }

    private void emit(BeanEvent event) {
        List<BeanContextListener> current = listeners;
        if (current == null || current.isEmpty()) return;
        for (BeanContextListener listener : current) {
            try {
                listener.onEvent(event);
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable failure) {
                throw new BeanException(event.location(), "BeanContextListener failed for " + event.kind()
                        + (event.beanId() == null ? "" : " on bean '" + event.beanId() + "'"), failure);
            }
        }
    }

    private void emitFailure(BeanEvent event, Throwable primary) {
        try {
            emit(event);
        } catch (Throwable listenerFailure) {
            if (listenerFailure != primary) primary.addSuppressed(listenerFailure);
        }
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof InvocationTargetException
                || current instanceof java.util.concurrent.CompletionException
                || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static RuntimeException propagate(Throwable error) {
        Throwable root = unwrap(error);
        if (root instanceof RuntimeException runtime) return runtime;
        if (root instanceof Error fatal) throw fatal;
        return new BeanException("Bean operation failed: " + root, root);
    }

    private static final class DetachedBeanHandle<T> implements BeanHandle<T> {
        private T value;
        private List<LifecycleEntry> entries;
        private List<BeanLifecycleInterceptor> interceptors;
        private boolean closed;

        private DetachedBeanHandle(T value, List<LifecycleEntry> entries,
                                   List<BeanLifecycleInterceptor> interceptors) {
            this.value = value;
            this.entries = new ArrayList<>(entries);
            this.interceptors = new ArrayList<>(interceptors);
        }

        @Override
        public synchronized T value() {
            if (closed) throw new IllegalStateException("BeanHandle is closed");
            return value;
        }

        @Override
        public synchronized boolean isClosed() {
            return closed;
        }

        @Override
        public void close() {
            List<LifecycleEntry> current;
            List<BeanLifecycleInterceptor> currentInterceptors;
            synchronized (this) {
                if (closed) return;
                closed = true;
                current = entries;
                currentInterceptors = interceptors;
                entries = List.of();
                interceptors = List.of();
                value = null;
            }
            BeanException failure = destroyDetached(current, currentInterceptors);
            current.clear();
            currentInterceptors.clear();
            if (failure != null) throw failure;
        }

        private static BeanException destroyDetached(List<LifecycleEntry> entries,
                                                     List<BeanLifecycleInterceptor> interceptors) {
            BeanException failure = null;
            for (int i = entries.size() - 1; i >= 0; i--) {
                LifecycleEntry entry = entries.get(i);
                try {
                    destroyDetachedEntry(entry, interceptors);
                } catch (Throwable error) {
                    Throwable root = unwrap(error);
                    if (failure == null) {
                        failure = new BeanException(entry.location(),
                                "One or more bean destruction operations failed during prototype handle close");
                    }
                    failure.addSuppressed(root);
                }
            }
            return failure;
        }

        private static void destroyDetachedEntry(LifecycleEntry entry,
                                                 List<BeanLifecycleInterceptor> interceptors) throws Throwable {
            Throwable interceptorFailure = null;
            for (int i = interceptors.size() - 1; i >= 0; i--) {
                BeanLifecycleInterceptor interceptor = interceptors.get(i);
                try {
                    interceptor.beforeDestruction(entry.lifecycleContext(), entry.instance());
                } catch (VirtualMachineError fatal) {
                    throw fatal;
                } catch (Throwable error) {
                    if (interceptorFailure == null) interceptorFailure = error;
                    else interceptorFailure.addSuppressed(error);
                }
            }
            Method explicit = entry.destroyMethod();
            boolean autoClose = entry.instance() instanceof AutoCloseable
                    && !(explicit != null && explicit.getName().equals("close") && explicit.getParameterCount() == 0);
            Throwable failure = null;
            switch (entry.autoClosePolicy()) {
                case FALLBACK -> {
                    if (explicit != null) invokeDetached(entry, explicit);
                    else if (autoClose) closeDetached(entry);
                }
                case BEFORE -> {
                    if (autoClose) {
                        try { closeDetached(entry); } catch (Throwable error) { failure = error; }
                    }
                    if (explicit != null) {
                        try { invokeDetached(entry, explicit); } catch (Throwable error) {
                            if (failure == null) failure = error;
                            else failure.addSuppressed(error);
                        }
                    }
                }
                case AFTER -> {
                    if (explicit != null) {
                        try { invokeDetached(entry, explicit); } catch (Throwable error) { failure = error; }
                    }
                    if (autoClose) {
                        try { closeDetached(entry); } catch (Throwable error) {
                            if (failure == null) failure = error;
                            else failure.addSuppressed(error);
                        }
                    }
                }
                case NEVER -> {
                    if (explicit != null) invokeDetached(entry, explicit);
                }
            }
            if (interceptorFailure != null) {
                if (failure == null) failure = interceptorFailure;
                else failure.addSuppressed(interceptorFailure);
            }
            for (BeanLifecycleInterceptor interceptor : interceptors) {
                try {
                    interceptor.afterDestruction(entry.lifecycleContext(), entry.instance(), failure);
                } catch (VirtualMachineError fatal) {
                    throw fatal;
                } catch (Throwable error) {
                    if (failure == null) failure = error;
                    else failure.addSuppressed(error);
                }
            }
            if (failure != null) throw failure;
        }

        private static void invokeDetached(LifecycleEntry entry, Method method) throws Throwable {
            try {
                method.invoke(entry.instance());
            } catch (InvocationTargetException error) {
                throw error.getCause() == null ? error : error.getCause();
            } catch (ReflectiveOperationException error) {
                throw new BeanException(entry.location(), "Cannot invoke destroy method "
                        + method.getDeclaringClass().getTypeName() + "." + method.getName() + "()", error);
            }
        }

        private static void closeDetached(LifecycleEntry entry) throws Throwable {
            try {
                ((AutoCloseable) entry.instance()).close();
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable failure) {
                throw new BeanException(entry.location(), "AutoCloseable.close() failed for bean '"
                        + entry.label() + "' (" + entry.instance().getClass().getTypeName() + ")", failure);
            }
        }
    }

}
