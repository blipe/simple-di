package io.github.simpledi.tests;

import io.github.simpledi.BeanContext;
import io.github.simpledi.BeanHandle;
import io.github.simpledi.ContextLease;
import io.github.simpledi.LeaseDiagnostics;
import io.github.simpledi.GenerationClassLoaderOwnership;
import io.github.simpledi.PreparedReload;
import io.github.simpledi.ReloadEvent;
import io.github.simpledi.ReloadPolicy;
import io.github.simpledi.ReloadResult;
import io.github.simpledi.ReloadableBeanContext;
import io.github.simpledi.RetiredGeneration;
import io.github.simpledi.XmlBeans;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLClassLoader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

public final class ReloadableTest {
    private static int passed;

    private ReloadableTest() {}

    public static void main(String[] args) throws Exception {
        run("atomic generation publication", ReloadableTest::atomicGenerationPublication);
        run("validation failure preserves active graph", ReloadableTest::validationFailurePreservesGraph);
        run("startup failure preserves active graph", ReloadableTest::startupFailurePreservesGraph);
        run("unchanged revision skips reconstruction", ReloadableTest::unchangedSkipsReconstruction);
        run("sensitive value changes revision without disclosure", ReloadableTest::sensitiveRevision);
        run("stale prepared candidate rejected", ReloadableTest::staleCandidate);
        run("busy policy rejects activation", ReloadableTest::busyPolicy);
        run("graceful retirement drains final lease", ReloadableTest::gracefulDrain);
        run("immediate retirement invalidates old lease", ReloadableTest::immediateRetirement);
        run("handoff failure rolls candidate back", ReloadableTest::handoffFailure);
        run("closing prepared candidate disposes graph", ReloadableTest::preparedClose);
        run("retirement failure cannot undo publication", ReloadableTest::retirementFailure);
        run("retired plugin classloader is collectible", ReloadableTest::reloadClassLoaderReleased);
        run("owned classloader cannot be shared across changed generations", ReloadableTest::sharedOwnedLoaderRejected);
        run("owned classloader must be closeable", ReloadableTest::ownedLoaderMustBeCloseable);
        run("retained reload diagnostics release plugin classloader", ReloadableTest::retainedDiagnosticsReleaseLoader);
        run("lease owns caller prototype handles", ReloadableTest::leaseOwnsPrototypeHandles);
        run("prototype cleanup failure still releases generation", ReloadableTest::prototypeCleanupFailureReleasesGeneration);
        run("lease close is idempotent", ReloadableTest::leaseCloseIdempotent);
        run("lease diagnostics survive forced shutdown", ReloadableTest::leaseDiagnostics);
        run("reload events are ordered", ReloadableTest::reloadEvents);
        run("prepublication listener failure preserves graph", ReloadableTest::listenerFailure);
        run("concurrent acquisitions never observe a mixed graph", ReloadableTest::concurrentAtomicity);
        run("manager close waits for concurrent generation destruction", ReloadableTest::managerCloseWaitsForDestruction);
        run("manager close rejects new work", ReloadableTest::managerClose);
        System.out.println("PASS: " + passed + " reload tests");
    }

    private static void atomicGenerationPublication() throws Exception {
        reset();
        Path one = graph("one", false);
        Path two = graph("two", false);
        try (ReloadableBeanContext manager = XmlBeans.reloadable().load(one)) {
            equal(1L, manager.generation());
            ContextLease old = manager.acquire();
            equal("one", old.require("service", Versioned.class).value());
            ReloadResult result = manager.reload(two);
            equal(ReloadResult.Status.ACTIVATED, result.status());
            equal(2L, manager.generation());
            try (ContextLease current = manager.acquire()) {
                equal("two", current.require("service", Versioned.class).value());
                equal(2L, current.generation());
            }
            equal("one", old.require("service", Versioned.class).value());
            old.close();
        }
    }

    private static void validationFailurePreservesGraph() throws Exception {
        reset();
        Path valid = graph("stable", false);
        Path invalid = xml("<beans version=\"2\"><bean id=\"broken\"></beans>");
        try (ReloadableBeanContext manager = XmlBeans.reloadable().load(valid)) {
            ReloadResult result = manager.reload(invalid);
            equal(ReloadResult.Status.VALIDATION_FAILED, result.status());
            equal(1L, manager.generation());
            try (ContextLease lease = manager.acquire()) {
                equal("stable", lease.require("service", Versioned.class).value());
            }
        }
    }

    private static void startupFailurePreservesGraph() throws Exception {
        reset();
        Path valid = graph("stable", false);
        Path failing = graph("candidate", true);
        try (ReloadableBeanContext manager = XmlBeans.reloadable().load(valid)) {
            ReloadResult result = manager.reload(failing);
            equal(ReloadResult.Status.STARTUP_FAILED, result.status());
            equal(1L, manager.generation());
            try (ContextLease lease = manager.acquire()) {
                equal("stable", lease.require("service", Versioned.class).value());
            }
            check(EVENTS.contains("candidate.close"), "failed candidate rolled back");
            check(!EVENTS.contains("stable.close"), "active graph remains open");
        }
    }

    private static void unchangedSkipsReconstruction() throws Exception {
        reset();
        Path valid = graph("same", false);
        try (ReloadableBeanContext manager = XmlBeans.reloadable().load(valid)) {
            equal(1, CONSTRUCTIONS.get());
            ReloadResult result = manager.reload(valid);
            equal(ReloadResult.Status.UNCHANGED, result.status());
            equal(1, CONSTRUCTIONS.get());
            equal(1L, manager.generation());
        }
    }

    private static void sensitiveRevision() throws Exception {
        reset();
        AtomicReference<String> secret = new AtomicReference<>("alpha-secret");
        Path source = xml("""
                <beans version="2"><bean id="service" class="%s">
                  <constructor><arg value="${token}"/><arg value="false"/></constructor>
                  <init method="start"/>
                </bean></beans>
                """.formatted(Versioned.class.getName()));
        try (ReloadableBeanContext manager = XmlBeans.reloadable()
                .builderFactory(() -> XmlBeans.builder().withoutDefaultPropertySources()
                        .propertySource(name -> "token".equals(name)
                                ? java.util.Optional.of(io.github.simpledi.PropertyValue.secret(secret.get(), "test"))
                                : java.util.Optional.empty()))
                .load(source)) {
            String before = manager.revision().sha256();
            secret.set("beta-secret");
            ReloadResult result = manager.reload(source);
            equal(ReloadResult.Status.ACTIVATED, result.status());
            check(!before.equals(manager.revision().sha256()), "secret change updates revision");
            String rendered = result.toString() + manager.revision();
            check(!rendered.contains("alpha-secret") && !rendered.contains("beta-secret"), "secret not disclosed");
        }
    }

    private static void staleCandidate() throws Exception {
        reset();
        Path one = graph("one", false);
        Path two = graph("two", false);
        Path three = graph("three", false);
        try (ReloadableBeanContext manager = XmlBeans.reloadable().load(one)) {
            PreparedReload prepared = manager.prepare(two);
            equal(ReloadResult.Status.ACTIVATED, manager.reload(three).status());
            ReloadResult stale = prepared.activate();
            equal(ReloadResult.Status.STALE_CANDIDATE, stale.status());
            prepared.close();
            try (ContextLease lease = manager.acquire()) {
                equal("three", lease.require("service", Versioned.class).value());
            }
            check(EVENTS.contains("two.close"), "stale candidate closed");
        }
    }

    private static void busyPolicy() throws Exception {
        reset();
        Path one = graph("one", false);
        Path two = graph("two", false);
        try (ReloadableBeanContext manager = XmlBeans.reloadable()
                .reloadPolicy(ReloadPolicy.REJECT_WHILE_BUSY).load(one)) {
            ContextLease lease = manager.acquire();
            ReloadResult result = manager.reload(two);
            equal(ReloadResult.Status.BUSY, result.status());
            equal(1L, manager.generation());
            check(EVENTS.contains("two.close"), "rejected candidate closed");
            lease.close();
        }
    }

    private static void gracefulDrain() throws Exception {
        reset();
        Path one = graph("one", false);
        Path two = graph("two", false);
        try (ReloadableBeanContext manager = XmlBeans.reloadable()
                .reloadPolicy(ReloadPolicy.GRACEFUL).load(one)) {
            ContextLease old = manager.acquire();
            equal(ReloadResult.Status.ACTIVATED, manager.reload(two).status());
            check(!EVENTS.contains("one.close"), "old graph retained while leased");
            List<RetiredGeneration> retired = manager.retiredGenerations();
            equal(1, retired.get(0).activeLeases());
            old.close();
            check(EVENTS.contains("one.close"), "old graph closes after final lease");
            equal("closed", manager.retiredGenerations().get(0).state());
        }
    }

    private static void immediateRetirement() throws Exception {
        reset();
        Path one = graph("one", false);
        Path two = graph("two", false);
        try (ReloadableBeanContext manager = XmlBeans.reloadable()
                .reloadPolicy(ReloadPolicy.IMMEDIATE).load(one)) {
            ContextLease old = manager.acquire();
            equal(ReloadResult.Status.ACTIVATED, manager.reload(two).status());
            expect(IllegalStateException.class, () -> old.require("service"));
            old.close();
        }
    }

    private static void handoffFailure() throws Exception {
        reset();
        Path one = graph("one", false);
        Path two = graph("two", false);
        try (ReloadableBeanContext manager = XmlBeans.reloadable()
                .handoff((previous, candidate, diff) -> { throw new IllegalStateException("handoff boom"); })
                .load(one)) {
            ReloadResult result = manager.reload(two);
            equal(ReloadResult.Status.HANDOFF_FAILED, result.status());
            equal(1L, manager.generation());
            check(EVENTS.contains("two.close"), "handoff candidate closed");
            check(!EVENTS.contains("one.close"), "old graph retained");
        }
    }

    private static void preparedClose() throws Exception {
        reset();
        try (ReloadableBeanContext manager = XmlBeans.reloadable().load(graph("one", false))) {
            PreparedReload prepared = manager.prepare(graph("two", false));
            check(EVENTS.contains("two.construct"), "candidate started during prepare");
            prepared.close();
            check(EVENTS.contains("two.close"), "closed preparation disposes candidate");
            equal(1L, manager.generation());
        }
    }

    private static void retirementFailure() throws Exception {
        reset();
        Path old = xml("""
                <beans version="2"><bean id="service" class="%s"/></beans>
                """.formatted(FailingClose.class.getName()));
        Path replacement = graph("replacement", false);
        try (ReloadableBeanContext manager = XmlBeans.reloadable().load(old)) {
            ReloadResult result = manager.reload(replacement);
            equal(ReloadResult.Status.ACTIVATED, result.status());
            check(result.failure() != null, "retirement failure reported");
            equal(2L, manager.generation());
            try (ContextLease lease = manager.acquire()) {
                equal("replacement", lease.require("service", Versioned.class).value());
            }
            check(manager.retiredGenerations().get(0).destructionFailure().contains("retire close failed"),
                    "value-only failure retained");
        }
    }

    private static void reloadClassLoaderReleased() throws Exception {
        LoaderProbe probe = createReloadedPlugin();
        try {
            for (int i = 0; i < 80 && probe.oldLoader().get() != null; i++) {
                System.gc();
                byte[] pressure = new byte[128 * 1024];
                pressure[0] = 1;
                Thread.sleep(15);
            }
            check(probe.oldLoader().get() == null, "retired generation retained plugin classloader");
        } finally {
            probe.manager().close();
            equal(1, probe.currentCloseCount().get());
        }
    }

    private static LoaderProbe createReloadedPlugin() throws Exception {
        Path root = Files.createTempDirectory("simple-di-reload-plugin-");
        Path source = root.resolve("src/reloadplugin/Plugin.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package reloadplugin;
                public final class Plugin implements AutoCloseable {
                    public Plugin() {}
                    public String value() { return "plugin"; }
                    public void close() {}
                }
                """);
        Path classes = root.resolve("classes");
        Files.createDirectories(classes);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        check(compiler != null, "JDK compiler unavailable");
        equal(0, compiler.run(null, null, null, "--release", "21", "-d", classes.toString(), source.toString()));
        Path config = xml("""
                <beans version="2"><bean id="plugin" class="reloadplugin.Plugin"/></beans>
                """);
        URL url = classes.toUri().toURL();
        AtomicInteger oldCloseCount = new AtomicInteger();
        TrackingLoader old = new TrackingLoader(new URL[] {url}, oldCloseCount);
        AtomicReference<ClassLoader> selected = new AtomicReference<>(old);
        ReloadableBeanContext manager = XmlBeans.reloadable()
                .builderFactory(() -> XmlBeans.builder().classLoader(selected.get()))
                .classLoaderOwnership(GenerationClassLoaderOwnership.CLOSE_ON_RETIREMENT)
                .load(config);
        AtomicInteger currentCloseCount = new AtomicInteger();
        TrackingLoader current = new TrackingLoader(new URL[] {url}, currentCloseCount);
        selected.set(current);
        ReloadResult result = manager.reload(config);
        equal(ReloadResult.Status.ACTIVATED, result.status());
        equal(1, oldCloseCount.get());
        WeakReference<ClassLoader> oldReference = new WeakReference<>(old);
        old = null;
        return new LoaderProbe(manager, currentCloseCount, oldReference);
    }

    private static void sharedOwnedLoaderRejected() throws Exception {
        Path root = Files.createTempDirectory("simple-di-shared-loader-");
        Path source = root.resolve("src/reloadplugin/Shared.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package reloadplugin;
                public final class Shared implements AutoCloseable {
                    public Shared() {}
                    public void close() {}
                }
                """);
        Path classes = root.resolve("classes");
        Files.createDirectories(classes);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        check(compiler != null, "JDK compiler unavailable");
        equal(0, compiler.run(null, null, null, "--release", "21", "-d", classes.toString(), source.toString()));
        AtomicInteger closes = new AtomicInteger();
        TrackingLoader loader = new TrackingLoader(new URL[] {classes.toUri().toURL()}, closes);
        Path first = xml("""
                <beans version="2"><bean id="plugin" class="reloadplugin.Shared"/></beans>
                """);
        Path changed = xml("""
                <beans version="2"><bean id="plugin" class="reloadplugin.Shared"/>
                  <alias name="plugin" alias="other"/>
                </beans>
                """);
        ReloadableBeanContext manager = XmlBeans.reloadable()
                .builderFactory(() -> XmlBeans.builder().classLoader(loader))
                .classLoaderOwnership(GenerationClassLoaderOwnership.CLOSE_ON_RETIREMENT)
                .load(first);
        ReloadResult result = manager.reload(changed);
        equal(ReloadResult.Status.STARTUP_FAILED, result.status());
        equal(1L, manager.generation());
        equal(0, closes.get());
        manager.close();
        equal(1, closes.get());
    }

    private static void ownedLoaderMustBeCloseable() throws Exception {
        Path config = graph("one", false);
        ClassLoader loader = new ClassLoader(ReloadableTest.class.getClassLoader()) {};
        IllegalStateException failure = expect(IllegalStateException.class, () -> XmlBeans.reloadable()
                .builderFactory(() -> XmlBeans.builder().classLoader(loader))
                .classLoaderOwnership(GenerationClassLoaderOwnership.CLOSE_ON_RETIREMENT)
                .load(config));
        check(failure.getMessage().contains("AutoCloseable"), "ownership failure explains requirement");
    }

    private static void retainedDiagnosticsReleaseLoader() throws Exception {
        RetainedLoaderProbe probe = createRetainedDiagnosticsProbe();
        try {
            check(probe.result().failure() != null, "retirement failure captured");
            check(probe.events().stream().anyMatch(event -> event.failure() != null), "failure event captured");
            for (int i = 0; i < 80 && probe.oldLoader().get() != null; i++) {
                System.gc();
                byte[] pressure = new byte[128 * 1024];
                pressure[0] = 1;
                Thread.sleep(15);
            }
            check(probe.oldLoader().get() == null,
                    "retained result or event pinned retired application throwable/classloader");
        } finally {
            expect(io.github.simpledi.BeanException.class, probe.manager()::close);
        }
    }

    private static RetainedLoaderProbe createRetainedDiagnosticsProbe() throws Exception {
        Path root = Files.createTempDirectory("simple-di-reload-failure-plugin-");
        Path source = root.resolve("src/reloadplugin/FailingPlugin.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package reloadplugin;
                public final class FailingPlugin implements AutoCloseable {
                    public static final class PluginCloseFailure extends RuntimeException {
                        public PluginCloseFailure() { super("plugin close failed"); }
                    }
                    public FailingPlugin() {}
                    public void close() { throw new PluginCloseFailure(); }
                }
                """);
        Path classes = root.resolve("classes");
        Files.createDirectories(classes);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        check(compiler != null, "JDK compiler unavailable");
        equal(0, compiler.run(null, null, null, "--release", "21", "-d", classes.toString(), source.toString()));
        Path config = xml("""
                <beans version="2"><bean id="plugin" class="reloadplugin.FailingPlugin"/></beans>
                """);
        URL url = classes.toUri().toURL();
        TrackingLoader old = new TrackingLoader(new URL[] {url}, new AtomicInteger());
        AtomicReference<ClassLoader> selected = new AtomicReference<>(old);
        List<ReloadEvent> events = new ArrayList<>();
        ReloadableBeanContext manager = XmlBeans.reloadable()
                .builderFactory(() -> XmlBeans.builder().classLoader(selected.get()))
                .classLoaderOwnership(GenerationClassLoaderOwnership.CLOSE_ON_RETIREMENT)
                .listener(events::add)
                .load(config);
        selected.set(new TrackingLoader(new URL[] {url}, new AtomicInteger()));
        ReloadResult result = manager.reload(config);
        equal(ReloadResult.Status.ACTIVATED, result.status());
        WeakReference<ClassLoader> reference = new WeakReference<>(old);
        old = null;
        return new RetainedLoaderProbe(manager, result, List.copyOf(events), reference);
    }

    private static void prototypeCleanupFailureReleasesGeneration() throws Exception {
        Path first = xml("""
                <beans version="2"><bean id="job" class="%s" scope="prototype"/></beans>
                """.formatted(FailingPrototype.class.getName()));
        try (ReloadableBeanContext manager = XmlBeans.reloadable().load(first)) {
            ContextLease lease = manager.acquire();
            BeanHandle<FailingPrototype> handle = lease.context().create("job", FailingPrototype.class);
            equal(ReloadResult.Status.ACTIVATED, manager.reload(graph("replacement", false)).status());
            expect(io.github.simpledi.BeanException.class, lease::close);
            check(handle.isClosed(), "failing handle is still terminally closed");
            equal("closed", manager.retiredGenerations().get(0).state());
            equal(0, manager.retiredGenerations().get(0).activeLeases());
        }
    }

    private static void leaseOwnsPrototypeHandles() throws Exception {
        reset();
        Path first = xml("""
                <beans version="2"><bean id="job" class="%s" scope="prototype">
                  <constructor><arg value="job"/><arg value="false"/></constructor>
                  <init method="start"/>
                </bean></beans>
                """.formatted(Versioned.class.getName()));
        Path second = graph("replacement", false);
        try (ReloadableBeanContext manager = XmlBeans.reloadable().load(first)) {
            ContextLease lease = manager.acquire();
            BeanHandle<Versioned> handle = lease.context().create("job", Versioned.class);
            equal("job", handle.value().value());
            equal(ReloadResult.Status.ACTIVATED, manager.reload(second).status());
            check(!handle.isClosed(), "handle remains valid while lease is open");
            lease.close();
            check(handle.isClosed(), "lease closes its prototype handles");
            check(EVENTS.contains("job.close"), "prototype destroyed before generation release");
        }
    }

    private static void leaseCloseIdempotent() throws Exception {
        reset();
        try (ReloadableBeanContext manager = XmlBeans.reloadable().load(graph("one", false))) {
            ContextLease lease = manager.acquire();
            lease.close();
            lease.close();
            check(lease.isClosed(), "closed state");
            expect(IllegalStateException.class, lease::context);
        }
    }

    private static void leaseDiagnostics() throws Exception {
        reset();
        ReloadableBeanContext manager = XmlBeans.reloadable()
                .leaseDiagnostics(LeaseDiagnostics.CAPTURE_STACK)
                .shutdownPolicy(ReloadPolicy.GRACEFUL_WITH_TIMEOUT)
                .drainTimeout(Duration.ZERO)
                .load(graph("one", false));
        ContextLease leaked = manager.acquire();
        check(!leaked.acquisitionTrace().isBlank(), "trace captured");
        manager.close();
        check(leaked.isClosed(), "forced shutdown closes leaked lease");
        List<RetiredGeneration> retired = manager.retiredGenerations();
        equal("closed", retired.get(0).state());
        check(!retired.get(0).leaseDiagnostics().isEmpty(), "leak retained as value-only diagnostics");
        leaked.close();
    }

    private static void reloadEvents() throws Exception {
        reset();
        List<ReloadEvent.Kind> kinds = Collections.synchronizedList(new ArrayList<>());
        try (ReloadableBeanContext manager = XmlBeans.reloadable()
                .listener(event -> kinds.add(event.kind())).load(graph("one", false))) {
            kinds.clear();
            equal(ReloadResult.Status.ACTIVATED, manager.reload(graph("two", false)).status());
            check(index(kinds, ReloadEvent.Kind.PREPARE_STARTED) < index(kinds, ReloadEvent.Kind.PREPARE_SUCCEEDED),
                    "prepare order");
            check(index(kinds, ReloadEvent.Kind.CANDIDATE_STARTING) < index(kinds, ReloadEvent.Kind.CANDIDATE_STARTED),
                    "startup order");
            check(index(kinds, ReloadEvent.Kind.HANDOFF_STARTING) < index(kinds, ReloadEvent.Kind.GENERATION_ACTIVATED),
                    "handoff before publication");
        }
    }

    private static void listenerFailure() throws Exception {
        reset();
        AtomicInteger candidates = new AtomicInteger();
        try (ReloadableBeanContext manager = XmlBeans.reloadable()
                .listener(event -> {
                    if (event.kind() == ReloadEvent.Kind.CANDIDATE_STARTED && candidates.incrementAndGet() > 1) {
                        throw new IllegalStateException("observer boom");
                    }
                }).load(graph("one", false))) {
            ReloadResult result = manager.reload(graph("two", false));
            equal(ReloadResult.Status.STARTUP_FAILED, result.status());
            equal(1L, manager.generation());
            check(EVENTS.contains("two.close"), "listener-failed candidate closed");
        }
    }

    private static void concurrentAtomicity() throws Exception {
        reset();
        Path one = pairedGraph("one");
        Path two = pairedGraph("two");
        try (ReloadableBeanContext manager = XmlBeans.reloadable().load(one)) {
            ExecutorService pool = Executors.newFixedThreadPool(9);
            CountDownLatch start = new CountDownLatch(1);
            List<Callable<Void>> readers = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                readers.add(() -> {
                    start.await();
                    for (int n = 0; n < 300; n++) {
                        try (ContextLease lease = manager.acquire()) {
                            PairService service = lease.require("pair", PairService.class);
                            check(service.value().equals(service.dependency().value()), "mixed generation");
                            check(Set.of("one", "two").contains(service.value()), "unexpected value");
                        }
                    }
                    return null;
                });
            }
            List<Future<Void>> futures = new ArrayList<>();
            for (Callable<Void> reader : readers) futures.add(pool.submit(reader));
            Future<?> writer = pool.submit(() -> {
                try {
                    start.await();
                    for (int n = 0; n < 40; n++) manager.reload((n & 1) == 0 ? two : one);
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            });
            start.countDown();
            for (Future<Void> future : futures) future.get(20, TimeUnit.SECONDS);
            writer.get(20, TimeUnit.SECONDS);
            pool.shutdownNow();
        }
    }

    private static void managerCloseWaitsForDestruction() throws Exception {
        BlockingClose.reset();
        Path first = xml("""
                <beans version="2"><bean id="service" class="%s"/></beans>
                """.formatted(BlockingClose.class.getName()));
        ReloadableBeanContext manager = XmlBeans.reloadable()
                .reloadPolicy(ReloadPolicy.GRACEFUL)
                .shutdownPolicy(ReloadPolicy.IMMEDIATE)
                .load(first);
        ContextLease old = manager.acquire();
        equal(ReloadResult.Status.ACTIVATED, manager.reload(graph("replacement", false)).status());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> leaseClose = pool.submit(old::close);
        check(BlockingClose.entered.await(5, TimeUnit.SECONDS), "old generation destruction started");
        Future<?> managerClose = pool.submit(manager::close);
        Thread.sleep(100);
        check(!managerClose.isDone(), "manager close returned while generation destruction was in progress");
        BlockingClose.release.countDown();
        leaseClose.get(5, TimeUnit.SECONDS);
        managerClose.get(5, TimeUnit.SECONDS);
        pool.shutdownNow();
    }

    private static void managerClose() throws Exception {
        reset();
        ReloadableBeanContext manager = XmlBeans.reloadable().load(graph("one", false));
        manager.close();
        manager.close();
        check(manager.isClosed(), "manager closed");
        expect(IllegalStateException.class, manager::acquire);
        ReloadResult result = manager.reload(graph("two", false));
        equal(ReloadResult.Status.MANAGER_CLOSED, result.status());
    }

    private static Path graph(String value, boolean fail) throws Exception {
        return xml("""
                <beans version="2"><bean id="service" class="%s">
                  <constructor><arg value="%s"/><arg value="%s"/></constructor>
                  <init method="start"/>
                </bean></beans>
                """.formatted(Versioned.class.getName(), value, fail));
    }

    private static Path pairedGraph(String value) throws Exception {
        return xml("""
                <beans version="2">
                  <bean id="dependency" class="%s"><constructor><arg value="%s"/></constructor></bean>
                  <bean id="pair" class="%s"><constructor><arg value="%s"/><arg ref="dependency"/></constructor></bean>
                </beans>
                """.formatted(PairDependency.class.getName(), value, PairService.class.getName(), value));
    }

    private static Path xml(String text) throws Exception {
        Path path = Files.createTempFile("simple-di-reload-", ".xml");
        Files.writeString(path, text);
        path.toFile().deleteOnExit();
        return path;
    }

    private static int index(List<ReloadEvent.Kind> values, ReloadEvent.Kind kind) {
        int result = values.indexOf(kind);
        if (result < 0) throw new AssertionError("missing event " + kind + ": " + values);
        return result;
    }

    private static void reset() {
        EVENTS.clear();
        CONSTRUCTIONS.set(0);
    }

    private static void run(String name, Checked test) throws Exception {
        try {
            test.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (Throwable failure) {
            System.err.println("FAIL " + name);
            throw failure;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual) {
        if (!java.util.Objects.deepEquals(expected, actual)) {
            throw new AssertionError("expected=" + expected + " actual=" + actual);
        }
    }

    private static <T extends Throwable> T expect(Class<T> type, Checked action) throws Exception {
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) return type.cast(failure);
            throw new AssertionError("expected " + type.getName() + " but got " + failure, failure);
        }
        throw new AssertionError("expected " + type.getName());
    }

    @FunctionalInterface
    private interface Checked { void run() throws Exception; }

    private static final List<String> EVENTS = Collections.synchronizedList(new ArrayList<>());
    private static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();

    public static final class Versioned implements AutoCloseable {
        private final String value;
        private final boolean fail;
        public Versioned(String value, boolean fail) {
            this.value = value;
            this.fail = fail;
            CONSTRUCTIONS.incrementAndGet();
            EVENTS.add(value + ".construct");
        }
        public void start() {
            EVENTS.add(value + ".start");
            if (fail) throw new IllegalStateException(value + " startup failed");
        }
        public String value() { return value; }
        @Override public void close() { EVENTS.add(value + ".close"); }
    }

    public static final class FailingClose implements AutoCloseable {
        public FailingClose() {}
        @Override public void close() { throw new IllegalStateException("retire close failed"); }
    }

    public static final class FailingPrototype implements AutoCloseable {
        public FailingPrototype() {}
        @Override public void close() { throw new IllegalStateException("prototype close failed"); }
    }

    public static final class BlockingClose implements AutoCloseable {
        private static CountDownLatch entered = new CountDownLatch(1);
        private static CountDownLatch release = new CountDownLatch(1);
        public BlockingClose() {}
        private static void reset() {
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }
        @Override public void close() {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("close release timed out");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("close interrupted", failure);
            }
        }
    }

    private record RetainedLoaderProbe(
            ReloadableBeanContext manager,
            ReloadResult result,
            List<ReloadEvent> events,
            WeakReference<ClassLoader> oldLoader) {}

    private static final class TrackingLoader extends URLClassLoader {
        private final AtomicInteger closes;
        private TrackingLoader(URL[] urls, AtomicInteger closes) {
            super(urls, ReloadableTest.class.getClassLoader());
            this.closes = closes;
        }
        @Override public void close() throws java.io.IOException {
            closes.incrementAndGet();
            super.close();
        }
    }

    private record LoaderProbe(ReloadableBeanContext manager, AtomicInteger currentCloseCount,
                               WeakReference<ClassLoader> oldLoader) {}

    public record PairDependency(String value) {}
    public record PairService(String value, PairDependency dependency) {}
}
