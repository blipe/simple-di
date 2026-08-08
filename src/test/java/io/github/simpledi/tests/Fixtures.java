package io.github.simpledi.tests;

import io.github.simpledi.BeanContext;

import java.time.Clock;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Properties;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class Fixtures {
    public static final List<String> EVENTS = Collections.synchronizedList(new ArrayList<>());

    private Fixtures() {}

    public static void reset() {
        EVENTS.clear();
    }

    public enum Mode { FAST, SAFE }

    public record Repo(String name) {}

    public record Config(String host, int port) {}

    public interface Listener {
        String label();
    }

    public static final class AuditListener implements Listener, AutoCloseable {
        private String prefix;

        public AuditListener() {
            EVENTS.add("audit.construct");
        }

        public void setPrefix(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public String label() {
            return prefix + "-audit";
        }

        @Override
        public void close() {
            EVENTS.add("audit.close");
        }
    }

    public static final class Service {
        private final Repo repo;
        private final Clock clock;
        private int batchSize;
        private List<String> names;
        private Set<Mode> modes;
        private Map<String, Integer> weights;
        private int[] codes;
        private Optional<String> maybe;
        private Listener listener;

        public Service(Repo repo, Clock clock) {
            this.repo = repo;
            this.clock = clock;
        }

        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
        public void setNames(List<String> names) { this.names = names; }
        public void setModes(Set<Mode> modes) { this.modes = modes; }
        public void setWeights(Map<String, Integer> weights) { this.weights = weights; }
        public void setCodes(int[] codes) { this.codes = codes; }
        public void setMaybe(Optional<String> maybe) { this.maybe = maybe; }
        public void setListener(Listener listener) { this.listener = listener; }

        public Repo repo() { return repo; }
        public Clock clock() { return clock; }
        public int batchSize() { return batchSize; }
        public List<String> names() { return names; }
        public Set<Mode> modes() { return modes; }
        public Map<String, Integer> weights() { return weights; }
        public int[] codes() { return codes; }
        public Optional<String> maybe() { return maybe; }
        public Listener listener() { return listener; }
    }

    public record Product(String name, int count) {}

    public static final class ProductFactory {
        private ProductFactory() {}

        public static Product create(String name, int count) {
            return new Product(name, count);
        }
    }

    public static final class Ambiguous {
        private final String selected;

        public Ambiguous(int value) { selected = "int:" + value; }
        public Ambiguous(long value) { selected = "long:" + value; }

        public String selected() { return selected; }
    }

    public static final class Dependency implements AutoCloseable {
        public Dependency() { EVENTS.add("dependency.construct"); }
        @Override public void close() { EVENTS.add("dependency.close"); }
    }

    public static final class Owner {
        private final Dependency dependency;
        public Owner(Dependency dependency) {
            this.dependency = dependency;
            EVENTS.add("owner.construct");
        }
        public void start() { EVENTS.add("owner.start"); }
        public void stop() { EVENTS.add("owner.stop"); }
        public Dependency dependency() { return dependency; }
    }

    public static final class Failing implements AutoCloseable {
        public Failing(Dependency dependency) { EVENTS.add("failing.construct"); }
        public void start() {
            EVENTS.add("failing.start");
            throw new IllegalStateException("boom");
        }
        @Override public void close() { EVENTS.add("failing.close"); }
    }

    public static final class A {
        public A(B b) {}
    }

    public static final class B {
        public B(A a) {}
    }

    public record Endpoint(String host, int port) {}

    public record EndpointConsumer(Endpoint endpoint) {}


    public record StringHolder(String value) {}

    public static final class InstanceProductFactory implements AutoCloseable {
        public InstanceProductFactory() { EVENTS.add("factory.construct"); }
        public Product create(String name, int count) { return new Product(name, count); }
        @Override public void close() { EVENTS.add("factory.close"); }
    }

    public static final class MethodConfigured {
        private final List<String> values = new ArrayList<>();
        public void add(String... values) { this.values.addAll(List.of(values)); }
        public List<String> values() { return List.copyOf(values); }
    }

    public static final class VarargsTarget {
        private final String prefix;
        private final int[] values;
        public VarargsTarget(String prefix, int... values) {
            this.prefix = prefix;
            this.values = values.clone();
        }
        public String prefix() { return prefix; }
        public int[] values() { return values.clone(); }
    }

    public static final class LazyProbe implements AutoCloseable {
        public static final AtomicInteger CONSTRUCTED = new AtomicInteger();
        public LazyProbe() { CONSTRUCTED.incrementAndGet(); EVENTS.add("lazy.construct"); }
        @Override public void close() { EVENTS.add("lazy.close"); }
    }

    public static final class SupplierA {
        private final Supplier<SupplierB> b;
        public SupplierA(Supplier<SupplierB> b) { this.b = b; EVENTS.add("supplierA.construct"); }
        public SupplierB b() { return b.get(); }
    }

    public static final class SupplierB {
        private final SupplierA a;
        public SupplierB(SupplierA a) { this.a = a; EVENTS.add("supplierB.construct"); }
        public SupplierA a() { return a; }
    }

    public record OptionalConsumer(Optional<Repo> repo) {}
    public record GenericSupplierConsumer(Supplier<Repo> repo) {}

    public static final class EventBean implements AutoCloseable {
        private final String name;
        public EventBean(String name) { this.name = name; EVENTS.add(name + ".construct"); }
        @Override public void close() { EVENTS.add(name + ".close"); }
    }

    public record PropertiesConsumer(Properties properties) {}
    public record ConstantConsumer(java.nio.charset.Charset charset) {}

    public static final class CollectionTargets {
        private HashSet<String> hashSet;
        private HashMap<String, Integer> hashMap;
        private ArrayDeque<String> deque;
        private SortedSet<String> sortedSet;
        private NavigableSet<String> navigableSet;
        private SortedMap<String, Integer> sortedMap;
        private NavigableMap<String, Integer> navigableMap;
        public void setHashSet(HashSet<String> value) { hashSet = value; }
        public void setHashMap(HashMap<String, Integer> value) { hashMap = value; }
        public void setDeque(ArrayDeque<String> value) { deque = value; }
        public void setSortedSet(SortedSet<String> value) { sortedSet = value; }
        public void setNavigableSet(NavigableSet<String> value) { navigableSet = value; }
        public void setSortedMap(SortedMap<String, Integer> value) { sortedMap = value; }
        public void setNavigableMap(NavigableMap<String, Integer> value) { navigableMap = value; }
        public HashSet<String> hashSet() { return hashSet; }
        public HashMap<String, Integer> hashMap() { return hashMap; }
        public ArrayDeque<String> deque() { return deque; }
        public SortedSet<String> sortedSet() { return sortedSet; }
        public NavigableSet<String> navigableSet() { return navigableSet; }
        public SortedMap<String, Integer> sortedMap() { return sortedMap; }
        public NavigableMap<String, Integer> navigableMap() { return navigableMap; }
    }

    public static final class ImmutableTargets {
        private List<String> list;
        private Set<String> set;
        private SortedSet<String> sortedSet;
        private Map<String, Integer> map;
        private SortedMap<String, Integer> sortedMap;
        private Properties properties;
        public void setList(List<String> v) { list = v; }
        public void setSet(Set<String> v) { set = v; }
        public void setSortedSet(SortedSet<String> v) { sortedSet = v; }
        public void setMap(Map<String, Integer> v) { map = v; }
        public void setSortedMap(SortedMap<String, Integer> v) { sortedMap = v; }
        public void setProperties(Properties v) { properties = v; }
        public List<String> list() { return list; }
        public Set<String> set() { return set; }
        public SortedSet<String> sortedSet() { return sortedSet; }
        public Map<String, Integer> map() { return map; }
        public SortedMap<String, Integer> sortedMap() { return sortedMap; }
        public Properties properties() { return properties; }
    }

    public static final class PreflightProbe {
        public static final AtomicInteger CONSTRUCTED = new AtomicInteger();
        public PreflightProbe() { CONSTRUCTED.incrementAndGet(); }
    }

    public static final class RetryDependency implements AutoCloseable {
        public RetryDependency() { EVENTS.add("retry.dep.construct"); }
        @Override public void close() { EVENTS.add("retry.dep.close"); }
    }

    public static final class FlakyLazy implements AutoCloseable {
        public static final AtomicInteger ATTEMPTS = new AtomicInteger();
        public FlakyLazy(RetryDependency dependency) {
            EVENTS.add("flaky.attempt");
            if (ATTEMPTS.getAndIncrement() == 0) throw new IllegalStateException("first attempt fails");
            EVENTS.add("flaky.construct");
        }
        @Override public void close() { EVENTS.add("flaky.close"); }
    }

    public static final class SharedSingleton implements AutoCloseable {
        public static final SharedSingleton INSTANCE = new SharedSingleton();
        private SharedSingleton() {}
        @Override public void close() { EVENTS.add("shared.close"); }
    }

    public static final class SharedFactory {
        public static SharedSingleton get() { return SharedSingleton.INSTANCE; }
    }

    public static final class FailingClose implements AutoCloseable {
        private final String name;
        public FailingClose(String name) { this.name = name; }
        @Override public void close() { EVENTS.add(name + ".close"); throw new IllegalStateException(name); }
    }

    public static final class ExplicitDestroy implements AutoCloseable {
        public void stop() { EVENTS.add("explicit.stop"); }
        @Override public void close() { EVENTS.add("explicit.close"); }
    }


    public static final class LifecycleProbe implements AutoCloseable {
        private final String name;
        public LifecycleProbe(String name) { this.name = name; EVENTS.add(name + ".construct"); }
        public void stop() { EVENTS.add(name + ".stop"); }
        @Override public void close() { EVENTS.add(name + ".close"); }
    }

    public static final class DualFailLifecycle implements AutoCloseable {
        public DualFailLifecycle() { EVENTS.add("dual.construct"); }
        public void stop() { EVENTS.add("dual.stop"); throw new IllegalStateException("stop failed"); }
        @Override public void close() { EVENTS.add("dual.close"); throw new IllegalStateException("close failed"); }
    }

    public interface Marker {}
    public static final class MarkerOne implements Marker {}
    public static final class MarkerTwo implements Marker {}

    public static final class ConcurrentSingleton {
        public static final AtomicInteger CONSTRUCTED = new AtomicInteger();
        public ConcurrentSingleton() { CONSTRUCTED.incrementAndGet(); }
    }

    public static final class PrototypeProbe implements AutoCloseable {
        public static final AtomicInteger NEXT_ID = new AtomicInteger();
        private final int id = NEXT_ID.incrementAndGet();
        public PrototypeProbe() { EVENTS.add("prototype." + id + ".construct"); }
        public int id() { return id; }
        @Override public void close() { EVENTS.add("prototype." + id + ".close"); }
    }

    public record PrototypePair(PrototypeProbe first, PrototypeProbe second) {}
    public record PrototypeSupplierConsumer(Supplier<PrototypeProbe> supplier) {}


    public static final class IntProbe {
        public static final AtomicInteger CONSTRUCTED = new AtomicInteger();
        private final int value;
        public IntProbe(int value) { CONSTRUCTED.incrementAndGet(); this.value = value; }
        public int value() { return value; }
    }

    public static final class ContextBridge {
        private volatile BeanContext context;

        public void context(BeanContext context) { this.context = context; }
        public BeanContext context() {
            BeanContext current = context;
            if (current == null) throw new IllegalStateException("context not installed");
            return current;
        }
    }

    public static final class ConstructorWorkerLookup {
        public ConstructorWorkerLookup(ContextBridge bridge) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    bridge.context().require("workerDependency");
                } catch (Throwable error) {
                    failure.set(error);
                }
            }, "simple-di-constructor-worker");
            worker.setDaemon(true);
            worker.start();
            try {
                worker.join(TimeUnit.SECONDS.toMillis(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            }
            if (worker.isAlive()) throw new IllegalStateException("worker lookup deadlocked");
            if (failure.get() != null) throw new IllegalStateException("worker lookup failed", failure.get());
            EVENTS.add("worker.lookup.complete");
        }
    }

    public static final class CrossThreadBridge {
        private volatile BeanContext context;
        private final CyclicBarrier barrier = new CyclicBarrier(2);

        public void context(BeanContext context) { this.context = context; }
        public void awaitAndRequire(String id) {
            try {
                barrier.await(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("barrier failed", e);
            }
            BeanContext current = context;
            if (current == null) throw new IllegalStateException("context not installed");
            current.require(id);
        }
    }

    public static final class CrossThreadLookup {
        public CrossThreadLookup(CrossThreadBridge bridge, String other) {
            bridge.awaitAndRequire(other);
        }
    }


    public static final class ReentrantLookup {
        public ReentrantLookup(ContextBridge bridge, String id) {
            bridge.context().require(id);
        }
    }

    public static final class SlowBean implements AutoCloseable {
        public static volatile CountDownLatch STARTED = new CountDownLatch(1);
        public static volatile CountDownLatch PROCEED = new CountDownLatch(1);

        public SlowBean() {
            STARTED.countDown();
            try {
                if (!PROCEED.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("slow bean timeout");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            }
            EVENTS.add("slow.construct");
        }

        public static void reset() {
            STARTED = new CountDownLatch(1);
            PROCEED = new CountDownLatch(1);
        }

        @Override public void close() { EVENTS.add("slow.close"); }
    }


    public interface Box<T> {
        T value();
    }

    public static final class StringBox implements Box<String> {
        private final String value;
        public StringBox(String value) { this.value = value; }
        @Override public String value() { return value; }
    }

    public static final class IntegerBox implements Box<Integer> {
        private final Integer value;
        public IntegerBox(Integer value) { this.value = value; }
        @Override public Integer value() { return value; }
    }

    public record StringBoxConsumer(Box<String> box) {}
    public record IntegerBoxConsumer(Box<Integer> box) {}

    public static class GenericSetter<T> {
        private T value;
        public void setValue(T value) { this.value = value; }
        public T value() { return value; }
    }

    public static final class StringSetter extends GenericSetter<String> {}

    public interface GenericFactory<T> {
        T create();
    }

    public static final class ProductGenericFactory implements GenericFactory<Product> {
        @Override public Product create() { return new Product("generic", 11); }
    }

    public record ParentConsumer(Repo repo) {}

}
