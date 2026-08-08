# Changelog

## 2.5.0

Safe whole-graph reload release:

- Added `ReloadableBeanContext`, generation-bound `ContextLease`, prepared candidates, stale-candidate protection, and atomic publication of completely initialized replacement graphs.
- Added graceful, timed, immediate, and reject-while-busy retirement policies plus independently configurable shutdown behavior.
- Added configuration revisions with frozen property values, manager-keyed HMAC treatment of secrets, weak extension/binding/classloader identities, and explicit host revision tokens.
- Added explicit pre-publication `GenerationHandoff`, synchronous reload lifecycle events, retired-generation diagnostics, and side-effect-free prepared validation/diff review.
- Added optional generation classloader ownership with early `AutoCloseable` validation, distinct-loader enforcement, close-on-retirement, and real classloader collection tests.
- Bound caller-owned prototype handles to their generation lease so generation cleanup cannot race escaped handles.
- Added value-only `ReloadFailure` diagnostics so retained results/events/history do not retain application throwables or plugin classes.
- Hardened concurrent shutdown to wait for in-progress generation destruction and aggregate forced lease/prototype/context/classloader failures.
- Fixed JPMS invocation for packages qualified-exported specifically to `io.github.simpledi`; public method handles remain the fast path with reflection fallback where the JDK method-handle lookup requires an unconditional export.
- Added concurrent atomicity, startup/validation/handoff rollback, no-op revision, lease drain, timeout, classloader, lifecycle-race, and retained-diagnostic tests.
- Expanded the adversarial suite from 94 to 119 tests.

## 2.4.0

Explicit-extensibility and diagnostics release:

- Added property-driven bean activation with `if-property`, `if-value`, `unless-property`, `unless-value`, and positive `match-if-missing`, evaluated before class loading, overlays, and graph compilation.
- Added value-free `ConditionOutcome` reporting with property provenance and sensitivity metadata.
- Added the transaction-aware `BeanScope` SPI plus standard keyed and thread-local scopes with explicit key release, reverse-order destruction, failure rollback/retry, and same-thread/cross-thread cycle detection.
- Added static scope-escape validation so singletons cannot directly capture custom-scoped or caller-owned prototype instances; deferred `Supplier<T>` edges remain valid.
- Added instance-aware `BeanLifecycleInterceptor` callbacks across singleton, prototype-handle, custom-scope, rollback, and shutdown lifecycles.
- Added names-and-counts-only runtime `ContextSnapshot` diagnostics without lazy creation or instance exposure.
- Added value-free `ValidationResult.diff(...)`, Graphviz DOT rendering, per-bean/alias/condition explanation, alias target metadata, alias-retarget detection, and condition-state change detection.
- Hardened custom-scope shutdown aggregation, inactive-bean class-loading isolation, condition grammar validation, and custom scope-name validation in both parser and XSD.
- Preserved JDK-only runtime dependencies and all 2.3 construction, generic, lifecycle, security, JPMS, and reproducibility behavior.
- Expanded the adversarial integration suite from 79 to 94 tests.

## 2.3.0

Operational-hardening release:

- Added grammar marker `version="2"`, while preserving unversioned 2.2 XML compatibility and rejecting unknown versions.
- Added exact constructor, factory, and method binding with `signature="(...)"`, including primitive, array, and varargs parameter types.
- Added order-independent named and indexed executable arguments. Record canonical constructor names work without `-parameters`; ordinary named parameters require it.
- Added caller-owned `InputStream`, `Reader`, and XML-string load/inspection APIs with stable source names and no implicit input closure.
- Added default file/classpath include sandboxes, configurable include roots, hard-disable switches, symlink-aware file checks, per-document input limits, aggregate include limits, and comment/whitespace limits.
- Added synchronous `BeanContextListener` and immutable `BeanEvent` APIs covering configuration parsing, graph compilation, context, creation, destruction, failure, and rollback transitions. Listener failures participate in rollback and shutdown aggregation.
- Added lifecycle policies `auto-close="fallback|before|after|never"` and XML `ownership="external"` for container-created but externally managed objects.
- Updated and packaged the editor XSD for the 2.3 grammar.
- Preserved 2.2 graph, generic, property, overlay, parent/child, lifecycle, and classloader semantics.
- Expanded the adversarial integration suite from 69 to 79 tests, including deterministic parser mutation/byte fuzz smoke coverage.

## 2.2.0

Configuration-composition and type-fidelity release:

- Added precedence-aware `PropertySource` and `PropertyValue` APIs, `.propertiesFile(...)`, `.propertiesResource(...)`, direct sensitive properties, optional default-source disabling, and value-free property-resolution reporting.
- Added end-to-end sensitivity propagation and redacted conversion/configuration diagnostics that do not retain sensitive conversion causes.
- Added explicit base/overlay document composition. Replacement requires `replaces="sameId"`; accidental collisions and missing replacement targets fail.
- Added typed external bindings using `Class<T>` or `TypeRef<T>`, preserving generic API types for validation, factory discovery, lookup, and inspection.
- Added complete generic assignment and inherited type-variable resolution across classes/interfaces, wildcards, nested parameterized types, generic arrays, suppliers, optionals, collections, setters, and factories.
- Added generic `TypeRef<T>` context lookup and canonical `beanType(id)` inspection.
- Added explicit parent/child contexts with selected bean imports, parent-owned lifecycle, child leases, and close-order enforcement.
- Added structured, side-effect-free `inspect(...)` reports with compiled bean plans, dependency edges, creation/destruction order, property precedence metadata, and multiple independent configuration failures.
- Preserved 2.1 APIs and deterministic/lifecycle semantics.
- Expanded the adversarial integration suite from 59 to 69 tests.

## 2.1.0

Correctness-hardening release:

- Replaced the context-wide creation lock with per-singleton creation slots; no constructor, factory, converter, injection method, lifecycle method, or close callback runs under the context monitor.
- Added cross-thread singleton dependency-cycle detection and same-thread runtime cycle detection.
- Added root creation transactions that publish singleton instances atomically after successful initialization and roll back all partial lifecycle state on any nonfatal `Throwable`.
- Fixed rollback when a custom converter throws an `Error`, including cleanup of beans created earlier in the same eager/lazy/prototype transaction.
- Fixed plugin classloader retention by clearing declared classes and all executable/type caches during close; added an unloading regression test with a retained closed context.
- Added explicit prototype ownership. Prototype defaults to `ownership="caller"`; `ownership="context"` preserves tracked 2.0 behavior.
- Added `BeanHandle<T>` and `BeanContext.create(...)` for deterministic caller-owned prototype destruction.
- Made compiled graph and by-type lookup ordering stable and insertion ordered.
- Made close reject new operations, wait for active operations, and then destroy the committed graph.
- Expanded the adversarial integration suite from 49 to 59 tests.

## 2.0.0

- Added file and classpath includes, aliases, lazy beans, default lazy mode, depends-on, and singleton/prototype scope.
- Added externally supplied, caller-owned singleton bindings for host/application integration.
- Added instance factories, arbitrary method calls, and Java varargs binding.
- Added supplier and optional references, constants, `Properties`, immutable values, sorted/concurrent collection targets, and more converters.
- Added by-type context lookup APIs and side-effect-free validation.
- Added full graph precompilation before startup and built-in conversion preflight.
- Added transactional lazy/prototype creation rollback and retry.
- Added duplicate identity rejection, reverse-order destruction, aggregated shutdown failures, XML security limits, JPMS metadata, and a packaged XSD.
