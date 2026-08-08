package io.github.simpledi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/** Standard dependency-free scope implementations. */
public final class BeanScopes {
    private BeanScopes() {}

    /** A keyed scope whose current key is supplied by the host, suitable for request/job/session scopes. */
    public static Keyed keyed(Supplier<?> currentKey) {
        return new KeyedScope(currentKey);
    }

    /** A scope keyed by the current thread. Call {@link Keyed#releaseCurrent()} when thread work ends. */
    public static Keyed threadLocal() {
        return keyed(Thread::currentThread);
    }

    /** A keyed scope can explicitly release one logical scope without closing the whole DI context. */
    public interface Keyed extends BeanScope {
        void release(Object key);

        void releaseCurrent();

        int activeKeys();
    }

    private static final class Slot {
        private final String beanId;
        private final Thread creator;
        private final CompletableFuture<BeanHandle<Object>> future = new CompletableFuture<>();
        private BeanHandle<Object> handle;
        private long publicationOrder;

        private Slot(String beanId) {
            this.beanId = beanId;
            this.creator = Thread.currentThread();
        }
    }

    private static final class KeyedScope implements Keyed {
        private final Supplier<?> currentKey;
        private final Object monitor = new Object();
        private final LinkedHashMap<Object, LinkedHashMap<String, Slot>> values = new LinkedHashMap<>();
        private final IdentityHashMap<Thread, Slot> waitingOn = new IdentityHashMap<>();
        private long nextPublicationOrder;
        private boolean closed;

        private KeyedScope(Supplier<?> currentKey) {
            this.currentKey = Objects.requireNonNull(currentKey, "currentKey");
        }

        @Override
        public Reservation reserve(String beanId) {
            Objects.requireNonNull(beanId, "beanId");
            Object key = requireKey();
            Slot slot;
            boolean creator;
            synchronized (monitor) {
                ensureOpen();
                LinkedHashMap<String, Slot> beans = values.computeIfAbsent(key, ignored -> new LinkedHashMap<>());
                slot = beans.get(beanId);
                creator = slot == null;
                if (creator) {
                    slot = new Slot(beanId);
                    beans.put(beanId, slot);
                }
            }
            return new KeyedReservation(this, key, beanId, slot, creator);
        }

        @Override
        public void release(Object key) {
            Objects.requireNonNull(key, "key");
            List<Slot> slots;
            synchronized (monitor) {
                LinkedHashMap<String, Slot> removed = values.remove(key);
                if (removed == null) return;
                slots = new ArrayList<>(removed.values());
                slots.sort(java.util.Comparator.comparingLong(value -> value.publicationOrder));
            }
            BeanException failure = null;
            for (int i = slots.size() - 1; i >= 0; i--) {
                Slot slot = slots.get(i);
                BeanHandle<Object> handle = slot.handle;
                if (handle == null) {
                    slot.future.completeExceptionally(new IllegalStateException("Scope released before publication"));
                    continue;
                }
                try {
                    handle.close();
                } catch (Throwable error) {
                    if (failure == null) failure = new BeanException("One or more bean handles failed during scope release");
                    failure.addSuppressed(error);
                }
            }
            if (failure != null) throw failure;
        }

        @Override
        public void releaseCurrent() {
            release(requireKey());
        }

        @Override
        public int activeKeys() {
            synchronized (monitor) {
                return values.size();
            }
        }

        @Override
        public void close() {
            List<Object> keys;
            synchronized (monitor) {
                if (closed) return;
                closed = true;
                keys = new ArrayList<>(values.keySet());
            }
            BeanException failure = null;
            for (int i = keys.size() - 1; i >= 0; i--) {
                try {
                    release(keys.get(i));
                } catch (Throwable error) {
                    if (failure == null) failure = new BeanException("One or more scoped beans failed during scope shutdown");
                    failure.addSuppressed(error);
                }
            }
            if (failure != null) throw failure;
        }

        private Object requireKey() {
            Object key = currentKey.get();
            if (key == null) throw new IllegalStateException("No active scope key");
            return key;
        }

        private void ensureOpen() {
            if (closed) throw new IllegalStateException("BeanScope is closed");
        }

        private BeanHandle<Object> await(String beanId, Slot slot) {
            if (slot.future.isDone()) return completed(beanId, slot);
            Thread current = Thread.currentThread();
            synchronized (monitor) {
                if (slot.future.isDone()) return completed(beanId, slot);
                waitingOn.put(current, slot);
                List<String> cycle = waitCycle(current, slot);
                if (cycle != null) {
                    waitingOn.remove(current);
                    throw new BeanException("Cross-thread custom-scope dependency cycle: "
                            + String.join(" -> ", cycle));
                }
            }
            try {
                return slot.future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BeanException("Interrupted while awaiting scoped bean '" + beanId + "'", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                throw new BeanException("Scoped bean creation failed for '" + beanId + "'", cause);
            } finally {
                synchronized (monitor) {
                    waitingOn.remove(current);
                }
            }
        }

        private static BeanHandle<Object> completed(String beanId, Slot slot) {
            try {
                return slot.future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BeanException("Interrupted while awaiting scoped bean '" + beanId + "'", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                throw new BeanException("Scoped bean creation failed for '" + beanId + "'", cause);
            }
        }

        private List<String> waitCycle(Thread start, Slot first) {
            List<String> beanIds = new ArrayList<>();
            Slot slot = first;
            Set<Thread> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            while (slot != null) {
                beanIds.add(slot.beanId);
                Thread creator = slot.creator;
                if (creator == start) {
                    beanIds.add(first.beanId);
                    return List.copyOf(beanIds);
                }
                if (!visited.add(creator)) return null;
                slot = waitingOn.get(creator);
            }
            return null;
        }

        private void publish(Object key, String beanId, Slot slot, BeanHandle<Object> handle) {
            Objects.requireNonNull(handle, "handle");
            boolean rejected;
            synchronized (monitor) {
                LinkedHashMap<String, Slot> beans = values.get(key);
                rejected = closed || beans == null || beans.get(beanId) != slot;
                if (!rejected) {
                    if (slot.handle != null) throw new IllegalStateException("Scoped bean already published: " + beanId);
                    slot.handle = handle;
                    slot.publicationOrder = ++nextPublicationOrder;
                }
            }
            if (rejected) {
                handle.close();
                throw new IllegalStateException("Scope ended before bean '" + beanId + "' was published");
            }
            slot.future.complete(handle);
        }

        private void cancel(Object key, String beanId, Slot slot, Throwable failure) {
            synchronized (monitor) {
                LinkedHashMap<String, Slot> beans = values.get(key);
                if (beans != null && beans.get(beanId) == slot) {
                    beans.remove(beanId);
                    if (beans.isEmpty()) values.remove(key);
                }
            }
            slot.future.completeExceptionally(failure == null
                    ? new IllegalStateException("Scoped bean creation cancelled") : failure);
        }
    }

    private record KeyedReservation(
            KeyedScope owner,
            Object key,
            String beanId,
            Slot slot,
            boolean creator) implements BeanScope.Reservation {
        @Override
        public BeanHandle<Object> await() {
            if (creator) throw new IllegalStateException("Creator reservation cannot await itself");
            return owner.await(beanId, slot);
        }

        @Override
        public void publish(BeanHandle<Object> handle) {
            if (!creator) throw new IllegalStateException("Only creator reservation can publish");
            owner.publish(key, beanId, slot, handle);
        }

        @Override
        public void cancel(Throwable failure) {
            if (!creator) throw new IllegalStateException("Only creator reservation can cancel");
            owner.cancel(key, beanId, slot, failure);
        }
    }
}
