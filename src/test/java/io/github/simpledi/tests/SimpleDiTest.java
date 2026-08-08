package io.github.simpledi.tests;

import io.github.simpledi.BeanContext;
import io.github.simpledi.BeanEvent;
import io.github.simpledi.BeanException;
import io.github.simpledi.BeanHandle;
import io.github.simpledi.BeanLifecycleInterceptor;
import io.github.simpledi.BeanLifecycleContext;
import io.github.simpledi.BeanScope;
import io.github.simpledi.BeanScopes;
import io.github.simpledi.ConfigurationDiff;
import io.github.simpledi.ContextSnapshot;
import io.github.simpledi.ValidationResult;
import io.github.simpledi.PropertySource;
import io.github.simpledi.TypeRef;
import io.github.simpledi.XmlBeans;
import io.github.simpledi.XmlLimits;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

public final class SimpleDiTest {
    private static int passed;

    private SimpleDiTest() {}

    public static void main(String[] args) throws Exception {
        run("object graph", SimpleDiTest::objectGraph);
        run("record constructor", SimpleDiTest::recordConstructor);
        run("static factory with arguments", SimpleDiTest::staticFactory);
        run("instance factory", SimpleDiTest::instanceFactory);
        run("method calls and varargs", SimpleDiTest::methodCallsAndVarargs);
        run("varargs constructor", SimpleDiTest::varargsConstructor);
        run("custom converter", SimpleDiTest::customConverter);
        run("explicit overload type", SimpleDiTest::explicitOverload);
        run("ambiguous overload rejected", SimpleDiTest::ambiguousOverload);
        run("cycle rejected", SimpleDiTest::cycleRejected);
        run("supplier breaks lazy cycle", SimpleDiTest::supplierCycle);
        run("unknown reference rejected", SimpleDiTest::unknownReference);
        run("optional reference", SimpleDiTest::optionalReference);
        run("include and alias", SimpleDiTest::includeAndAlias);
        run("classpath resource include", SimpleDiTest::classpathResourceInclude);
        run("include cycle rejected", SimpleDiTest::includeCycle);
        run("duplicate across include rejected", SimpleDiTest::duplicateAcrossInclude);
        run("alias cycle rejected", SimpleDiTest::aliasCycle);
        run("lazy bean", SimpleDiTest::lazyBean);
        run("prototype scope", SimpleDiTest::prototypeScope);
        run("caller-owned prototype handle", SimpleDiTest::callerOwnedPrototypeHandle);
        run("caller-owned prototype rollback", SimpleDiTest::callerOwnedPrototypeRollback);
        run("caller-owned duplicate identity rejected", SimpleDiTest::callerOwnedDuplicateIdentity);
        run("invalid prototype scope options rejected", SimpleDiTest::prototypeScopeValidation);
        run("depends-on ordering", SimpleDiTest::dependsOnOrdering);
        run("by-type lookup", SimpleDiTest::byTypeLookup);
        run("external binding injection and alias", SimpleDiTest::externalBinding);
        run("external instance factory is unmanaged", SimpleDiTest::externalFactory);
        run("external binding collisions rejected", SimpleDiTest::externalBindingCollisions);
        run("managed bean cannot claim external identity", SimpleDiTest::externalIdentityCollision);
        run("constant value", SimpleDiTest::constantValue);
        run("properties and recursive placeholders", SimpleDiTest::propertiesAndPlaceholders);
        run("placeholder cycle rejected", SimpleDiTest::placeholderCycle);
        run("concrete and sorted collections", SimpleDiTest::collectionTargets);
        run("immutable values", SimpleDiTest::immutableValues);
        run("duplicate map key rejected", SimpleDiTest::duplicateMapKey);
        run("preflight prevents construction", SimpleDiTest::preflightPreventsConstruction);
        run("invalid conversion is preflight", SimpleDiTest::invalidConversionPreflight);
        run("validation only has no side effects", SimpleDiTest::validationOnly);
        run("generic supplier validation", SimpleDiTest::genericSupplierValidation);
        run("null primitive rejected", SimpleDiTest::nullPrimitiveRejected);
        run("lifecycle reverse order", SimpleDiTest::lifecycleOrder);
        run("initialization rollback", SimpleDiTest::initializationRollback);
        run("Error converter rollback", SimpleDiTest::errorConverterRollback);
        run("lazy failure rollback and retry", SimpleDiTest::lazyRollbackRetry);
        run("duplicate singleton identity rejected", SimpleDiTest::duplicateIdentity);
        run("shutdown failures aggregated", SimpleDiTest::shutdownFailures);
        run("explicit destroy wins", SimpleDiTest::explicitDestroyWins);
        run("concurrent singleton lookup", SimpleDiTest::concurrentSingleton);
        run("constructor worker lookup does not deadlock", SimpleDiTest::constructorWorkerLookup);
        run("cross-thread cycle detected", SimpleDiTest::crossThreadCycle);
        run("same-thread runtime cycle detected", SimpleDiTest::sameThreadRuntimeCycle);
        run("close waits for active creation", SimpleDiTest::closeWaitsForActiveCreation);
        run("deterministic by-type order", SimpleDiTest::deterministicByTypeOrder);
        run("closed context releases plugin classloader", SimpleDiTest::classLoaderReleased);
        run("XXE and DTD rejected", SimpleDiTest::xxeRejected);
        run("XML limits enforced", SimpleDiTest::xmlLimits);
        run("strict XML diagnostics", SimpleDiTest::strictDiagnostics);
        run("property source precedence and report", SimpleDiTest::propertySourcePrecedence);
        run("sensitive property redaction", SimpleDiTest::sensitivePropertyRedaction);
        run("explicit overlays", SimpleDiTest::explicitOverlays);
        run("typed generic external binding", SimpleDiTest::typedGenericBinding);
        run("inherited generic setter resolution", SimpleDiTest::inheritedGenericSetter);
        run("generic instance factory", SimpleDiTest::genericInstanceFactory);
        run("generic by-type lookup", SimpleDiTest::genericByTypeLookup);
        run("parent child contexts", SimpleDiTest::parentChildContexts);
        run("validation report collects failures", SimpleDiTest::validationReportFailures);
        run("validation report describes graph", SimpleDiTest::validationReportGraph);
        run("exact executable signatures", SimpleDiTest::exactSignatures);
        run("named and indexed arguments", SimpleDiTest::namedAndIndexedArguments);
        run("versioned grammar", SimpleDiTest::versionedGrammar);
        run("stream reader and string inputs", SimpleDiTest::inlineInputs);
        run("include sandbox", SimpleDiTest::includeSandbox);
        run("aggregate input limits", SimpleDiTest::aggregateInputLimits);
        run("parser fuzz smoke", SimpleDiTest::parserFuzzSmoke);
        run("container lifecycle events", SimpleDiTest::containerEvents);
        run("listener failure rolls back", SimpleDiTest::listenerFailureRollback);
        run("lifecycle sequencing and external ownership", SimpleDiTest::lifecycleSequencing);
        run("property conditions and conditional overlays", SimpleDiTest::propertyConditions);
        run("inactive conditions skip class loading", SimpleDiTest::inactiveConditionsSkipClassLoading);
        run("invalid condition combinations rejected", SimpleDiTest::invalidConditionCombinations);
        run("custom keyed scope lifecycle", SimpleDiTest::customScopeLifecycle);
        run("custom scope rollback and retry", SimpleDiTest::customScopeRollback);
        run("custom scope concurrent publication", SimpleDiTest::customScopeConcurrency);
        run("custom scope cross-thread cycle detected", SimpleDiTest::customScopeCrossThreadCycle);
        run("custom scope shutdown failure aggregated", SimpleDiTest::customScopeShutdownFailure);
        run("unknown custom scope rejected", SimpleDiTest::unknownCustomScope);
        run("scope escape rejected", SimpleDiTest::scopeEscapeRejected);
        run("instance lifecycle interceptors", SimpleDiTest::lifecycleInterceptors);
        run("prototype handle lifecycle interceptors", SimpleDiTest::prototypeLifecycleInterceptors);
        run("interceptor failure rolls back", SimpleDiTest::interceptorRollback);
        run("configuration diff and graph diagnostics", SimpleDiTest::configurationDiffAndGraph);
        run("runtime context snapshot", SimpleDiTest::contextSnapshot);
        run("closed context rejects lookup", SimpleDiTest::closedContext);
        System.out.println("PASS: " + passed + " tests");
    }

    private static void objectGraph() throws Exception {
        Fixtures.reset();
        String xml = """
                <beans>
                  <bean id="repo" class="%s">
                    <constructor><arg value="${repo.name:fallback}"/></constructor>
                  </bean>
                  <bean id="clock" class="java.time.Clock"><factory method="systemUTC"/></bean>
                  <bean id="service" class="%s">
                    <constructor><arg ref="repo"/><arg ref="clock"/></constructor>
                    <property name="batchSize" value="100"/>
                    <property name="names"><list><value>one</value><value>two</value></list></property>
                    <property name="modes"><set><value>FAST</value><value>SAFE</value></set></property>
                    <property name="weights"><map>
                      <entry key="high" value="9"/><entry><value>low</value><value>2</value></entry>
                    </map></property>
                    <property name="codes"><array><value>4</value><value>7</value></array></property>
                    <property name="maybe"><optional><value>present</value></optional></property>
                    <property name="listener"><bean class="%s"><property name="prefix" value="main"/></bean></property>
                  </bean>
                </beans>
                """.formatted(Fixtures.Repo.class.getName(), Fixtures.Service.class.getName(),
                Fixtures.AuditListener.class.getName());
        Path file = xml(xml);
        try (BeanContext context = XmlBeans.builder().property("repo.name", "primary").load(file)) {
            Fixtures.Service value = context.require("service", Fixtures.Service.class);
            equal("primary", value.repo().name());
            check(value.clock() instanceof Clock, "clock");
            equal(100, value.batchSize());
            equal(List.of("one", "two"), value.names());
            equal(Set.of(Fixtures.Mode.FAST, Fixtures.Mode.SAFE), value.modes());
            equal(Map.of("high", 9, "low", 2), value.weights());
            check(java.util.Arrays.equals(new int[] {4, 7}, value.codes()), "codes");
            equal(Optional.of("present"), value.maybe());
            equal("main-audit", value.listener().label());
            equal(Set.of("repo", "clock", "service"), context.beanNames());
            check(context.find("missing").isEmpty(), "missing optional");
        }
        check(Fixtures.EVENTS.contains("audit.close"), "nested auto close");
    }

    private static void recordConstructor() throws Exception {
        String body = """
                <beans><bean id="config" class="%s"><constructor>
                  <arg value="localhost"/><arg value="8080"/>
                </constructor></bean></beans>
                """.formatted(Fixtures.Config.class.getName());
        try (BeanContext context = XmlBeans.load(xml(body))) {
            equal(new Fixtures.Config("localhost", 8080), context.require("config", Fixtures.Config.class));
        }
    }

    private static void staticFactory() throws Exception {
        String body = """
                <beans><bean id="product" class="%s"><factory class="%s" method="create">
                  <arg value="widget"/><arg value="3" type="int"/>
                </factory></bean></beans>
                """.formatted(Fixtures.Product.class.getName(), Fixtures.ProductFactory.class.getName());
        try (BeanContext context = XmlBeans.load(xml(body))) {
            equal(new Fixtures.Product("widget", 3), context.require("product", Fixtures.Product.class));
        }
    }

    private static void instanceFactory() throws Exception {
        Fixtures.reset();
        String body = """
                <beans>
                  <bean id="factory" class="%s"/>
                  <bean id="product" class="%s"><factory bean="factory" method="create">
                    <arg value="instance"/><arg value="4"/>
                  </factory></bean>
                </beans>
                """.formatted(Fixtures.InstanceProductFactory.class.getName(), Fixtures.Product.class.getName());
        try (BeanContext context = XmlBeans.load(xml(body))) {
            equal(new Fixtures.Product("instance", 4), context.require("product", Fixtures.Product.class));
        }
        equal(List.of("factory.construct", "factory.close"), Fixtures.EVENTS);
    }

    private static void methodCallsAndVarargs() throws Exception {
        String body = """
                <beans><bean id="target" class="%s">
                  <call method="add"><arg value="a"/><arg value="b"/></call>
                  <call method="add"/>
                  <call method="add"><arg value="c"/></call>
                </bean></beans>
                """.formatted(Fixtures.MethodConfigured.class.getName());
        try (BeanContext context = XmlBeans.load(xml(body))) {
            equal(List.of("a", "b", "c"), context.require("target", Fixtures.MethodConfigured.class).values());
        }
    }

    private static void varargsConstructor() throws Exception {
        String body = """
                <beans><bean id="target" class="%s"><constructor>
                  <arg value="p"/><arg value="1"/><arg value="2"/><arg value="3"/>
                </constructor></bean></beans>
                """.formatted(Fixtures.VarargsTarget.class.getName());
        try (BeanContext context = XmlBeans.load(xml(body))) {
            Fixtures.VarargsTarget target = context.require("target", Fixtures.VarargsTarget.class);
            equal("p", target.prefix());
            check(java.util.Arrays.equals(new int[] {1, 2, 3}, target.values()), "varargs values");
        }
    }

    private static void customConverter() throws Exception {
        String body = """
                <beans><bean id="consumer" class="%s"><constructor>
                  <arg value="server.example:9443"/>
                </constructor></bean></beans>
                """.formatted(Fixtures.EndpointConsumer.class.getName());
        try (BeanContext context = XmlBeans.builder()
                .converter(Fixtures.Endpoint.class, (text, ignored) -> {
                    String[] parts = text.split(":", 2);
                    return new Fixtures.Endpoint(parts[0], Integer.parseInt(parts[1]));
                }).load(xml(body))) {
            equal(new Fixtures.Endpoint("server.example", 9443),
                    context.require("consumer", Fixtures.EndpointConsumer.class).endpoint());
        }
    }

    private static void explicitOverload() throws Exception {
        String body = """
                <beans><bean id="value" class="%s"><constructor><arg value="12" type="int"/></constructor></bean></beans>
                """.formatted(Fixtures.Ambiguous.class.getName());
        try (BeanContext context = XmlBeans.load(xml(body))) {
            equal("int:12", context.require("value", Fixtures.Ambiguous.class).selected());
        }
    }

    private static void ambiguousOverload() throws Exception {
        String body = """
                <beans><bean id="value" class="%s"><constructor><arg value="12"/></constructor></bean></beans>
                """.formatted(Fixtures.Ambiguous.class.getName());
        BeanException error = expect(BeanException.class, () -> XmlBeans.load(xml(body)));
        contains(error.getMessage(), "Ambiguous constructor");
        contains(error.getMessage(), "type=\"...\"");
    }

    private static void cycleRejected() throws Exception {
        String body = """
                <beans>
                  <bean id="a" class="%s"><constructor><arg ref="b"/></constructor></bean>
                  <bean id="b" class="%s"><constructor><arg ref="a"/></constructor></bean>
                </beans>
                """.formatted(Fixtures.A.class.getName(), Fixtures.B.class.getName());
        BeanException error = expect(BeanException.class, () -> XmlBeans.load(xml(body)));
        contains(error.getMessage(), "Circular dependency: a -> b -> a");
    }

    private static void supplierCycle() throws Exception {
        Fixtures.reset();
        String body = """
                <beans>
                  <bean id="a" class="%s"><constructor><arg><supplier ref="b"/></arg></constructor></bean>
                  <bean id="b" class="%s" lazy="true"><constructor><arg ref="a"/></constructor></bean>
                </beans>
                """.formatted(Fixtures.SupplierA.class.getName(), Fixtures.SupplierB.class.getName());
        try (BeanContext context = XmlBeans.load(xml(body))) {
            equal(List.of("supplierA.construct"), Fixtures.EVENTS);
            Fixtures.SupplierA a = context.require("a", Fixtures.SupplierA.class);
            Fixtures.SupplierB b = a.b();
            check(b.a() == a, "supplier cycle identity");
            equal(List.of("supplierA.construct", "supplierB.construct"), Fixtures.EVENTS);
        }
    }

    private static void unknownReference() throws Exception {
        String body = """
                <beans><bean id="owner" class="%s"><constructor><arg ref="absent"/></constructor></bean></beans>
                """.formatted(Fixtures.Owner.class.getName());
        BeanException error = expect(BeanException.class, () -> XmlBeans.load(xml(body)));
        contains(error.getMessage(), "Unknown bean reference 'absent'");
    }

    private static void optionalReference() throws Exception {
        String body = """
                <beans>
                  <bean id="repo" class="%s"><constructor><arg value="present"/></constructor></bean>
                  <bean id="present" class="%s"><constructor><arg><optional-ref bean="repo"/></arg></constructor></bean>
                  <bean id="missing" class="%s"><constructor><arg><optional-ref bean="notThere"/></arg></constructor></bean>
                </beans>
                """.formatted(Fixtures.Repo.class.getName(), Fixtures.OptionalConsumer.class.getName(),
                Fixtures.OptionalConsumer.class.getName());
        try (BeanContext context = XmlBeans.load(xml(body))) {
            equal(Optional.of(new Fixtures.Repo("present")), context.require("present", Fixtures.OptionalConsumer.class).repo());
            equal(Optional.empty(), context.require("missing", Fixtures.OptionalConsumer.class).repo());
        }
    }

    private static void includeAndAlias() throws Exception {
        Path dir = Files.createTempDirectory("simple-di-include-");
        Files.writeString(dir.resolve("child.xml"), """
                <beans>
                  <bean id="repo" class="%s"><constructor><arg value="included"/></constructor></bean>
                  <alias name="repo" alias="repository"/>
                </beans>
                """.formatted(Fixtures.Repo.class.getName()));
        Files.writeString(dir.resolve("root.xml"), "<beans><include file=\"child.xml\"/></beans>");
        try (BeanContext context = XmlBeans.load(dir.resolve("root.xml"))) {
            check(context.require("repo") == context.require("repository"), "alias identity");
            equal(Set.of("repository"), context.aliases());
        }
    }

    private static void classpathResourceInclude() {
        try (BeanContext context = XmlBeans.loadResource("simpledi/root.xml")) {
            equal(new Fixtures.Repo("resource"), context.require("resourceAlias", Fixtures.Repo.class));
        }
    }

    private static void includeCycle() throws Exception {
        Path dir = Files.createTempDirectory("simple-di-cycle-");
        Files.writeString(dir.resolve("a.xml"), "<beans><include file=\"b.xml\"/></beans>");
        Files.writeString(dir.resolve("b.xml"), "<beans><include file=\"a.xml\"/></beans>");
        BeanException error = expect(BeanException.class, () -> XmlBeans.load(dir.resolve("a.xml")));
        contains(error.getMessage(), "Include cycle");
    }

    private static void duplicateAcrossInclude() throws Exception {
        Path dir = Files.createTempDirectory("simple-di-duplicate-");
        String bean = "<bean id=\"x\" class=\"%s\"/>".formatted(Fixtures.Dependency.class.getName());
        Files.writeString(dir.resolve("child.xml"), "<beans>" + bean + "</beans>");
        Files.writeString(dir.resolve("root.xml"), "<beans><include file=\"child.xml\"/>" + bean + "</beans>");
        BeanException error = expect(BeanException.class, () -> XmlBeans.load(dir.resolve("root.xml")));
        contains(error.getMessage(), "Duplicate bean/alias name 'x'");
    }

    private static void aliasCycle() throws Exception {
        String body = "<beans><alias name=\"b\" alias=\"a\"/><alias name=\"a\" alias=\"b\"/></beans>";
        BeanException error = expect(BeanException.class, () -> XmlBeans.load(xml(body)));
        contains(error.getMessage(), "Alias cycle");
    }

    private static void lazyBean() throws Exception {
        Fixtures.LazyProbe.CONSTRUCTED.set(0);
        Fixtures.reset();
        String body = "<beans><bean id=\"lazy\" class=\"%s\" lazy=\"true\"/></beans>"
                .formatted(Fixtures.LazyProbe.class.getName());
        try (BeanContext context = XmlBeans.load(xml(body))) {
            equal(0, Fixtures.LazyProbe.CONSTRUCTED.get());
            check(context.contains("lazy"), "contains lazy");
            equal(0, Fixtures.LazyProbe.CONSTRUCTED.get());
            check(context.find("lazy").isPresent(), "find lazy");
            equal(1, Fixtures.LazyProbe.CONSTRUCTED.get());
        }
        equal(List.of("lazy.construct", "lazy.close"), Fixtures.EVENTS);
    }

    private static void prototypeScope() throws Exception {
        Fixtures.reset();
        Fixtures.PrototypeProbe.NEXT_ID.set(0);
        String body = """
                <beans>
                  <bean id="prototype" class="%s" scope="prototype" ownership="context"/>
                  <bean id="pair" class="%s"><constructor>
                    <arg ref="prototype"/><arg ref="prototype"/>
                  </constructor></bean>
                  <bean id="supplier" class="%s"><constructor>
                    <arg><supplier ref="prototype"/></arg>
                  </constructor></bean>
                </beans>
                """.formatted(Fixtures.PrototypeProbe.class.getName(), Fixtures.PrototypePair.class.getName(),
                Fixtures.PrototypeSupplierConsumer.class.getName());
        BeanContext context = XmlBeans.load(xml(body));
        Fixtures.PrototypePair pair = context.require("pair", Fixtures.PrototypePair.class);
        check(pair.first() != pair.second(), "prototype references are distinct");
        equal(1, pair.first().id());
        equal(2, pair.second().id());
        Fixtures.PrototypeProbe direct = context.require("prototype", Fixtures.PrototypeProbe.class);
        Fixtures.PrototypeSupplierConsumer supplier = context.require("supplier", Fixtures.PrototypeSupplierConsumer.class);
        Fixtures.PrototypeProbe suppliedOne = supplier.supplier().get();
        Fixtures.PrototypeProbe suppliedTwo = supplier.supplier().get();
        equal(List.of(3, 4, 5), List.of(direct.id(), suppliedOne.id(), suppliedTwo.id()));
        check(direct != suppliedOne && suppliedOne != suppliedTwo, "prototype lookup identities");
        context.close();
        equal(List.of(
                "prototype.1.construct", "prototype.2.construct", "prototype.3.construct",
                "prototype.4.construct", "prototype.5.construct",
                "prototype.5.close", "prototype.4.close", "prototype.3.close",
                "prototype.2.close", "prototype.1.close"), Fixtures.EVENTS);
    }

    private static void callerOwnedPrototypeHandle() throws Exception {
        Fixtures.reset();
        Fixtures.PrototypeProbe.NEXT_ID.set(0);
        String body = """
                <beans><bean id="prototype" class="%s" scope="prototype"/></beans>
                """
                .formatted(Fixtures.PrototypeProbe.class.getName());
        BeanContext context = XmlBeans.load(xml(body));
        try (BeanHandle<Fixtures.PrototypeProbe> handle = context.create("prototype", Fixtures.PrototypeProbe.class)) {
            equal(1, handle.value().id());
            equal(List.of("prototype.1.construct"), Fixtures.EVENTS);
        }
        equal(List.of("prototype.1.construct", "prototype.1.close"), Fixtures.EVENTS);

        Fixtures.PrototypeProbe untracked = context.require("prototype", Fixtures.PrototypeProbe.class);
        equal(2, untracked.id());
        context.close();
        equal(List.of("prototype.1.construct", "prototype.1.close", "prototype.2.construct"), Fixtures.EVENTS);
        untracked.close();
        equal(List.of("prototype.1.construct", "prototype.1.close", "prototype.2.construct", "prototype.2.close"),
                Fixtures.EVENTS);

        String managed = """
                <beans><bean id="prototype" class="%s" scope="prototype" ownership="context"/></beans>
                """
                .formatted(Fixtures.PrototypeProbe.class.getName());
        try (BeanContext managedContext = XmlBeans.load(xml(managed))) {
            BeanException error = expect(BeanException.class, () -> managedContext.create("prototype"));
            contains(error.getMessage(), "context-owned");
        }
    }

    private static void callerOwnedPrototypeRollback() throws Exception {
        Fixtures.reset();
        String body = """
                <beans>
                  <bean id="dependency" class="%s" scope="prototype"/>
                  <bean id="failing" class="%s" scope="prototype" init-method="start">
                    <constructor><arg ref="dependency"/></constructor>
                  </bean>
                </beans>
                """.formatted(Fixtures.Dependency.class.getName(), Fixtures.Failing.class.getName());
        try (BeanContext context = XmlBeans.load(xml(body))) {
            BeanException error = expect(BeanException.class, () -> context.create("failing"));
            contains(error.getMessage(), "Invocation failed");
            equal(List.of("dependency.construct", "failing.construct", "failing.start",
                    "failing.close", "dependency.close"), Fixtures.EVENTS);
            check(context.contains("failing"), "context remains usable after prototype rollback");
        }
    }

    private static void callerOwnedDuplicateIdentity() throws Exception {
        Fixtures.reset();
        String body = """
                <beans><bean id="shared" class="%s" scope="prototype">
                  <factory class="%s" method="get"/>
                </bean></beans>
                """.formatted(Fixtures.SharedSingleton.class.getName(), Fixtures.SharedFactory.class.getName());
        try (BeanContext context = XmlBeans.load(xml(body));
             BeanHandle<Fixtures.SharedSingleton> first = context.create("shared", Fixtures.SharedSingleton.class)) {
            check(first.value() == Fixtures.SharedSingleton.INSTANCE, "first shared prototype");
            BeanException error = expect(BeanException.class,
                    () -> context.create("shared", Fixtures.SharedSingleton.class));
            contains(error.getMessage(), "caller-owned bean");
        }
        equal(List.of("shared.close"), Fixtures.EVENTS);
    }

    private static void prototypeScopeValidation() throws Exception {
        String lazy = """
                <beans><bean id="x" class="%s" scope="prototype" lazy="true"/></beans>
                """.formatted(Fixtures.PrototypeProbe.class.getName());
        BeanException lazyError = expect(BeanException.class, () -> XmlBeans.load(xml(lazy)));
        contains(lazyError.getMessage(), "lazy is not valid for prototype");

        String nested = """
                <beans><bean id="holder" class="%s"><constructor>
                  <arg><bean class="%s" scope="prototype"/></arg>
                </constructor></bean></beans>
                """.formatted(Fixtures.PrototypePair.class.getName(), Fixtures.PrototypeProbe.class.getName());
        BeanException nestedError = expect(BeanException.class, () -> XmlBeans.load(xml(nested)));
        contains(nestedError.getMessage(), "Nested <bean> cannot declare scope");

        String singletonCaller = """
                <beans><bean id="x" class="%s" ownership="caller"/></beans>
                """
                .formatted(Fixtures.PrototypeProbe.class.getName());
        BeanException ownershipError = expect(BeanException.class, () -> XmlBeans.load(xml(singletonCaller)));
        contains(ownershipError.getMessage(), "only for prototype");
    }

    private static void dependsOnOrdering() throws Exception {
        Fixtures.reset();
        String body = """
                <beans>
                  <bean id="second" class="%s" depends-on="first"><constructor><arg value="second"/></constructor></bean>
                  <bean id="first" class="%s"><constructor><arg value="first"/></constructor></bean>
                </beans>
                """.formatted(Fixtures.EventBean.class.getName(), Fixtures.EventBean.class.getName());
        try (BeanContext context = XmlBeans.load(xml(body))) {
            check(context.contains("second"), "depends-on context");
            equal(List.of("first.construct", "second.construct"), Fixtures.EVENTS);
        }
        equal(List.of("first.construct", "second.construct", "second.close", "first.close"), Fixtures.EVENTS);
    }

    private static void byTypeLookup() throws Exception {
        String one = "<beans><bean id=\"one\" class=\"%s\"/></beans>".formatted(Fixtures.MarkerOne.class.getName());
        try (BeanContext context = XmlBeans.load(xml(one))) {
            check(context.require(Fixtures.Marker.class) instanceof Fixtures.MarkerOne, "require type");
            equal(Set.of("one"), context.beansOfType(Fixtures.Marker.class).keySet());
        }
        String two = "<beans><bean id=\"one\" class=\"%s\"/><bean id=\"two\" class=\"%s\"/></beans>"
                .formatted(Fixtures.MarkerOne.class.getName(), Fixtures.MarkerTwo.class.getName());
        try (BeanContext context = XmlBeans.load(xml(two))) {
            BeanException error = expect(BeanException.class, () -> context.require(Fixtures.Marker.class));
            contains(error.getMessage(), "Multiple beans");
            equal(Set.of("one", "two"), context.beansOfType(Fixtures.Marker.class).keySet());
        }
    }

    private static void externalBinding() throws Exception {
        Fixtures.reset();
        Fixtures.Dependency external = new Fixtures.Dependency();
        String body = """
                <beans>
                  <alias name="hostDependency" alias="dependency"/>
                  <bean id="owner" class="%s" destroy-method="stop">
                    <constructor><arg ref="dependency"/></constructor>
                  </bean>
                </beans>
                """.formatted(Fixtures.Owner.class.getName());
        Path file = xml(body);
        ValidationResult validation = XmlBeans.builder().bind("hostDependency", external).validate(file);
        equal(Set.of("hostDependency", "owner"), validation.beanNames());
        try (BeanContext context = XmlBeans.builder().bind("hostDependency", external).load(file)) {
            check(context.require("dependency") == external, "external alias identity");
            check(context.require(Fixtures.Dependency.class) == external, "external by-type lookup");
            check(context.require("owner", Fixtures.Owner.class).dependency() == external, "external injection");
            equal(Set.of("hostDependency", "owner"), context.beanNames());
        }
        equal(List.of("dependency.construct", "owner.construct", "owner.stop"), Fixtures.EVENTS);
    }

    private static void externalFactory() throws Exception {
        Fixtures.reset();
        Fixtures.InstanceProductFactory external = new Fixtures.InstanceProductFactory();
        String body = """
                <beans><bean id="product" class="%s"><factory bean="factory" method="create">
                  <arg value="external"/><arg value="8"/>
                </factory></bean></beans>
                """.formatted(Fixtures.Product.class.getName());
        try (BeanContext context = XmlBeans.builder().bind("factory", external).load(xml(body))) {
            equal(new Fixtures.Product("external", 8), context.require("product", Fixtures.Product.class));
        }
        equal(List.of("factory.construct"), Fixtures.EVENTS);
    }

    private static void externalBindingCollisions() throws Exception {
        Fixtures.Dependency first = new Fixtures.Dependency();
        IllegalArgumentException identity = expect(IllegalArgumentException.class,
                () -> XmlBeans.builder().bind("one", first).bind("two", first));
        contains(identity.getMessage(), "same object identity");

        String body = """
                <beans><bean id="external" class="%s"/></beans>
                """.formatted(Fixtures.Dependency.class.getName());
        BeanException name = expect(BeanException.class,
                () -> XmlBeans.builder().bind("external", new Fixtures.Dependency()).load(xml(body)));
        contains(name.getMessage(), "Duplicate bean/alias name 'external'");
    }

    private static void externalIdentityCollision() throws Exception {
        Fixtures.reset();
        String body = """
                <beans><bean id="duplicate" class="%s"><factory class="%s" method="get"/></bean></beans>
                """.formatted(Fixtures.SharedSingleton.class.getName(), Fixtures.SharedFactory.class.getName());
        BeanException error = expect(BeanException.class, () -> XmlBeans.builder()
                .bind("external", Fixtures.SharedSingleton.INSTANCE)
                .load(xml(body)));
        contains(error.getMessage(), "external binding");
        equal(List.of(), Fixtures.EVENTS);
    }

    private static void constantValue() throws Exception {
        String body = """
                <beans><bean id="consumer" class="%s"><constructor>
                  <arg><constant class="java.nio.charset.StandardCharsets" field="UTF_8"/></arg>
                </constructor></bean></beans>
                """.formatted(Fixtures.ConstantConsumer.class.getName());
        try (BeanContext context = XmlBeans.load(xml(body))) {
            check(context.require("consumer", Fixtures.ConstantConsumer.class).charset() == StandardCharsets.UTF_8,
                    "constant identity");
        }
    }

    private static void propertiesAndPlaceholders() throws Exception {
        String body = """
                <beans>
                  <bean id="holder" class="%s"><constructor><arg value="${a}"/></constructor></bean>
                  <bean id="props" class="%s"><constructor><arg><properties immutable="true">
                    <property name="url" value="https://${host}:${port:443}"/>
                  </properties></arg></constructor></bean>
                </beans>
                """.formatted(Fixtures.StringHolder.class.getName(), Fixtures.PropertiesConsumer.class.getName());
        try (BeanContext context = XmlBeans.builder()
                .property("a", "${b}").property("b", "resolved")
                .property("host", "example.test").load(xml(body))) {
            equal("resolved", context.require("holder", Fixtures.StringHolder.class).value());
            equal("https://example.test:443", context.require("props", Fixtures.PropertiesConsumer.class)
                    .properties().getProperty("url"));
        }
    }

    private static void placeholderCycle() throws Exception {
        String body = "<beans><bean id=\"x\" class=\"%s\"><constructor><arg value=\"${a}\"/></constructor></bean></beans>"
                .formatted(Fixtures.StringHolder.class.getName());
        BeanException error = expect(BeanException.class, () -> XmlBeans.builder()
                .property("a", "${b}").property("b", "${a}").load(xml(body)));
        contains(error.getMessage(), "Circular property expansion");
    }

    private static void collectionTargets() throws Exception {
        String body = """
                <beans><bean id="target" class="%s">
                  <property name="hashSet"><set><value>b</value><value>a</value></set></property>
                  <property name="hashMap"><map><entry key="a" value="1"/></map></property>
                  <property name="deque"><list><value>a</value><value>b</value></list></property>
                  <property name="sortedSet"><set><value>b</value><value>a</value></set></property>
                  <property name="navigableSet"><set><value>b</value><value>a</value></set></property>
                  <property name="sortedMap"><map><entry key="b" value="2"/><entry key="a" value="1"/></map></property>
                  <property name="navigableMap"><map><entry key="b" value="2"/><entry key="a" value="1"/></map></property>
                </bean></beans>
                """.formatted(Fixtures.CollectionTargets.class.getName());
        try (BeanContext context = XmlBeans.load(xml(body))) {
            Fixtures.CollectionTargets target = context.require("target", Fixtures.CollectionTargets.class);
            check(target.hashSet().getClass() == HashSet.class, "exact HashSet");
            check(target.hashMap().getClass() == HashMap.class, "exact HashMap");
            equal(List.of("a", "b"), new ArrayList<>(target.deque()));
            equal(List.of("a", "b"), new ArrayList<>(target.sortedSet()));
            equal(List.of("a", "b"), new ArrayList<>(target.navigableSet()));
            equal(List.of("a", "b"), new ArrayList<>(target.sortedMap().keySet()));
            equal(List.of("a", "b"), new ArrayList<>(target.navigableMap().keySet()));
        }
    }

    private static void immutableValues() throws Exception {
        String body = """
                <beans><bean id="target" class="%s">
                  <property name="list"><list immutable="true"><value>a</value></list></property>
                  <property name="set"><set immutable="true"><value>a</value></set></property>
                  <property name="sortedSet"><set immutable="true"><value>b</value><value>a</value></set></property>
                  <property name="map"><map immutable="true"><entry key="a" value="1"/></map></property>
                  <property name="sortedMap"><map immutable="true"><entry key="b" value="2"/><entry key="a" value="1"/></map></property>
                  <property name="properties"><properties immutable="true"><property name="a" value="b"/></properties></property>
                </bean></beans>
                """.formatted(Fixtures.ImmutableTargets.class.getName());
        try (BeanContext context = XmlBeans.load(xml(body))) {
            Fixtures.ImmutableTargets target = context.require("target", Fixtures.ImmutableTargets.class);
            expect(UnsupportedOperationException.class, () -> target.list().add("x"));
            expect(UnsupportedOperationException.class, () -> target.set().add("x"));
            expect(UnsupportedOperationException.class, () -> target.sortedSet().add("x"));
            expect(UnsupportedOperationException.class, () -> target.map().put("x", 2));
            expect(UnsupportedOperationException.class, () -> target.sortedMap().put("x", 2));
            expect(UnsupportedOperationException.class, () -> target.properties().setProperty("x", "y"));
            expect(UnsupportedOperationException.class,
                    () -> target.properties().entrySet().iterator().next().setValue("x"));
        }
    }

    private static void duplicateMapKey() throws Exception {
        String body = """
                <beans><bean id="service" class="%s"><property name="hashMap"><map>
                  <entry key="x" value="1"/><entry key="x" value="2"/>
                </map></property></bean></beans>
                """.formatted(Fixtures.CollectionTargets.class.getName());
        BeanException error = expect(BeanException.class, () -> XmlBeans.load(xml(body)));
        contains(error.getMessage(), "Duplicate map key");
    }

    private static void preflightPreventsConstruction() throws Exception {
        Fixtures.PreflightProbe.CONSTRUCTED.set(0);
        String body = "<beans><bean id=\"probe\" class=\"%s\"><property name=\"missing\" value=\"x\"/></bean></beans>"
                .formatted(Fixtures.PreflightProbe.class.getName());
        BeanException error = expect(BeanException.class, () -> XmlBeans.load(xml(body)));
        contains(error.getMessage(), "No public matching setter");
        equal(0, Fixtures.PreflightProbe.CONSTRUCTED.get());
    }

    private static void invalidConversionPreflight() throws Exception {
        Fixtures.IntProbe.CONSTRUCTED.set(0);
        String body = "<beans><bean id=\"probe\" class=\"%s\"><constructor><arg value=\"not-an-int\"/></constructor></bean></beans>"
                .formatted(Fixtures.IntProbe.class.getName());
        BeanException error = expect(BeanException.class, () -> XmlBeans.load(xml(body)));
        contains(error.getMessage(), "Cannot convert");
        equal(0, Fixtures.IntProbe.CONSTRUCTED.get());
    }

    private static void validationOnly() throws Exception {
        Fixtures.PreflightProbe.CONSTRUCTED.set(0);
        String body = "<beans><bean id=\"probe\" class=\"%s\"/><alias name=\"probe\" alias=\"p\"/></beans>"
                .formatted(Fixtures.PreflightProbe.class.getName());
        ValidationResult result = XmlBeans.validate(xml(body));
        equal(Set.of("probe"), result.beanNames());
        equal(Set.of("p"), result.aliases());
        equal(0, Fixtures.PreflightProbe.CONSTRUCTED.get());
    }

    private static void genericSupplierValidation() throws Exception {
        String body = """
                <beans>
                  <bean id="marker" class="%s"/>
                  <bean id="consumer" class="%s"><constructor><arg><supplier ref="marker"/></arg></constructor></bean>
                </beans>
                """.formatted(Fixtures.MarkerOne.class.getName(), Fixtures.GenericSupplierConsumer.class.getName());
        BeanException error = expect(BeanException.class, () -> XmlBeans.load(xml(body)));
        contains(error.getMessage(), "cannot be assigned to");
    }

    private static void nullPrimitiveRejected() throws Exception {
        String body = """
                <beans><bean id="config" class="%s"><constructor><arg value="host"/><arg><null/></arg></constructor></bean></beans>
                """.formatted(Fixtures.Config.class.getName());
        BeanException error = expect(BeanException.class, () -> XmlBeans.load(xml(body)));
        contains(error.getMessage(), "No compatible constructor");
    }

    private static void lifecycleOrder() throws Exception {
        Fixtures.reset();
        String body = """
                <beans>
                  <bean id="dependency" class="%s"/>
                  <bean id="owner" class="%s" init-method="start" destroy-method="stop">
                    <constructor><arg ref="dependency"/></constructor>
                  </bean>
                </beans>
                """.formatted(Fixtures.Dependency.class.getName(), Fixtures.Owner.class.getName());
        BeanContext context = XmlBeans.load(xml(body));
        equal(List.of("dependency.construct", "owner.construct", "owner.start"), Fixtures.EVENTS);
        context.close();
        equal(List.of("dependency.construct", "owner.construct", "owner.start", "owner.stop", "dependency.close"),
                Fixtures.EVENTS);
        context.close();
    }

    private static void initializationRollback() throws Exception {
        Fixtures.reset();
        String body = """
                <beans>
                  <bean id="dependency" class="%s"/>
                  <bean id="failing" class="%s" init-method="start"><constructor><arg ref="dependency"/></constructor></bean>
                </beans>
                """.formatted(Fixtures.Dependency.class.getName(), Fixtures.Failing.class.getName());
        BeanException error = expect(BeanException.class, () -> XmlBeans.load(xml(body)));
        contains(error.getMessage(), "Invocation failed");
        equal(List.of("dependency.construct", "failing.construct", "failing.start", "failing.close", "dependency.close"),
                Fixtures.EVENTS);
    }

    private static void errorConverterRollback() throws Exception {
        Fixtures.reset();
        String body = """
                <beans>
                  <bean id="dependency" class="%s"/>
                  <bean id="consumer" class="%s"><constructor><arg value="explode"/></constructor></bean>
                </beans>
                """.formatted(Fixtures.Dependency.class.getName(), Fixtures.EndpointConsumer.class.getName());
        BeanException error = expect(BeanException.class, () -> XmlBeans.builder()
                .converter(Fixtures.Endpoint.class, (text, ignored) -> { throw new AssertionError("converter error"); })
                .load(xml(body)));
        contains(error.getMessage(), "converter error");
        equal(List.of("dependency.construct", "dependency.close"), Fixtures.EVENTS);
    }

    private static void lazyRollbackRetry() throws Exception {
        Fixtures.reset();
        Fixtures.FlakyLazy.ATTEMPTS.set(0);
        String body = """
                <beans>
                  <bean id="dep" class="%s" lazy="true"/>
                  <bean id="flaky" class="%s" lazy="true"><constructor><arg ref="dep"/></constructor></bean>
                </beans>
                """.formatted(Fixtures.RetryDependency.class.getName(), Fixtures.FlakyLazy.class.getName());
        try (BeanContext context = XmlBeans.load(xml(body))) {
            BeanException first = expect(BeanException.class, () -> context.require("flaky"));
            contains(first.getMessage(), "first attempt fails");
            equal(List.of("retry.dep.construct", "flaky.attempt", "retry.dep.close"), Fixtures.EVENTS);
            context.require("flaky", Fixtures.FlakyLazy.class);
            equal(List.of("retry.dep.construct", "flaky.attempt", "retry.dep.close",
                    "retry.dep.construct", "flaky.attempt", "flaky.construct"), Fixtures.EVENTS);
        }
        equal(List.of("retry.dep.construct", "flaky.attempt", "retry.dep.close",
                "retry.dep.construct", "flaky.attempt", "flaky.construct", "flaky.close", "retry.dep.close"),
                Fixtures.EVENTS);
    }

    private static void duplicateIdentity() throws Exception {
        Fixtures.reset();
        String body = """
                <beans>
                  <bean id="one" class="%s"><factory class="%s" method="get"/></bean>
                  <bean id="two" class="%s"><factory class="%s" method="get"/></bean>
                </beans>
                """.formatted(Fixtures.SharedSingleton.class.getName(), Fixtures.SharedFactory.class.getName(),
                Fixtures.SharedSingleton.class.getName(), Fixtures.SharedFactory.class.getName());
        BeanException error = expect(BeanException.class, () -> XmlBeans.load(xml(body)));
        contains(error.getMessage(), "Use <alias>");
        equal(List.of("shared.close"), Fixtures.EVENTS);
    }

    private static void shutdownFailures() throws Exception {
        Fixtures.reset();
        String body = """
                <beans>
                  <bean id="a" class="%s"><constructor><arg value="a"/></constructor></bean>
                  <bean id="b" class="%s"><constructor><arg value="b"/></constructor></bean>
                </beans>
                """.formatted(Fixtures.FailingClose.class.getName(), Fixtures.FailingClose.class.getName());
        BeanContext context = XmlBeans.load(xml(body));
        BeanException error = expect(BeanException.class, context::close);
        equal(2, error.getSuppressed().length);
        equal(List.of("b.close", "a.close"), Fixtures.EVENTS);
        check(context.isClosed(), "closed despite failures");
    }

    private static void explicitDestroyWins() throws Exception {
        Fixtures.reset();
        String body = "<beans><bean id=\"x\" class=\"%s\" destroy-method=\"stop\"/></beans>"
                .formatted(Fixtures.ExplicitDestroy.class.getName());
        try (BeanContext context = XmlBeans.load(xml(body))) {
            check(context.contains("x"), "explicit destroy bean");
        }
        equal(List.of("explicit.stop"), Fixtures.EVENTS);
    }

    private static void concurrentSingleton() throws Exception {
        Fixtures.ConcurrentSingleton.CONSTRUCTED.set(0);
        String body = "<beans><bean id=\"x\" class=\"%s\" lazy=\"true\"/></beans>"
                .formatted(Fixtures.ConcurrentSingleton.class.getName());
        try (BeanContext context = XmlBeans.load(xml(body))) {
            ExecutorService pool = Executors.newFixedThreadPool(8);
            try {
                List<Callable<Object>> calls = new ArrayList<>();
                for (int i = 0; i < 64; i++) calls.add(() -> context.require("x"));
                List<Future<Object>> futures = pool.invokeAll(calls);
                Object first = futures.get(0).get();
                for (Future<Object> future : futures) check(future.get() == first, "singleton identity");
            } finally {
                pool.shutdownNow();
            }
            equal(1, Fixtures.ConcurrentSingleton.CONSTRUCTED.get());
        }
    }

    private static void constructorWorkerLookup() throws Exception {
        Fixtures.reset();
        Fixtures.ContextBridge bridge = new Fixtures.ContextBridge();
        String body = """
                <beans>
                  <bean id="workerDependency" class="%s" lazy="true"/>
                  <bean id="target" class="%s" lazy="true"><constructor><arg ref="bridge"/></constructor></bean>
                </beans>
                """.formatted(Fixtures.Dependency.class.getName(), Fixtures.ConstructorWorkerLookup.class.getName());
        try (BeanContext context = XmlBeans.builder().bind("bridge", bridge).load(xml(body))) {
            bridge.context(context);
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Object value = pool.submit(() -> context.require("target")).get(8, TimeUnit.SECONDS);
                check(value instanceof Fixtures.ConstructorWorkerLookup, "worker lookup target");
                equal(List.of("dependency.construct", "worker.lookup.complete"), Fixtures.EVENTS);
            } finally {
                pool.shutdownNow();
            }
        }
        equal(List.of("dependency.construct", "worker.lookup.complete", "dependency.close"), Fixtures.EVENTS);
    }

    private static void crossThreadCycle() throws Exception {
        Fixtures.CrossThreadBridge bridge = new Fixtures.CrossThreadBridge();
        String body = """
                <beans>
                  <bean id="a" class="%s" lazy="true"><constructor><arg ref="bridge"/><arg value="b"/></constructor></bean>
                  <bean id="b" class="%s" lazy="true"><constructor><arg ref="bridge"/><arg value="a"/></constructor></bean>
                </beans>
                """.formatted(Fixtures.CrossThreadLookup.class.getName(), Fixtures.CrossThreadLookup.class.getName());
        try (BeanContext context = XmlBeans.builder().bind("bridge", bridge).load(xml(body))) {
            bridge.context(context);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Object> a = pool.submit(() -> context.require("a"));
                Future<Object> b = pool.submit(() -> context.require("b"));
                Throwable first = futureFailure(a);
                Throwable second = futureFailure(b);
                String combined = rootText(first) + " " + rootText(second);
                contains(combined, "Cross-thread circular dependency");
            } finally {
                pool.shutdownNow();
            }
        }
    }

    private static void sameThreadRuntimeCycle() throws Exception {
        Fixtures.ContextBridge bridge = new Fixtures.ContextBridge();
        String body = """
                <beans><bean id="x" class="%s" lazy="true"><constructor>
                  <arg ref="bridge"/><arg value="x"/>
                </constructor></bean></beans>
                """.formatted(Fixtures.ReentrantLookup.class.getName());
        try (BeanContext context = XmlBeans.builder().bind("bridge", bridge).load(xml(body))) {
            bridge.context(context);
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Throwable failure = futureFailure(pool.submit(() -> context.require("x")));
                contains(rootText(failure), "Circular dependency during creation");
            } finally {
                pool.shutdownNow();
            }
        }
    }

    private static void closeWaitsForActiveCreation() throws Exception {
        Fixtures.reset();
        Fixtures.SlowBean.reset();
        String body = """
                <beans><bean id="slow" class="%s" lazy="true"/></beans>
                """
                .formatted(Fixtures.SlowBean.class.getName());
        BeanContext context = XmlBeans.load(xml(body));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> creation = pool.submit(() -> context.require("slow"));
            check(Fixtures.SlowBean.STARTED.await(5, TimeUnit.SECONDS), "slow bean started");
            Future<?> closing = pool.submit(context::close);
            Thread.sleep(100);
            check(!closing.isDone(), "close waits for active creation");
            Fixtures.SlowBean.PROCEED.countDown();
            check(creation.get(5, TimeUnit.SECONDS) instanceof Fixtures.SlowBean, "slow bean created");
            closing.get(5, TimeUnit.SECONDS);
            equal(List.of("slow.construct", "slow.close"), Fixtures.EVENTS);
        } finally {
            Fixtures.SlowBean.PROCEED.countDown();
            pool.shutdownNow();
            context.close();
        }
    }

    private static void deterministicByTypeOrder() throws Exception {
        String body = """
                <beans><bean id="first" class="%s"/><bean id="second" class="%s"/></beans>
                """
                .formatted(Fixtures.MarkerOne.class.getName(), Fixtures.MarkerTwo.class.getName());
        try (BeanContext context = XmlBeans.load(xml(body))) {
            equal(List.of("first", "second"), List.copyOf(context.beansOfType(Fixtures.Marker.class).keySet()));
        }
    }

    private static void classLoaderReleased() throws Exception {
        LeakProbe probe = createClosedPluginContext();
        for (int attempt = 0; attempt < 80 && probe.loader().get() != null; attempt++) {
            System.gc();
            byte[][] pressure = new byte[8][];
            for (int i = 0; i < pressure.length; i++) pressure[i] = new byte[256 * 1024];
            Thread.sleep(25);
        }
        check(probe.context().isClosed(), "retained context remains closed");
        check(probe.loader().get() == null, "closed context retained plugin classloader");
    }

    private static LeakProbe createClosedPluginContext() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        check(compiler != null, "system Java compiler");
        Path root = Files.createTempDirectory("simple-di-plugin-");
        Path source = root.resolve("plugin/UnloadableBean.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package plugin;
                public final class UnloadableBean implements AutoCloseable {
                    public UnloadableBean() {}
                    public void close() {}
                }
                """);
        int result = compiler.run(null, null, null, "-d", root.toString(), source.toString());
        equal(0, result);
        URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, SimpleDiTest.class.getClassLoader());
        WeakReference<ClassLoader> reference = new WeakReference<>(loader);
        String body = """
                <beans><bean id="plugin" class="plugin.UnloadableBean"/></beans>
                """;
        BeanContext context = XmlBeans.builder().classLoader(loader).load(xml(body));
        context.close();
        loader.close();
        return new LeakProbe(context, reference);
    }

    private static void xxeRejected() throws Exception {
        String body = """
                <?xml version="1.0"?>
                <!DOCTYPE beans [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <beans><bean id="x" class="%s"><constructor><arg value="&xxe;"/></constructor></bean></beans>
                """.formatted(Fixtures.StringHolder.class.getName());
        BeanException error = expect(BeanException.class, () -> XmlBeans.load(xml(body)));
        check(error.getMessage().contains("DTD") || error.getMessage().contains("entity")
                || error.getMessage().contains("Invalid XML"), "XXE rejection message");
    }

    private static void xmlLimits() throws Exception {
        String body = """
                <beans><bean id="x" class="%s"><constructor><arg><value>abcdef</value></arg></constructor></bean></beans>
                """.formatted(Fixtures.StringHolder.class.getName());
        XmlLimits depth = new XmlLimits(10, 100, 3, 20, 100, 100);
        BeanException depthError = expect(BeanException.class, () -> XmlBeans.builder().limits(depth).load(xml(body)));
        contains(depthError.getMessage(), "depth limit");
        XmlLimits text = new XmlLimits(10, 100, 20, 20, 5, 100);
        BeanException textError = expect(BeanException.class, () -> XmlBeans.builder().limits(text).load(xml(body)));
        contains(textError.getMessage(), "Text length limit");
    }

    private static void strictDiagnostics() throws Exception {
        String body = """
                <beans>
                  <bean id="x" class="%s" mystery="bad"/>
                </beans>
                """.formatted(Fixtures.Dependency.class.getName());
        BeanException error = expect(BeanException.class, () -> XmlBeans.load(xml(body)));
        contains(error.getMessage(), ":2:");
        contains(error.getMessage(), "Unknown attribute 'mystery'");
    }


    private static void propertyConditions() throws Exception {
        Fixtures.reset();
        String base = """
                <beans version="2">
                  <bean id="base" class="%s" if-property="feature.base" if-value="on">
                    <constructor><arg value="base"/></constructor>
                  </bean>
                  <bean id="fallback" class="%s" unless-property="feature.disabled">
                    <constructor><arg value="fallback"/></constructor>
                  </bean>
                  <bean id="defaulted" class="%s" if-property="feature.missing" match-if-missing="true">
                    <constructor><arg value="defaulted"/></constructor>
                  </bean>
                </beans>
                """.formatted(Fixtures.EventBean.class.getName(), Fixtures.EventBean.class.getName(),
                Fixtures.EventBean.class.getName());
        String overlay = """
                <beans version="2">
                  <bean id="base" class="%s" replaces="base" if-property="profile" if-value="dev">
                    <constructor><arg value="overlay"/></constructor>
                  </bean>
                </beans>
                """.formatted(Fixtures.EventBean.class.getName());
        Path baseFile = xml(base);
        Path overlayFile = xml(overlay);
        ValidationResult report = XmlBeans.builder().property("feature.base", "on")
                .secret("profile", "prod").overlay(overlayFile).inspect(baseFile);
        check(report.valid(), "conditional graph valid");
        equal(Set.of("base", "fallback", "defaulted"), report.beanNames());
        equal(4, report.conditions().size());
        check(report.conditions().stream().anyMatch(v -> v.beanId().equals("base") && !v.active()
                && v.sensitive()), "sensitive overlay condition redacted metadata");
        check(report.toString().indexOf("prod") < 0, "condition report does not expose secret value");
        try (BeanContext context = XmlBeans.builder().property("feature.base", "on")
                .property("profile", "prod").overlay(overlayFile).load(baseFile)) {
            context.require("base");
            context.require("fallback");
            context.require("defaulted");
        }
        check(Fixtures.EVENTS.contains("base.construct"), "base selected");
        check(!Fixtures.EVENTS.contains("overlay.construct"), "inactive overlay skipped");

        Fixtures.reset();
        try (BeanContext context = XmlBeans.builder().property("feature.base", "on")
                .property("profile", "dev").overlay(overlayFile).load(baseFile)) {
            context.require("base");
        }
        check(Fixtures.EVENTS.contains("overlay.construct"), "active overlay selected");
    }

    private static void inactiveConditionsSkipClassLoading() throws Exception {
        String body = """
                <beans version="2">
                  <bean id="missing" class="does.not.exist.MissingType" if-property="feature.enabled"/>
                  <alias name="missing" alias="missingAlias"/>
                  <bean id="empty" class="%s" if-property="empty.value" if-value=""/>
                </beans>
                """.formatted(Fixtures.Dependency.class.getName());
        ValidationResult inactive = XmlBeans.builder().withoutDefaultPropertySources()
                .property("empty.value", "").inspectXml(body, "conditions.xml");
        check(inactive.valid(), "inactive missing class does not load");
        equal(Set.of("empty"), inactive.beanNames());
        equal(Set.of(), inactive.aliases());
        contains(inactive.explain("missing"), "inactive");

        ValidationResult active = XmlBeans.builder().withoutDefaultPropertySources()
                .property("feature.enabled", "true").property("empty.value", "")
                .inspectXml(body, "conditions-active.xml");
        check(!active.valid(), "active missing class is validated");
        check(active.problems().stream().anyMatch(problem -> problem.code()
                == io.github.simpledi.ConfigurationProblem.Code.TYPE), "missing class type problem");
    }

    private static void invalidConditionCombinations() throws Exception {
        String className = Fixtures.Dependency.class.getName();
        String both = "<beans version=\"2\"><bean id=\"x\" class=\"" + className
                + "\" if-property=\"a\" unless-property=\"b\"/></beans>";
        contains(expect(BeanException.class, () -> XmlBeans.loadXml(both, "both.xml")).getMessage(),
                "both if-property and unless-property");

        String redundantMissing = "<beans version=\"2\"><bean id=\"x\" class=\"" + className
                + "\" unless-property=\"a\" match-if-missing=\"true\"/></beans>";
        contains(expect(BeanException.class,
                () -> XmlBeans.loadXml(redundantMissing, "unless-missing.xml")).getMessage(),
                "valid only with if-property");
    }

    private static void customScopeLifecycle() throws Exception {
        Fixtures.reset();
        AtomicReference<String> key = new AtomicReference<>("request-1");
        BeanScopes.Keyed scope = BeanScopes.keyed(key::get);
        String body = """
                <beans version="2">
                  <bean id="singleton" class="%s"><constructor><arg value="singleton"/></constructor></bean>
                  <bean id="requestBean" class="%s" scope="request">
                    <constructor><arg value="request"/></constructor>
                  </bean>
                </beans>
                """.formatted(Fixtures.EventBean.class.getName(), Fixtures.EventBean.class.getName());
        BeanContext context = XmlBeans.builder().scope("request", scope).load(xml(body));
        Object first = context.require("requestBean");
        check(context.require("requestBean") == first, "same scope identity");
        key.set("request-2");
        Object second = context.require("requestBean");
        check(second != first, "different scope identity");
        equal(2, scope.activeKeys());
        scope.release("request-1");
        equal(1, scope.activeKeys());
        equal(1L, Fixtures.EVENTS.stream().filter("request.close"::equals).count());
        context.close();
        equal(2L, Fixtures.EVENTS.stream().filter("request.close"::equals).count());
        equal(List.of("request.close", "singleton.close"),
                Fixtures.EVENTS.subList(Fixtures.EVENTS.size() - 2, Fixtures.EVENTS.size()));
    }

    private static void customScopeRollback() throws Exception {
        Fixtures.reset();
        Fixtures.FlakyLazy.ATTEMPTS.set(0);
        AtomicReference<String> key = new AtomicReference<>("request");
        BeanScopes.Keyed scope = BeanScopes.keyed(key::get);
        String body = """
                <beans version="2">
                  <bean id="dep" class="%s" scope="request"/>
                  <bean id="flaky" class="%s" scope="request"><constructor><arg ref="dep"/></constructor></bean>
                </beans>
                """.formatted(Fixtures.RetryDependency.class.getName(), Fixtures.FlakyLazy.class.getName());
        try (BeanContext context = XmlBeans.builder().scope("request", scope).load(xml(body))) {
            expect(BeanException.class, () -> context.require("flaky"));
            equal(0, scope.activeKeys());
            equal(List.of("retry.dep.construct", "flaky.attempt", "retry.dep.close"), Fixtures.EVENTS);
            context.require("flaky");
            equal(1, scope.activeKeys());
        }
        equal(List.of("retry.dep.construct", "flaky.attempt", "retry.dep.close",
                "retry.dep.construct", "flaky.attempt", "flaky.construct", "flaky.close", "retry.dep.close"),
                Fixtures.EVENTS);
    }

    private static void customScopeConcurrency() throws Exception {
        Fixtures.ConcurrentSingleton.CONSTRUCTED.set(0);
        BeanScopes.Keyed scope = BeanScopes.keyed(() -> "same");
        String body = "<beans version=\"2\"><bean id=\"x\" class=\"%s\" scope=\"request\"/></beans>"
                .formatted(Fixtures.ConcurrentSingleton.class.getName());
        try (BeanContext context = XmlBeans.builder().scope("request", scope).load(xml(body))) {
            ExecutorService pool = Executors.newFixedThreadPool(8);
            try {
                List<Callable<Object>> calls = new ArrayList<>();
                for (int i = 0; i < 64; i++) calls.add(() -> context.require("x"));
                List<Future<Object>> futures = pool.invokeAll(calls);
                Object first = futures.get(0).get();
                for (Future<Object> future : futures) check(future.get() == first, "custom scope identity");
            } finally {
                pool.shutdownNow();
            }
            equal(1, Fixtures.ConcurrentSingleton.CONSTRUCTED.get());
        }
    }

    private static void customScopeCrossThreadCycle() throws Exception {
        Fixtures.CrossThreadBridge bridge = new Fixtures.CrossThreadBridge();
        BeanScopes.Keyed scope = BeanScopes.keyed(() -> "same-request");
        String body = """
                <beans version="2">
                  <bean id="left" class="%s" scope="request">
                    <constructor><arg ref="bridge"/><arg value="right"/></constructor>
                  </bean>
                  <bean id="right" class="%s" scope="request">
                    <constructor><arg ref="bridge"/><arg value="left"/></constructor>
                  </bean>
                </beans>
                """.formatted(Fixtures.CrossThreadLookup.class.getName(), Fixtures.CrossThreadLookup.class.getName());
        try (BeanContext context = XmlBeans.builder().bind("bridge", bridge).scope("request", scope)
                .loadXml(body, "scope-cycle.xml")) {
            bridge.context(context);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<?> left = pool.submit(() -> context.require("left"));
                Future<?> right = pool.submit(() -> context.require("right"));
                String failures = throwableTreeText(futureFailure(left)) + throwableTreeText(futureFailure(right));
                contains(failures, "custom-scope dependency cycle");
            } finally {
                pool.shutdownNow();
            }
        }
        equal(0, scope.activeKeys());
    }

    private static void customScopeShutdownFailure() throws Exception {
        Fixtures.reset();
        BeanScope failingScope = new BeanScope() {
            @Override public Reservation reserve(String beanId) {
                throw new AssertionError("unused scope was unexpectedly entered");
            }
            @Override public void close() { throw new IllegalStateException("scope-close-boom"); }
        };
        String body = "<beans version=\"2\"><bean id=\"x\" class=\"%s\"><constructor>"
                + "<arg value=\"x\"/></constructor></bean></beans>";
        BeanContext context = XmlBeans.builder().scope("request", failingScope)
                .loadXml(body.formatted(Fixtures.EventBean.class.getName()), "scope-close.xml");
        BeanException failure = expect(BeanException.class, context::close);
        contains(throwableTreeText(failure), "scope-close-boom");
        equal(List.of("x.construct", "x.close"), List.copyOf(Fixtures.EVENTS));
        check(context.isClosed(), "context closes despite scope failure");
    }

    private static void unknownCustomScope() throws Exception {
        String body = "<beans version=\"2\"><bean id=\"x\" class=\"%s\" scope=\"request\"/></beans>"
                .formatted(Fixtures.Dependency.class.getName());
        BeanException error = expect(BeanException.class, () -> XmlBeans.load(xml(body)));
        contains(error.getMessage(), "No BeanScope registered");
    }

    private static void scopeEscapeRejected() throws Exception {
        String custom = """
                <beans version="2">
                  <bean id="request" class="%s" scope="request"><constructor><arg value="r"/></constructor></bean>
                  <bean id="consumer" class="%s"><constructor><arg ref="request"/></constructor></bean>
                </beans>
                """.formatted(Fixtures.EventBean.class.getName(), Fixtures.EventBean.class.getName());
        BeanException customError = expect(BeanException.class,
                () -> XmlBeans.builder().scope("request", BeanScopes.keyed(() -> "x")).load(xml(custom)));
        contains(customError.getMessage(), "Scope violation");

        String prototype = """
                <beans version="2">
                  <bean id="repo" class="%s" scope="prototype"><constructor><arg value="r"/></constructor></bean>
                  <bean id="consumer" class="%s"><constructor><arg ref="repo"/></constructor></bean>
                </beans>
                """.formatted(Fixtures.Repo.class.getName(), Fixtures.ParentConsumer.class.getName());
        BeanException prototypeError = expect(BeanException.class, () -> XmlBeans.load(xml(prototype)));
        contains(prototypeError.getMessage(), "caller-owned prototype");
    }

    private static void lifecycleInterceptors() throws Exception {
        Fixtures.reset();
        List<String> phases = new ArrayList<>();
        BeanLifecycleInterceptor interceptor = new BeanLifecycleInterceptor() {
            @Override public void afterConstruction(BeanLifecycleContext context, Object bean) {
                phases.add("constructed:" + context.beanId());
            }
            @Override public void beforeInitialization(BeanLifecycleContext context, Object bean) {
                phases.add("before-init:" + context.beanId());
            }
            @Override public void afterInitialization(BeanLifecycleContext context, Object bean) {
                phases.add("after-init:" + context.beanId());
            }
            @Override public void beforeDestruction(BeanLifecycleContext context, Object bean) {
                phases.add("before-destroy:" + context.beanId());
            }
            @Override public void afterDestruction(BeanLifecycleContext context, Object bean, Throwable failure) {
                phases.add("after-destroy:" + context.beanId() + ":" + (failure == null));
            }
        };
        String body = "<beans version=\"2\"><bean id=\"x\" class=\"%s\"><constructor><arg value=\"x\"/></constructor></bean></beans>"
                .formatted(Fixtures.EventBean.class.getName());
        try (BeanContext context = XmlBeans.builder().lifecycleInterceptor(interceptor).load(xml(body))) {
            check(context.contains("x"), "intercepted bean present");
            equal(List.of("constructed:x", "before-init:x", "after-init:x"), phases);
        }
        equal(List.of("constructed:x", "before-init:x", "after-init:x", "before-destroy:x",
                "after-destroy:x:true"), phases);
    }

    private static void prototypeLifecycleInterceptors() throws Exception {
        Fixtures.reset();
        List<String> phases = new ArrayList<>();
        BeanLifecycleInterceptor interceptor = new BeanLifecycleInterceptor() {
            @Override public void afterConstruction(BeanLifecycleContext context, Object bean) {
                phases.add("construct:" + context.beanId() + ":" + context.scope());
            }
            @Override public void beforeDestruction(BeanLifecycleContext context, Object bean) {
                phases.add("before-destroy:" + context.beanId());
            }
            @Override public void afterDestruction(BeanLifecycleContext context, Object bean, Throwable failure) {
                phases.add("after-destroy:" + context.beanId() + ":" + (failure == null));
            }
        };
        String body = "<beans version=\"2\"><bean id=\"job\" class=\"%s\" scope=\"prototype\">"
                + "<constructor><arg value=\"job\"/></constructor></bean></beans>";
        try (BeanContext context = XmlBeans.builder().lifecycleInterceptor(interceptor)
                .loadXml(body.formatted(Fixtures.EventBean.class.getName()), "prototype-interceptor.xml")) {
            try (BeanHandle<Fixtures.EventBean> handle = context.create("job", Fixtures.EventBean.class)) {
                check(handle.value() != null, "prototype handle value");
                equal(List.of("construct:job:prototype"), phases);
            }
            equal(List.of("construct:job:prototype", "before-destroy:job", "after-destroy:job:true"), phases);
        }
        equal(List.of("job.construct", "job.close"), List.copyOf(Fixtures.EVENTS));
    }

    private static void interceptorRollback() throws Exception {
        Fixtures.reset();
        BeanLifecycleInterceptor interceptor = new BeanLifecycleInterceptor() {
            @Override public void afterConstruction(BeanLifecycleContext context, Object bean) {
                throw new IllegalStateException("interceptor-boom");
            }
        };
        String body = "<beans version=\"2\"><bean id=\"x\" class=\"%s\"><constructor><arg value=\"x\"/></constructor></bean></beans>"
                .formatted(Fixtures.EventBean.class.getName());
        BeanException error = expect(BeanException.class,
                () -> XmlBeans.builder().lifecycleInterceptor(interceptor).load(xml(body)));
        contains(error.getMessage(), "interceptor-boom");
        equal(List.of("x.construct", "x.close"), Fixtures.EVENTS);
    }

    private static void configurationDiffAndGraph() throws Exception {
        String first = """
                <beans version="2">
                  <bean id="repo" class="%s"><constructor><arg value="a"/></constructor></bean>
                  <bean id="consumer" class="%s"><constructor><arg ref="repo"/></constructor></bean>
                  <alias name="repo" alias="current"/>
                  <bean id="conditional" class="%s" if-property="feature" if-value="on"/>
                </beans>
                """.formatted(Fixtures.Repo.class.getName(), Fixtures.ParentConsumer.class.getName(),
                Fixtures.Dependency.class.getName());
        String second = """
                <beans version="2">
                  <bean id="repo" class="%s"><constructor><arg value="a"/><arg value="1"/></constructor></bean>
                  <bean id="extra" class="%s"/>
                  <alias name="extra" alias="current"/>
                  <bean id="conditional" class="%s" if-property="feature" if-value="on"/>
                </beans>
                """.formatted(Fixtures.Endpoint.class.getName(), Fixtures.Dependency.class.getName(),
                Fixtures.Dependency.class.getName());
        ValidationResult before = XmlBeans.builder().property("feature", "off").inspectXml(first, "before.xml");
        ValidationResult after = XmlBeans.builder().property("feature", "on").inspectXml(second, "after.xml");
        check(before.valid(), "before graph valid");
        check(after.valid(), "after graph valid");
        ConfigurationDiff diff = before.diff(after);
        equal(Set.of("extra", "conditional"), diff.addedBeans());
        equal(Set.of("consumer"), diff.removedBeans());
        check(diff.changedBeans().containsKey("repo"), "repo changed");
        equal("repo", diff.changedAliases().get("current").beforeTarget());
        equal("extra", diff.changedAliases().get("current").afterTarget());
        check(diff.changedConditions().contains("conditional"), "condition state changed");
        contains(before.toDot(), "repo");
        contains(before.toDot(), "alias");
        contains(before.explain("current"), "alias for repo");
        contains(before.toDot(), "consumer");
        contains(before.explain("consumer"), "depends on: repo");
    }

    private static void contextSnapshot() throws Exception {
        String body = """
                <beans version="2">
                  <bean id="eager" class="%s"/>
                  <bean id="lazy" class="%s" lazy="true"/>
                </beans>
                """.formatted(Fixtures.Dependency.class.getName(), Fixtures.LazyProbe.class.getName());
        BeanContext context = XmlBeans.load(xml(body));
        ContextSnapshot initial = context.snapshot();
        equal("open", initial.state());
        equal(List.of("eager"), initial.createdSingletons());
        context.require("lazy");
        equal(List.of("eager", "lazy"), context.snapshot().createdSingletons());
        context.close();
        equal("closed", context.snapshot().state());
    }

    private static void closedContext() throws Exception {
        String body = "<beans><bean id=\"x\" class=\"%s\"/></beans>"
                .formatted(Fixtures.Dependency.class.getName());
        BeanContext context = XmlBeans.load(xml(body));
        context.close();
        check(context.isClosed(), "closed state");
        expect(IllegalStateException.class, () -> context.require("x"));
    }

    private static void propertySourcePrecedence() throws Exception {
        String body = "<beans><bean id=\"repo\" class=\"%s\"><constructor><arg value=\"${repo.name}\"/></constructor></bean></beans>"
                .formatted(Fixtures.Repo.class.getName());
        PropertySource low = PropertySource.of("low", Map.of("repo.name", "low"));
        PropertySource high = PropertySource.of("high", Map.of("repo.name", "high"));
        XmlBeans.Builder builder = XmlBeans.builder().withoutDefaultPropertySources()
                .propertySource(low).propertySource(high).property("repo.name", "direct");
        ValidationResult report = builder.inspect(xml(body));
        check(report.valid(), "property report valid");
        equal(1, report.properties().size());
        equal("builder", report.properties().get(0).selectedSource());
        equal(List.of("high", "low"), report.properties().get(0).shadowedSources());
        try (BeanContext context = builder.load(xml(body))) {
            equal("direct", context.require("repo", Fixtures.Repo.class).name());
        }

        Path properties = Files.createTempFile("simple-di-", ".properties");
        Files.writeString(properties, "repo.name=file\n", StandardCharsets.ISO_8859_1);
        try (BeanContext context = XmlBeans.builder().withoutDefaultPropertySources()
                .propertiesFile(properties).load(xml(body))) {
            equal("file", context.require("repo", Fixtures.Repo.class).name());
        }
    }

    private static void sensitivePropertyRedaction() throws Exception {
        String body = "<beans><bean id=\"probe\" class=\"%s\"><constructor><arg value=\"${database.password}\"/></constructor></bean></beans>"
                .formatted(Fixtures.IntProbe.class.getName());
        BeanException failure = expect(BeanException.class, () -> XmlBeans.builder()
                .withoutDefaultPropertySources().secret("database.password", "super-secret-value")
                .load(xml(body)));
        contains(failure.getMessage(), "<redacted>");
        check(!failure.getMessage().contains("super-secret-value"), "secret not leaked");
    }

    private static void explicitOverlays() throws Exception {
        String base = "<beans><bean id=\"repo\" class=\"%s\"><constructor><arg value=\"base\"/></constructor></bean></beans>"
                .formatted(Fixtures.Repo.class.getName());
        String overlay = "<beans><bean id=\"repo\" replaces=\"repo\" class=\"%s\"><constructor><arg value=\"overlay\"/></constructor></bean></beans>"
                .formatted(Fixtures.Repo.class.getName());
        try (BeanContext context = XmlBeans.builder().overlay(xml(overlay)).load(xml(base))) {
            equal("overlay", context.require("repo", Fixtures.Repo.class).name());
            equal(List.of("repo"), List.copyOf(context.beanNames()));
        }
        String accidental = "<beans><bean id=\"repo\" class=\"%s\"/></beans>"
                .formatted(Fixtures.Repo.class.getName());
        BeanException failure = expect(BeanException.class, () -> XmlBeans.builder().overlay(xml(accidental)).load(xml(base)));
        contains(failure.getMessage(), "declare replaces");
    }

    private static void typedGenericBinding() throws Exception {
        Fixtures.Box<String> box = new Fixtures.StringBox("typed");
        String consumer = "<beans><bean id=\"consumer\" class=\"%s\"><constructor><arg ref=\"box\"/></constructor></bean></beans>"
                .formatted(Fixtures.StringBoxConsumer.class.getName());
        try (BeanContext context = XmlBeans.builder()
                .bind("box", new TypeRef<Fixtures.Box<String>>() {}, box).load(xml(consumer))) {
            equal("typed", context.require("consumer", Fixtures.StringBoxConsumer.class).box().value());
            equal(new TypeRef<Fixtures.Box<String>>() {}.type(), context.beanType("box"));
        }
        String mismatch = "<beans><bean id=\"consumer\" class=\"%s\"><constructor><arg ref=\"box\"/></constructor></bean></beans>"
                .formatted(Fixtures.IntegerBoxConsumer.class.getName());
        BeanException failure = expect(BeanException.class, () -> XmlBeans.builder()
                .bind("box", new TypeRef<Fixtures.Box<String>>() {}, box).load(xml(mismatch)));
        contains(failure.getMessage(), "No compatible constructor");
    }

    private static void inheritedGenericSetter() throws Exception {
        String body = "<beans><bean id=\"holder\" class=\"%s\"><property name=\"value\" value=\"resolved\"/></bean></beans>"
                .formatted(Fixtures.StringSetter.class.getName());
        try (BeanContext context = XmlBeans.load(xml(body))) {
            equal("resolved", context.require("holder", Fixtures.StringSetter.class).value());
        }
    }

    private static void genericInstanceFactory() throws Exception {
        Fixtures.GenericFactory<Fixtures.Product> factory = new Fixtures.ProductGenericFactory();
        String body = "<beans><bean id=\"product\" class=\"%s\"><factory bean=\"factory\" method=\"create\"/></bean></beans>"
                .formatted(Fixtures.Product.class.getName());
        try (BeanContext context = XmlBeans.builder()
                .bind("factory", new TypeRef<Fixtures.GenericFactory<Fixtures.Product>>() {}, factory)
                .load(xml(body))) {
            equal(new Fixtures.Product("generic", 11), context.require("product", Fixtures.Product.class));
        }
    }

    private static void genericByTypeLookup() throws Exception {
        String body = "<beans><bean id=\"box\" class=\"%s\"><constructor><arg value=\"value\"/></constructor></bean></beans>"
                .formatted(Fixtures.StringBox.class.getName());
        try (BeanContext context = XmlBeans.load(xml(body))) {
            Fixtures.Box<String> value = context.require(new TypeRef<Fixtures.Box<String>>() {});
            equal("value", value.value());
            check(context.find(new TypeRef<Fixtures.Box<Integer>>() {}).isEmpty(), "generic mismatch absent");
        }
    }

    private static void parentChildContexts() throws Exception {
        String parentXml = "<beans><bean id=\"repo\" class=\"%s\"><constructor><arg value=\"parent\"/></constructor></bean></beans>"
                .formatted(Fixtures.Repo.class.getName());
        String childXml = "<beans><bean id=\"consumer\" class=\"%s\"><constructor><arg ref=\"parentRepo\"/></constructor></bean></beans>"
                .formatted(Fixtures.ParentConsumer.class.getName());
        BeanContext parent = XmlBeans.load(xml(parentXml));
        BeanContext child = XmlBeans.builder().parent(parent).importBean("repo", "parentRepo").load(xml(childXml));
        try {
            equal("parent", child.require("consumer", Fixtures.ParentConsumer.class).repo().name());
            check(child.require("parentRepo") == parent.require("repo"), "parent identity imported");
            IllegalStateException failure = expect(IllegalStateException.class, parent::close);
            contains(failure.getMessage(), "child context");
        } finally {
            child.close();
            parent.close();
        }
        check(parent.isClosed(), "parent closes after child");
    }

    private static void validationReportFailures() throws Exception {
        String body = """
                <beans>
                  <bean id="badNumber" class="%s"><constructor><arg value="not-int"/></constructor></bean>
                  <bean id="badSetter" class="%s"><property name="missing" value="x"/></bean>
                </beans>
                """.formatted(Fixtures.IntProbe.class.getName(), Fixtures.StringSetter.class.getName());
        ValidationResult report = XmlBeans.builder().inspect(xml(body));
        check(!report.valid(), "invalid report");
        check(report.problems().size() >= 2, "independent failures collected: " + report.problems());
        check(report.problems().stream().anyMatch(p -> p.code() == io.github.simpledi.ConfigurationProblem.Code.EXECUTABLE),
                "executable problem classified");
        BeanException failure = expect(BeanException.class, () -> XmlBeans.validate(xml(body)));
        check(failure.location() != null, "validate preserves location");
    }


    private static void validationReportGraph() throws Exception {
        String body = """
                <beans>
                  <bean id="repo" class="%s"><constructor><arg value="${repo.name:fallback}"/></constructor></bean>
                  <bean id="consumer" class="%s"><constructor><arg ref="repo"/></constructor></bean>
                </beans>
                """.formatted(Fixtures.Repo.class.getName(), Fixtures.ParentConsumer.class.getName());
        ValidationResult report = XmlBeans.builder().withoutDefaultPropertySources()
                .propertiesResource("simpledi/application.properties").inspect(xml(body));
        check(report.valid(), "valid graph report: " + report.problems());
        equal(List.of("repo", "consumer"), List.copyOf(report.beans().keySet()));
        check(report.dependencies().stream().anyMatch(edge -> edge.sourceBean().equals("consumer")
                && edge.targetBean().equals("repo") && !edge.lazy()), "eager dependency described");
        equal(List.of("repo", "consumer"), report.creationOrder());
        equal(List.of("consumer", "repo"), report.destructionOrder());
        equal("classpath:simpledi/application.properties", report.properties().get(0).selectedSource());
        check(report.beans().get("consumer").creator().contains("ParentConsumer"), "selected creator reported");
    }


    private static void exactSignatures() throws Exception {
        String body = """
                <beans version="2"><bean id="value" class="%s">
                  <constructor signature="(long)"><arg value="7"/></constructor>
                </bean></beans>
                """.formatted(Fixtures.Ambiguous.class.getName());
        String factoryAndCall = """
                <beans version="2">
                  <bean id="product" class="%s">
                    <factory class="%s" method="create" signature="(java.lang.String,int)">
                      <arg value="widget"/><arg value="3"/>
                    </factory>
                  </bean>
                  <bean id="text" class="java.lang.StringBuilder">
                    <constructor signature="()"/>
                    <call method="append" signature="(java.lang.String)"><arg value="hello"/></call>
                  </bean>
                  <bean id="varargs" class="%s">
                    <constructor signature="(java.lang.String,int...)">
                      <arg value="p"/><arg value="1"/><arg value="2"/>
                    </constructor>
                  </bean>
                </beans>
                """.formatted(Fixtures.Product.class.getName(), Fixtures.ProductFactory.class.getName(),
                Fixtures.VarargsTarget.class.getName());
        try (BeanContext context = XmlBeans.builder().overlay(xml(factoryAndCall)).load(xml(body))) {
            equal("long:7", context.require("value", Fixtures.Ambiguous.class).selected());
            equal(new Fixtures.Product("widget", 3), context.require("product", Fixtures.Product.class));
            equal("hello", context.require("text").toString());
            check(java.util.Arrays.equals(new int[] {1, 2},
                    context.require("varargs", Fixtures.VarargsTarget.class).values()), "exact varargs signature");
        }
        String bad = """
                <beans version="2"><bean id="value" class="%s">
                  <constructor signature="(java.lang.String)"><arg value="7"/></constructor>
                </bean></beans>
                """.formatted(Fixtures.Ambiguous.class.getName());
        List<BeanEvent.Kind> failureEvents = new ArrayList<>();
        BeanException failure = expect(BeanException.class, () -> XmlBeans.builder()
                .listener(event -> failureEvents.add(event.kind())).load(xml(bad)));
        contains(rootText(failure), "has exact signature");
        check(failureEvents.contains(BeanEvent.Kind.GRAPH_FAILED), "graph failure event emitted");
    }

    private static void namedAndIndexedArguments() throws Exception {
        String body = """
                <beans version="2">
                  <bean id="named" class="%s"><constructor>
                    <arg name="port" value="8080"/><arg name="host" value="localhost"/>
                  </constructor></bean>
                  <bean id="indexed" class="%s"><constructor>
                    <arg index="1" value="9090"/><arg index="0" value="example.test"/>
                  </constructor></bean>
                </beans>
                """.formatted(Fixtures.Config.class.getName(), Fixtures.Config.class.getName());
        try (BeanContext context = XmlBeans.load(xml(body))) {
            equal(new Fixtures.Config("localhost", 8080), context.require("named", Fixtures.Config.class));
            equal(new Fixtures.Config("example.test", 9090), context.require("indexed", Fixtures.Config.class));
        }
        String unavailable = """
                <beans version="2"><bean id="product" class="%s">
                  <factory class="%s" method="create">
                    <arg name="name" value="x"/><arg name="count" value="1"/>
                  </factory>
                </bean></beans>
                """.formatted(Fixtures.Product.class.getName(), Fixtures.ProductFactory.class.getName());
        BeanException failure = expect(BeanException.class, () -> XmlBeans.loadXml(unavailable, "names.xml"));
        contains(rootText(failure), "Compile the target with -parameters");
    }

    private static void versionedGrammar() throws Exception {
        String current = "<beans version=\"2\"><bean id=\"x\" class=\"java.lang.StringBuilder\"/></beans>";
        try (BeanContext ignored = XmlBeans.loadXml(current, "current.xml")) {
            check(!ignored.isClosed(), "current grammar loaded");
        }
        String legacy = "<beans><bean id=\"x\" class=\"java.lang.StringBuilder\"/></beans>";
        try (BeanContext ignored = XmlBeans.loadXml(legacy, "legacy.xml")) {
            check(!ignored.isClosed(), "unversioned grammar remains compatible");
        }
        BeanException failure = expect(BeanException.class,
                () -> XmlBeans.loadXml("<beans version=\"3\"/>", "future.xml"));
        contains(rootText(failure), "Unsupported simple-di XML version");
        List<BeanEvent.Kind> parseEvents = new ArrayList<>();
        ValidationResult report = XmlBeans.builder().listener(event -> parseEvents.add(event.kind()))
                .inspectXml("<beans version=\"3\"/>", "future-inspect.xml");
        check(!report.valid(), "future grammar inspection invalid");
        check(parseEvents.contains(BeanEvent.Kind.CONFIG_FAILED), "configuration failure event emitted");
    }

    private static void inlineInputs() throws Exception {
        String body = "<beans version=\"2\"><bean id=\"x\" class=\"java.lang.StringBuilder\"/></beans>";
        final class TrackingInput extends ByteArrayInputStream {
            private boolean closed;
            private TrackingInput(byte[] bytes) { super(bytes); }
            @Override public void close() { closed = true; }
        }
        final class TrackingReader extends StringReader {
            private boolean closed;
            private TrackingReader(String value) { super(value); }
            @Override public void close() { closed = true; }
        }
        TrackingInput input = new TrackingInput(body.getBytes(StandardCharsets.UTF_8));
        try (BeanContext context = XmlBeans.load(input, "stream.xml")) {
            check(context.require("x") instanceof StringBuilder, "stream input loaded");
        }
        check(!input.closed, "caller stream remains open");

        TrackingReader reader = new TrackingReader(body);
        try (BeanContext context = XmlBeans.load(reader, "reader.xml")) {
            check(context.require("x") instanceof StringBuilder, "reader input loaded");
        }
        check(!reader.closed, "caller reader remains open");

        try (BeanContext context = XmlBeans.loadXml(body, "inline.xml")) {
            check(context.require("x") instanceof StringBuilder, "string input loaded");
        }
        check(XmlBeans.validateXml(body, "validate-string.xml").valid(), "string validation");
        check(XmlBeans.validate(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)),
                "validate-stream.xml").valid(), "stream validation");
        check(XmlBeans.validate(new StringReader(body), "validate-reader.xml").valid(), "reader validation");
    }

    private static void includeSandbox() throws Exception {
        Path base = Files.createTempDirectory("simple-di-sandbox-");
        Path allowed = Files.createDirectories(base.resolve("allowed"));
        Files.writeString(base.resolve("outside.xml"),
                "<beans version=\"2\"><bean id=\"x\" class=\"java.lang.StringBuilder\"/></beans>");
        Path main = allowed.resolve("main.xml");
        Files.writeString(main, "<beans version=\"2\"><include file=\"../outside.xml\"/></beans>");
        BeanException failure = expect(BeanException.class, () -> XmlBeans.load(main));
        contains(rootText(failure), "escapes include root");
        try (BeanContext context = XmlBeans.builder().fileIncludeRoot(base).load(main)) {
            check(context.require("x") instanceof StringBuilder, "explicit sandbox permits include");
        }
        BeanException disabled = expect(BeanException.class,
                () -> XmlBeans.builder().withoutFileIncludes().load(main));
        contains(rootText(disabled), "File includes are disabled");

        Path separate = Files.createTempFile("simple-di-outside-", ".xml");
        Files.writeString(separate,
                "<beans version=\"2\"><bean id=\"y\" class=\"java.lang.StringBuilder\"/></beans>");
        Path symlink = allowed.resolve("linked.xml");
        Files.createSymbolicLink(symlink, separate);
        Path symlinkRoot = allowed.resolve("symlink-root.xml");
        Files.writeString(symlinkRoot, "<beans version=\"2\"><include file=\"linked.xml\"/></beans>");
        BeanException symlinkFailure = expect(BeanException.class, () -> XmlBeans.load(symlinkRoot));
        contains(rootText(symlinkFailure), "escapes include root");

        BeanException classpathEscape = expect(BeanException.class,
                () -> XmlBeans.loadResource("simpledi/sandbox/root.xml"));
        contains(rootText(classpathEscape), "escapes include root");
        BeanException classpathDisabled = expect(BeanException.class,
                () -> XmlBeans.builder().withoutClasspathIncludes().loadResource("simpledi/root.xml"));
        contains(rootText(classpathDisabled), "Classpath includes are disabled");
    }

    private static void aggregateInputLimits() throws Exception {
        Path root = Files.createTempDirectory("simple-di-limits-");
        String padding = " ".repeat(180);
        Files.writeString(root.resolve("a.xml"),
                "<beans version=\"2\"><!--" + padding + "--></beans>");
        Files.writeString(root.resolve("b.xml"),
                "<beans version=\"2\"><!--" + padding + "--></beans>");
        Path main = root.resolve("main.xml");
        Files.writeString(main,
                "<beans version=\"2\"><include file=\"a.xml\"/><include file=\"b.xml\"/></beans>");
        XmlLimits aggregate = new XmlLimits(10, 1000, 50, 20, 1000, 100,
                300, 350, 1000);
        BeanException aggregateFailure = expect(BeanException.class,
                () -> XmlBeans.builder().limits(aggregate).load(main));
        contains(rootText(aggregateFailure), "Aggregate XML byte limit");

        XmlLimits miscellaneous = new XmlLimits(10, 1000, 50, 20, 1000, 100,
                1000, 1000, 20);
        ValidationResult report = XmlBeans.builder().limits(miscellaneous)
                .inspectXml("<beans version=\"2\"><!--" + padding + "--></beans>", "misc.xml");
        check(!report.valid(), "miscellaneous text limit reported");
        check(report.problems().stream().anyMatch(problem -> problem.message().contains("comment/whitespace")),
                "miscellaneous text diagnostic");

        Path overlayBase = root.resolve("overlay-base.xml");
        Path overlay = root.resolve("overlay.xml");
        Files.writeString(overlayBase, "<beans version=\"2\"><!--" + " ".repeat(120) + "--></beans>");
        Files.writeString(overlay, "<beans version=\"2\"><!--" + " ".repeat(120) + "--></beans>");
        XmlLimits overlayAggregate = new XmlLimits(10, 1000, 50, 20, 1000, 100,
                300, 300, 1000);
        BeanException overlayFailure = expect(BeanException.class, () -> XmlBeans.builder()
                .limits(overlayAggregate).overlay(overlay).load(overlayBase));
        contains(rootText(overlayFailure), "Aggregate XML byte limit");

        Path beanBase = root.resolve("bean-base.xml");
        Path beanOverlay = root.resolve("bean-overlay.xml");
        Files.writeString(beanBase,
                "<beans version=\"2\"><bean id=\"one\" class=\"java.lang.StringBuilder\"/></beans>");
        Files.writeString(beanOverlay,
                "<beans version=\"2\"><bean id=\"two\" class=\"java.lang.StringBuilder\"/></beans>");
        BeanException beanLimitFailure = expect(BeanException.class, () -> XmlBeans.builder()
                .limits(new XmlLimits(10, 1000, 50, 20, 1000, 1))
                .overlay(beanOverlay).load(beanBase));
        contains(rootText(beanLimitFailure), "Merged bean count exceeds bean limit");
    }

    private static void parserFuzzSmoke() throws Exception {
        String seed = "<beans version=\"2\"><bean id=\"x\" class=\"java.lang.StringBuilder\"><call method=\"append\"><arg value=\"value\"/></call></bean></beans>";
        Random random = new Random(0x51A2D1L);
        XmlLimits limits = new XmlLimits(8, 2_000, 64, 32, 4_096, 256,
                16_384, 32_768, 4_096);
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "simple-di-parser-fuzz");
            thread.setDaemon(true);
            return thread;
        });
        Future<?> future = executor.submit(() -> {
            for (int sample = 0; sample < 500; sample++) {
                StringBuilder mutated = new StringBuilder(seed);
                int edits = 1 + random.nextInt(8);
                for (int edit = 0; edit < edits; edit++) {
                    int operation = random.nextInt(3);
                    int position = mutated.isEmpty() ? 0 : random.nextInt(mutated.length() + 1);
                    if (operation == 0 && !mutated.isEmpty()) {
                        mutated.deleteCharAt(Math.min(position, mutated.length() - 1));
                    } else if (operation == 1) {
                        mutated.insert(position, (char) (32 + random.nextInt(95)));
                    } else if (!mutated.isEmpty()) {
                        mutated.setCharAt(Math.min(position, mutated.length() - 1),
                                (char) (32 + random.nextInt(95)));
                    }
                }
                ValidationResult result = XmlBeans.builder().limits(limits)
                        .inspectXml(mutated.toString(), "fuzz-" + sample + ".xml");
                if (result == null) throw new AssertionError("null fuzz result");
            }
            for (int sample = 0; sample < 100; sample++) {
                byte[] bytes = new byte[random.nextInt(1024)];
                for (int index = 0; index < bytes.length; index++) {
                    bytes[index] = (byte) (32 + random.nextInt(95));
                }
                ValidationResult result = XmlBeans.builder().limits(limits)
                        .inspect(new ByteArrayInputStream(bytes), "bytes-" + sample + ".xml");
                if (result == null) throw new AssertionError("null byte fuzz result");
            }
        });
        try {
            future.get(15, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    private static void containerEvents() throws Exception {
        Fixtures.reset();
        List<String> events = new ArrayList<>();
        List<BeanEvent> payloads = new ArrayList<>();
        String body = """
                <beans version="2"><bean id="event" class="%s">
                  <constructor><arg value="events"/></constructor>
                </bean></beans>
                """.formatted(Fixtures.EventBean.class.getName());
        BeanContext context = XmlBeans.builder().listener(event -> {
            payloads.add(event);
            events.add(event.kind() + ":" + (event.beanId() == null ? "-" : event.beanId()));
        }).loadXml(body, "events.xml");
        context.close();
        equal(List.of(
                "CONFIG_PARSING:-",
                "CONFIG_PARSED:-",
                "GRAPH_COMPILING:-",
                "GRAPH_COMPILED:-",
                "CONTEXT_STARTING:-",
                "BEAN_CREATING:event",
                "BEAN_CREATED:event",
                "CONTEXT_STARTED:-",
                "CONTEXT_CLOSING:-",
                "BEAN_DESTROYING:event",
                "BEAN_DESTROYED:event",
                "CONTEXT_CLOSED:-"), events);
        check(payloads.stream().filter(event -> event.kind() == BeanEvent.Kind.CONFIG_PARSED
                || event.kind() == BeanEvent.Kind.GRAPH_COMPILED
                || event.kind() == BeanEvent.Kind.BEAN_CREATED
                || event.kind() == BeanEvent.Kind.BEAN_DESTROYED)
                .allMatch(event -> event.durationNanos() > 0), "completed events carry durations");
    }

    private static void listenerFailureRollback() throws Exception {
        Fixtures.reset();
        String body = """
                <beans version="2"><bean id="event" class="%s">
                  <constructor><arg value="listener"/></constructor>
                </bean></beans>
                """.formatted(Fixtures.EventBean.class.getName());
        List<BeanEvent.Kind> kinds = new ArrayList<>();
        BeanException failure = expect(BeanException.class, () -> XmlBeans.builder()
                .listener(event -> kinds.add(event.kind()))
                .listener(event -> {
                    if (event.kind() == BeanEvent.Kind.BEAN_CREATED && "event".equals(event.beanId())) {
                        throw new IllegalStateException("listener boom");
                    }
                }).loadXml(body, "listener.xml"));
        contains(rootText(failure), "listener boom");
        equal(List.of("listener.construct", "listener.close"), List.copyOf(Fixtures.EVENTS));
        check(kinds.contains(BeanEvent.Kind.BEAN_FAILED), "listener failure emits bean failure");
        check(kinds.contains(BeanEvent.Kind.ROLLBACK_STARTING), "rollback start emitted");
        check(kinds.contains(BeanEvent.Kind.ROLLBACK_COMPLETED), "rollback completion emitted");
        check(kinds.contains(BeanEvent.Kind.BEAN_DESTROYED), "rollback destruction emitted");
    }

    private static void lifecycleSequencing() throws Exception {
        Fixtures.reset();
        String body = """
                <beans version="2">
                  <bean id="after" class="%s" destroy-method="stop" auto-close="after">
                    <constructor><arg value="after"/></constructor>
                  </bean>
                  <bean id="before" class="%s" destroy-method="stop" auto-close="before">
                    <constructor><arg value="before"/></constructor>
                  </bean>
                  <bean id="never" class="%s" destroy-method="stop" auto-close="never">
                    <constructor><arg value="never"/></constructor>
                  </bean>
                  <bean id="external" class="%s" destroy-method="stop" auto-close="after" ownership="external">
                    <constructor><arg value="external"/></constructor>
                  </bean>
                </beans>
                """.formatted(Fixtures.LifecycleProbe.class.getName(), Fixtures.LifecycleProbe.class.getName(),
                Fixtures.LifecycleProbe.class.getName(), Fixtures.LifecycleProbe.class.getName());
        try (BeanContext context = XmlBeans.loadXml(body, "lifecycle.xml")) {
            equal(4, context.beansOfType(Fixtures.LifecycleProbe.class).size());
        }
        equal(List.of(
                "after.construct", "before.construct", "never.construct", "external.construct",
                "never.stop", "before.close", "before.stop", "after.stop", "after.close"),
                List.copyOf(Fixtures.EVENTS));

        Fixtures.reset();
        String failingSingleton = """
                <beans version="2"><bean id="dual" class="%s" destroy-method="stop" auto-close="after"/></beans>
                """.formatted(Fixtures.DualFailLifecycle.class.getName());
        BeanContext singletonContext = XmlBeans.loadXml(failingSingleton, "dual-singleton.xml");
        BeanException singletonFailure = expect(BeanException.class, singletonContext::close);
        contains(throwableTreeText(singletonFailure), "stop failed");
        contains(throwableTreeText(singletonFailure), "close failed");
        equal(List.of("dual.construct", "dual.stop", "dual.close"), List.copyOf(Fixtures.EVENTS));

        Fixtures.reset();
        String failingPrototype = """
                <beans version="2"><bean id="dual" class="%s" scope="prototype"
                  destroy-method="stop" auto-close="after"/></beans>
                """.formatted(Fixtures.DualFailLifecycle.class.getName());
        try (BeanContext context = XmlBeans.loadXml(failingPrototype, "dual-prototype.xml")) {
            BeanHandle<Fixtures.DualFailLifecycle> handle = context.create("dual", Fixtures.DualFailLifecycle.class);
            BeanException handleFailure = expect(BeanException.class, handle::close);
            contains(throwableTreeText(handleFailure), "stop failed");
            contains(throwableTreeText(handleFailure), "close failed");
        }
        equal(List.of("dual.construct", "dual.stop", "dual.close"), List.copyOf(Fixtures.EVENTS));
    }

    private static Path xml(String text) throws Exception {
        Path path = Files.createTempFile("simple-di-test-", ".xml");
        Files.writeString(path, text);
        path.toFile().deleteOnExit();
        return path;
    }

    private static void run(String name, Checked test) throws Exception {
        try {
            test.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (Throwable error) {
            System.err.println("FAIL " + name);
            throw error;
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

    private static void contains(String actual, String expected) {
        if (actual == null || !actual.contains(expected)) {
            throw new AssertionError("expected to contain '" + expected + "': " + actual);
        }
    }

    private static <T extends Throwable> T expect(Class<T> type, Checked action) throws Exception {
        try {
            action.run();
        } catch (Throwable error) {
            if (type.isInstance(error)) return type.cast(error);
            throw new AssertionError("expected " + type.getName() + " but got " + error, error);
        }
        throw new AssertionError("expected " + type.getName());
    }

    private static Throwable futureFailure(Future<?> future) throws Exception {
        try {
            future.get(8, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            return e.getCause();
        }
        throw new AssertionError("expected future failure");
    }

    private static String rootText(Throwable error) {
        StringBuilder result = new StringBuilder();
        Throwable current = error;
        while (current != null) {
            result.append(current).append(' ');
            current = current.getCause();
        }
        return result.toString();
    }

    private static String throwableTreeText(Throwable error) {
        StringBuilder result = new StringBuilder();
        appendThrowable(error, result, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
        return result.toString();
    }

    private static void appendThrowable(Throwable error, StringBuilder result, Set<Throwable> seen) {
        if (error == null || !seen.add(error)) return;
        result.append(error).append(' ');
        appendThrowable(error.getCause(), result, seen);
        for (Throwable suppressed : error.getSuppressed()) appendThrowable(suppressed, result, seen);
    }

    private record LeakProbe(BeanContext context, WeakReference<ClassLoader> loader) {}

    @FunctionalInterface
    private interface Checked {
        void run() throws Exception;
    }
}
