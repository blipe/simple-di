# simple-di 2.5

A small, explicit, JDK-only XML object-graph assembler. It covers normal application and plugin wiring without classpath scanning, annotation magic, private reflection, proxies, or implicit by-type autowiring.

## Requirements

- JDK 21+
- Runtime modules: `java.base`, `java.xml`
- Runtime dependencies: none
- Explicit JPMS module: `io.github.simpledi`

## Build and verify

```bash
./test.sh
./run-example.sh
```

Or with Maven:

```bash
mvn test
```

The standalone suite compiles all production and test code with `-Xlint:all -Werror` and runs 119 adversarial integration tests across the base container and reload manager.

## Basic use

```java
try (BeanContext context = XmlBeans.load(Path.of("beans.xml"))) {
    OrderService service = context.require("service", OrderService.class);
    service.run();
}
```

Path/classpath inputs own and close their underlying streams. Caller-supplied inputs remain caller-owned:

```java
BeanContext fromStream = XmlBeans.load(inputStream, "generated-config.xml");
BeanContext fromReader = XmlBeans.load(reader, "generated-config.xml");
BeanContext fromString = XmlBeans.loadXml(xmlText, "generated-config.xml");
```

Inline stream, reader, and string documents deliberately cannot use `<include>` because they have no trustworthy include origin.

Builder configuration:

```java
BeanContext context = XmlBeans.builder()
        .classLoader(pluginClassLoader)
        .propertiesFile(Path.of("application.properties"))
        .propertySource(vaultPropertySource)
        .property("environment", "dev")
        .secret("database.password", password)
        .bind("hostExecutor", Executor.class, externallyOwnedExecutor)
        .converter(Endpoint.class, (text, ignored) -> Endpoint.parse(text))
        .overlay(Path.of("development.xml"))
        .load(Path.of("plugin-beans.xml"));
```


## Safe whole-graph reload

`ReloadableBeanContext` builds each candidate as a complete independent context, then publishes one immutable generation reference atomically. Existing leases remain attached to the previous generation; new leases immediately use the replacement.

```java
try (ReloadableBeanContext manager = XmlBeans.reloadable()
        .builder(XmlBeans.builder().propertiesFile(Path.of("application.properties")))
        .reloadPolicy(ReloadPolicy.GRACEFUL)
        .load(Path.of("beans-v1.xml"))) {

    try (ContextLease lease = manager.acquire()) {
        lease.require("service", Service.class).run();
    }

    ReloadResult result = manager.reload(Path.of("beans-v2.xml"));
    if (!result.activated() && result.status() != ReloadResult.Status.UNCHANGED) {
        throw new IllegalStateException("reload failed: " + result.failure());
    }
}
```

Candidate parsing, validation, construction, injection, initialization, lifecycle listeners, and optional state handoff all complete before publication. Failure at any pre-publication phase closes the candidate and leaves the active generation untouched.

For deployment review, prepare without publishing:

```java
try (PreparedReload prepared = manager.prepare(Path.of("beans-v2.xml"))) {
    prepared.validation().problems().forEach(System.out::println);
    System.out.println(prepared.validation().toDot());
    ReloadResult result = prepared.activate();
}
```

The candidate records its base generation. Activation rejects stale prepared candidates if another replacement won first.

### Leases and retirement

A lease protects one generation for the complete operation:

```java
try (ContextLease lease = manager.acquire()) {
    Service service = lease.require("service", Service.class);
    service.run();
}
```

Do not retain a bean beyond its lease unless the application deliberately owns that object independently. Caller-owned prototype handles created through `lease.context().create(...)` are themselves owned by the lease and close before the generation is released.

Reload policies are explicit:

- `GRACEFUL` does not wait for existing leases and destroys the old generation after its final lease closes (an already-drained generation is destroyed inline);
- `GRACEFUL_WITH_TIMEOUT` waits up to `drainTimeout(...)` during the reload call, leaving a still-busy generation retired until it drains;
- `IMMEDIATE` invalidates and closes old leases immediately;
- `REJECT_WHILE_BUSY` refuses publication while the active generation has leases.

Manager shutdown has an independently configurable `shutdownPolicy(...)`. `LeaseDiagnostics.CAPTURE_STACK` records bounded acquisition traces for forced-shutdown diagnostics; it is off by default.

### Revisions, handoff, and plugin classloaders

A manager-local configuration revision covers the semantic XML graph, includes/overlays, frozen property resolutions, external-binding identities, extension registrations, classloader identity, and host-provided `.revisionToken(...)` values. Sensitive values contribute only a manager-keyed HMAC. Equal revisions return `UNCHANGED` without constructing another graph.

No singleton or mutable runtime state is reused automatically. State transfer is an explicit pre-publication callback:

```java
XmlBeans.reloadable()
        .handoff((previous, candidate, diff) ->
                candidate.require("cache", Cache.class)
                        .restore(previous.require("cache", Cache.class).snapshot()));
```

Generation-specific custom scopes or classloaders require `builderFactory(...)` so each generation receives fresh extension instances. For unloadable plugins:

```java
AtomicReference<ClassLoader> loader = new AtomicReference<>(initialPluginLoader);
ReloadableBeanContext manager = XmlBeans.reloadable()
        .builderFactory(() -> XmlBeans.builder().classLoader(loader.get()))
        .classLoaderOwnership(GenerationClassLoaderOwnership.CLOSE_ON_RETIREMENT)
        .load(pluginXml);
```

Owned classloaders must implement `AutoCloseable`, and every changed generation must receive a distinct loader. Reload results, events, and retired-generation histories contain value-only `ReloadFailure` diagnostics, so retaining operational history does not pin application exception classes.

## Property sources and secrets

Property precedence is explicit and inspectable, from highest to lowest:

1. direct `.property(...)` and `.secret(...)` values;
2. custom/property-file sources, with the most recently added source winning;
3. JVM system properties;
4. environment variables exposed as `env.NAME`.

Disable system/environment fallback with `.withoutDefaultPropertySources()`.

```java
PropertySource vault = name -> switch (name) {
    case "database.password" -> Optional.of(PropertyValue.secret(readSecret(name), "vault"));
    default -> Optional.empty();
};

XmlBeans.Builder builder = XmlBeans.builder()
        .propertiesFile(Path.of("application.properties"), Set.of("api.token"))
        .propertiesResource("defaults.properties")
        .propertySource(vault)
        .secret("bootstrap.password", bootstrapPassword);
```

Sensitivity follows a value through nested placeholders and conversion. Conversion/configuration diagnostics render `<redacted>` and do not retain the original sensitive conversion failure as a cause. `ValidationResult.properties()` reports the winning and shadowed source names, sensitivity, and whether a default was used, but never reports values.

Existing application objects can be bound by ID:

```java
BeanContext context = XmlBeans.builder()
        .bind("clock", Clock.systemUTC())
        .bindings(Map.of("executor", executor, "metrics", metrics))
        .load(Path.of("beans.xml"));
```

External bindings participate in references, aliases, instance factories, validation, and by-type lookup. They are caller-owned: the context never injects, initializes, or destroys them. Duplicate names and duplicate object identities are rejected.

Expose a deliberate API type rather than the runtime implementation:

```java
builder.bind("repository", Repository.class, repository);
builder.bind("customerRepository",
        new TypeRef<Repository<Customer>>() {}, customerRepository);
```

The declared type drives executable discovery, generic validation, `beanType(...)`, and by-type lookup. This supports proxies, lambdas, package-private implementations, and generic factories without exposing implementation details.

## Core XML

```xml
<beans version="2" default-lazy="false">
  <include file="storage.xml"/>
  <include resource="plugins/common.xml"/>
  <alias name="repository" alias="repo"/>

  <bean id="clock" class="java.time.Clock">
    <factory method="systemUTC"/>
  </bean>

  <bean id="service" class="example.OrderService"
        depends-on="migration" init-method="start" destroy-method="stop">
    <constructor>
      <arg ref="repo"/>
      <arg ref="clock"/>
    </constructor>
    <property name="batchSize" value="100"/>
    <call method="addInterceptor"><arg ref="auditInterceptor"/></call>
  </bean>
</beans>
```

`include file` is relative to the including file. `include resource` is relative to an including classpath resource, or classpath-rooted when included from a file. Includes are cycle checked and loaded once. Duplicate bean or alias names are rejected across the complete document set.

By default, file includes cannot escape the root document's directory, including through existing symlinks. Classpath includes cannot escape the root resource's directory. Hosts can tighten or deliberately widen those boundaries:

```java
XmlBeans.builder()
        .fileIncludeRoot(Path.of("/opt/application/config"))
        .classpathIncludeRoot("application/config")
        .withoutFileIncludes()       // optional hard disable
        .withoutClasspathIncludes()  // optional hard disable
        .load(Path.of("beans.xml"));
```

The root `version="2"` is the current grammar marker. Unversioned 2.2 documents remain accepted for compatibility; unknown versions fail before graph compilation.

## Explicit configuration overlays

Overlays are independent documents applied in builder order. Replacing a bean must be intentional:

```java
BeanContext context = XmlBeans.builder()
        .overlay(Path.of("environment.xml"))
        .overlayResource("site-overrides.xml")
        .load(Path.of("base.xml"));
```

```xml
<beans>
  <bean id="repository" replaces="repository" class="example.LocalRepository"/>
</beans>
```

`replaces` must equal the bean's own `id` and must target an existing bean from an earlier layer. An unmarked collision, replacement of a missing bean, alias collision, or duplicate within one layer fails. This prevents accidental last-file-wins configuration.

## Property-driven activation

Top-level beans can be activated before class loading and graph compilation:

```xml
<bean id="realGateway" class="example.RealGateway"
      if-property="gateway.mode" if-value="real"/>
<bean id="fallbackGateway" class="example.FallbackGateway"
      unless-property="gateway.mode" unless-value="real"/>
<bean id="optionalFeature" class="example.OptionalFeature"
      if-property="feature.enabled" match-if-missing="true"/>
```

`if-property` matches presence, or equality when `if-value` is supplied. `match-if-missing="true"` applies only to `if-property`. `unless-property` matches absence, or inequality when `unless-value` is supplied. Conditions use the same precedence-aware property sources as placeholders, but diagnostics contain only the property name, selected source, sensitivity flag, operator, and active/inactive outcome—never the value. Inactive beans and aliases targeting them are removed before their classes or executables are inspected. Overlay conditions are evaluated before explicit replacement rules.

## Creation

### Constructor and record injection

```xml
<bean id="config" class="example.ServerConfig">
  <constructor>
    <arg value="localhost"/>
    <arg value="8080"/>
    <arg value="PT30S"/>
  </constructor>
</bean>
```

Records use their ordinary public constructor. Arguments are positional and do not require `-parameters`.

### Named, indexed, and exact executable binding

Record canonical constructors can be wired by component name in any XML order, without `-parameters`:

```xml
<bean id="config" class="example.ServerConfig">
  <constructor>
    <arg name="timeout" value="PT30S"/>
    <arg name="host" value="localhost"/>
    <arg name="port" value="8080"/>
  </constructor>
</bean>
```

Ordinary constructor/method parameter names are available only when the target was compiled with `-parameters`. Indexed arguments are the universal order-independent fallback:

```xml
<constructor>
  <arg index="1" value="8080"/>
  <arg index="0" value="localhost"/>
</constructor>
```

For overloaded or API-stability-sensitive calls, pin the erased JVM-facing Java signature explicitly:

```xml
<constructor signature="(java.lang.String,int)"/>
<factory method="create" signature="(java.lang.String,int)"/>
<call method="configure" signature="(java.time.Duration,java.lang.String...)"/>
```

A selected argument list must be entirely positional or entirely name/index selected. Duplicate, missing, unknown, and out-of-range selectors fail during preflight. Name/index selection targets fixed-arity executables; expanded varargs arguments remain positional. Signature types support primitives, fully qualified classes, arrays, and `...` varargs notation.

### Static factory

```xml
<bean id="product" class="example.Product">
  <factory class="example.ProductFactory" method="create">
    <arg value="widget"/>
    <arg value="3" type="int"/>
  </factory>
</bean>
```

When `class` is omitted on `<factory>`, the declared bean class owns the static method.

### Instance factory

```xml
<bean id="factory" class="example.ProductFactory"/>
<bean id="product" class="example.Product">
  <factory bean="factory" method="create"><arg value="widget"/></factory>
</bean>
```

### Setter and arbitrary public method calls

```xml
<property name="timeout" value="PT5S"/>
<call method="addListener"><arg ref="listener"/></call>
<call method="configure"><arg value="a"/><arg value="b"/></call>
```

`<call>` supports repeatable adders, fluent APIs, and builders without pretending every API is a JavaBean. Java varargs work for constructors, factories, setters, and calls.

## References and deferred edges

```xml
<arg ref="repository"/>
<arg><supplier ref="largeLazyService"/></arg>
<arg><optional-ref bean="optionalPlugin"/></arg>
```

- `ref` is eager and must exist.
- `supplier` injects `Supplier<T>` and creates the target only when `get()` is called. It can safely break a genuinely lazy cycle.
- `optional-ref` injects `Optional<T>` and is empty when the bean does not exist.
- Generic `Supplier<T>` and `Optional<T>` assignability is validated before startup.

Beans may use `lazy="true"`; `<beans default-lazy="true">` changes the document default. `depends-on="a,b"` adds explicit creation and reverse-destruction ordering without injecting either bean.

### Singleton, prototype, and custom scopes

Singleton is the default. A prototype creates a new instance for every direct reference, context lookup, or supplier call:

```xml
<bean id="requestBuilder" class="example.RequestBuilder" scope="prototype"/>
<bean id="service" class="example.Service">
  <constructor><arg><supplier ref="requestBuilder"/></arg></constructor>
</bean>
```

Prototype beans are never eagerly created, so `lazy` is rejected on prototypes. Prototypes are **caller-owned by default** and are not retained by the context. Use a `BeanHandle<T>` when the prototype has a destroy method or implements `AutoCloseable`:

```java
try (BeanHandle<RequestBuilder> handle = context.create("requestBuilder", RequestBuilder.class)) {
    handle.value().build();
}
```

`BeanHandle.close()` destroys the complete caller-owned prototype subgraph in reverse creation order and aggregates cleanup failures. A raw `require()` or injected `Supplier<T>` still creates a caller-owned prototype, but ownership is transferred directly to the caller/consumer; the context does not close it.

For the legacy tracked behavior, opt in explicitly:

```xml
<bean id="trackedWorker" class="example.Worker"
      scope="prototype" ownership="context"/>
```

Context-owned prototypes are retained and destroyed at context shutdown. Use that mode only for bounded creation counts. `ownership="caller"` is valid only with prototype scope.

A container-created singleton or prototype can be marked `ownership="external"` when the returned object is shared or lifecycle-managed elsewhere. It still participates in creation, injection, lookup, and identity checks, but simple-di never invokes its destroy method or `AutoCloseable.close()`.

Custom scopes are explicit host registrations:

```java
AtomicReference<String> requestId = new AtomicReference<>();
BeanScopes.Keyed requestScope = BeanScopes.keyed(requestId::get);
BeanContext context = XmlBeans.builder()
        .scope("request", requestScope)
        .load(Path.of("beans.xml"));

requestId.set("request-42");
RequestState state = context.require("requestState", RequestState.class);
requestScope.releaseCurrent();
```

```xml
<bean id="requestState" class="example.RequestState" scope="request"/>
```

The standard keyed and thread-local scopes construct outside scope locks, publish only after the root creation transaction commits, roll back failed values, detect same-thread and cross-thread cycles, and destroy one key in reverse publication order. A custom-scoped graph is owned by the scope. The context closes registered scopes before singleton destruction. Scope instances are single-context objects; do not share one scope instance between contexts. A singleton cannot directly capture a custom-scoped or caller-owned prototype dependency; inject `Supplier<T>` so resolution occurs in the active scope.

Third-party `BeanScope` implementations use reservation/publish/cancel semantics. They must be thread-safe, avoid invoking constructors themselves, unblock waiters on cancellation, and provide equivalent cycle/deadlock protection for their waiting model.

## Parent and child contexts

A child can import only explicitly selected parent beans. The parent retains ownership and the child cannot close imported objects:

```java
BeanContext child = XmlBeans.builder()
        .parent(hostContext)
        .importBean("metrics")
        .importBean("hostExecutor", "executor")
        .load(Path.of("plugin.xml"));
```

Parent beans are validated using their canonical declared type, including generic type information. A parent cannot close while child contexts remain open. Close children first; closing a child releases its parent lease even when child destruction reports failures. There is no unrestricted parent fallback, so plugin dependencies remain explicit.

## Values

```xml
<arg value="${host:localhost}"/>
<arg value="12" type="long"/>
<arg><null/></arg>
<arg><optional/></arg>
<arg><optional><value>present</value></optional></arg>
<arg><constant class="java.nio.charset.StandardCharsets" field="UTF_8"/></arg>
```

Placeholders support nested keys/defaults, recursive property values, escaping as `\${literal}`, missing-property errors, and cycle detection.

Nested anonymous bean:

```xml
<property name="listener">
  <bean class="example.AuditListener"><property name="name" value="audit"/></bean>
</property>
```

Collections use generic target types:

```xml
<property name="names"><list immutable="true"><value>A</value><value>B</value></list></property>
<property name="modes"><set><value>FAST</value><value>SAFE</value></set></property>
<property name="weights"><map><entry key="high" value="10"/></map></property>
<property name="codes"><array><value>4</value><value>7</value></array></property>
<property name="settings"><properties immutable="true"><property name="mode" value="safe"/></properties></property>
```

Supported abstract targets include `List`, `Collection`, `Iterable`, `Queue`, `Deque`, `Set`, `SortedSet`, `NavigableSet`, `Map`, `SortedMap`, `NavigableMap`, and `ConcurrentMap`. Concrete collection/map targets are instantiated through their public no-argument constructor. Immutable wrappers are supported for list/set/map interface targets and `Properties`.

Built-in string conversion covers primitives/wrappers, enums, `Class`, numeric big types, file/path/URI/URL, UUID, regex, charset, locale, currency, zone IDs, and common `java.time` types. Custom converters are copied into each context.

## Deterministic generic binding

The entire graph is parsed, validated, class-loaded, and executable-bound before any user constructor or init method runs. Generic types are resolved through inherited classes and interfaces, including type variables, nested parameterized types, wildcards, generic arrays, `Supplier<T>`, `Optional<T>`, collection/map members, and typed external factories. A raw source cannot prove compatibility with a parameterized target.

Selection prefers:

1. exact or primitive-wrapper match;
2. assignable reference match;
3. legal numeric widening;
4. registered string conversion;
5. the uniquely more-specific overload.

Unresolved ties are errors. Use `type="int"`, `type="long"`, or another explicit type rather than relying on reflection order.

## Lifecycle and failure behavior

- Eager beans start only after the complete graph preflight succeeds.
- Injection values are prepared before the target constructor runs.
- Init runs after properties and calls.
- `auto-close="fallback"` is the default: an explicit destroy method wins; otherwise `AutoCloseable.close()` runs.
- `auto-close="before"` and `auto-close="after"` run both actions in the selected order and aggregate failures; `auto-close="never"` suppresses automatic close.
- Shutdown is reverse construction order.
- All shutdown failures are attempted and aggregated as suppressed exceptions.
- Failed eager startup closes the complete partial graph.
- Failed lazy creation rolls back only that creation transaction, clears its singleton states, and can be retried.
- Returning the same object identity from two bean definitions is rejected; use an alias. This prevents double injection and double destruction.
- Context lookup, lazy creation, and prototype creation are thread-safe; a singleton is created once under concurrent access.
- User constructors, factories, converters, injection calls, init methods, and destroy methods never run while the context monitor is held.
- Concurrent singleton creation uses per-bean completion slots. Cross-thread dependency cycles are detected and reported rather than hanging.
- Root creation transactions publish singleton results only after the complete graph operation succeeds; waiters never observe a partially initialized singleton.
- Caller-owned prototype failures are rolled back before ownership transfer. `BeanHandle` provides deterministic cleanup after successful transfer.
- Context-owned prototypes are tracked and destroyed in reverse creation order; caller-owned prototypes are not retained.
- External bindings are never initialized or destroyed.
- `close()` rejects new operations, waits for already-active operations to finish, is idempotent, and releases instances, executable handles, converter registrations, compiler plans, declared classes, and classloader references.


## Container events

Listeners receive immutable synchronous events without forcing a logging, metrics, JFR, or tracing dependency:

```java
BeanContext context = XmlBeans.builder()
        .listener(event -> telemetry.record(
                event.kind(), event.beanId(), event.durationNanos(), event.failure()))
        .load(Path.of("beans.xml"));
```

Events cover configuration parsing, graph compilation, context start/close, bean create/destroy success and failure, and rollback boundaries. Listener callbacks run outside container monitors. A listener failure is treated like any other user extension failure: startup/creation rolls back, shutdown continues cleanup and aggregates the listener failure. Event payloads contain no resolved property values.

Instance-aware lifecycle interception is separate from value-free events:

```java
XmlBeans.builder().lifecycleInterceptor(new BeanLifecycleInterceptor() {
    @Override
    public void afterInitialization(BeanLifecycleContext metadata, Object bean) {
        registry.register(metadata.beanId(), bean);
    }

    @Override
    public void beforeDestruction(BeanLifecycleContext metadata, Object bean) {
        registry.unregister(metadata.beanId(), bean);
    }
});
```

Construction and pre-initialization callbacks run in registration order; post-initialization callbacks unwind in reverse order. Destruction begins in reverse interceptor order and completes in forward order. Interceptor failure participates in creation rollback or shutdown aggregation. Interceptors also follow caller-owned prototype handles and custom-scope handles, so destruction remains observable after ownership transfer.

## 2.4 to 2.5 migration

2.5 preserves the normal `BeanContext`, XML, scope, condition, interceptor, and validation APIs. Reload is opt-in through `XmlBeans.reloadable()`.

- Wrap each unit of work in a `ContextLease`; do not cache generation-owned beans globally.
- Use `builderFactory(...)` when custom scopes or generation-specific classloaders are registered. `.builder(...)` is suitable only when registrations are safely copied and scopes are absent.
- Candidate graphs are complete replacements. Unchanged singleton reuse and state migration are deliberately not inferred.
- Use `GenerationHandoff` for explicit state transfer before atomic publication.
- `GRACEFUL` is the reload default; shutdown defaults to `GRACEFUL_WITH_TIMEOUT` with a 30-second drain timeout.
- `GenerationClassLoaderOwnership.CLOSE_ON_RETIREMENT` requires distinct `AutoCloseable` classloaders for changed generations.
- Reload failures are exposed as value-only `ReloadFailure` records rather than retained application `Throwable` objects.

Prototype ownership remains the only intentional 2.0-to-2.1 behavioral change: prototypes default to caller ownership. Add `ownership="context"` for the old tracked behavior, or use `context.create(...)` and close the returned handle.

## Validation and graph inspection without startup

```java
ValidationResult report = XmlBeans.builder()
        .property("environment", "test")
        .inspect(Path.of("beans.xml"));

if (!report.valid()) {
    report.problems().forEach(problem ->
            System.err.println(problem.code() + " " + problem.location() + ": " + problem.message()));
}
```

The report contains:

- compiled `BeanPlan`s and selected creator/injection signatures;
- named eager/lazy dependency edges;
- deterministic startup and reverse destruction order;
- value-free property precedence and condition-outcome metadata;
- complete alias target metadata;
- multiple independent structured `ConfigurationProblem`s with code, location, bean ID, and cycle path.

Configuration reports can be compared and rendered without exposing resolved values:

```java
ConfigurationDiff diff = previous.diff(candidate);
Files.writeString(Path.of("graph.dot"), candidate.toDot());
System.out.println(candidate.explain("checkoutService"));
```

Diffs identify added/removed/changed beans, alias retargeting, dependency changes, property-source metadata changes, and condition-state changes. They are deliberately structural and value-free: a literal or property value change from the same source is not fingerprinted, because secret fingerprints can themselves become disclosure or guessing oracles.

`validate(...)` preserves the older validate-or-throw contract by calling `throwIfInvalid()`. Neither API constructs beans or executes user constructors, factories, setters, init methods, or custom converters. Parsing/global graph failures may necessarily prevent collection of later independent errors; executable/type failures in independent beans are collected together.

## XML security

The parser fails closed unless DTD and external-entity support can be disabled. DTD/entity events are rejected, an external resolver always fails, and configurable limits cover documents, elements, depth, attributes, value text, bean count, bytes/characters per document, aggregate bytes/characters across includes and overlays, and comment/whitespace volume:

```java
XmlBeans.builder().limits(new XmlLimits(
        128, 100_000, 128, 64, 1_000_000, 20_000,
        8L * 1024 * 1024, 32L * 1024 * 1024, 1_000_000));
```

The original six-argument `XmlLimits` constructor remains supported and supplies hardened defaults for the new limits. File include roots are real-path checked where possible; classpath include paths are normalized and root constrained.

The no-namespace XSD for editor completion is at `schema/simple-di.xsd` and is also packaged as `/io/github/simpledi/simple-di.xsd`.

## Context lookup

```java
context.require("service");
context.require("service", Service.class);
context.require(Service.class);              // must be unique
context.find("optional");
context.find(Service.class);                 // empty or unique
context.beansOfType(Plugin.class);           // canonical names, XML order
context.require(new TypeRef<Repository<Customer>>() {});
context.find(new TypeRef<Repository<Order>>() {});
context.beansOfType(new TypeRef<Handler<Event>>() {});
context.beanType("customerRepository");           // canonical reflective Type
try (BeanHandle<Job> job = context.create("job", Job.class)) {
    job.value().run();                        // caller-owned prototype
}
context.contains("repo");                    // aliases included
context.beanNames();
context.aliases();
ContextSnapshot snapshot = context.snapshot(); // no instances and no lazy creation
```

XML remains explicit: there is no by-type autowiring. By-type operations are lookup conveniences after the graph has already been defined.

## Deliberate exclusions

- component/classpath scanning;
- annotation autowiring;
- private field or private method access;
- implicit by-type wiring or “primary” selection;
- automatic framework request/session scopes, proxy-based AOP, or implicit interception; custom explicit scopes and lifecycle interceptors remain available;
- expression languages and arbitrary code embedded in XML;
- eager circular dependencies or setter-cycle tricks;
- framework compatibility modes.

These are framework concerns. `simple-di` remains a deterministic object assembler suitable for applications, tests, tools, and unloadable plugin classloaders.
