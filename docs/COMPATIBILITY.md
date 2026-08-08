# Compatibility and operational contract

- JDK 21 language and class-file level.
- No runtime third-party dependencies.
- Named module `io.github.simpledi`; only `io.github.simpledi` is exported.
- User classes and invoked public members must be publicly accessible. In named modules, their packages must be exported to `io.github.simpledi` or unconditionally exported.
- XML has no namespace. `version="2"` is the current grammar marker; unversioned documents remain accepted. Unknown versions, elements, and attributes are errors.
- Bean IDs, external binding IDs, and aliases are case-sensitive and globally unique after includes.
- Singleton is the default scope and context-owned by default. `ownership="external"` suppresses container destruction for a container-created shared object.
- Prototype creates a fresh instance on every reference, lookup, or supplier call.
- Prototype ownership defaults to `caller`. Caller-owned prototypes are never retained by the context. Prefer `BeanContext.create(...)` and `BeanHandle<T>` when deterministic destruction is required.
- `scope="prototype" ownership="context"` preserves the 2.0 tracked behavior and destroys every created instance in reverse order at context shutdown. Use it only for bounded workloads.
- A caller-owned prototype injected as a raw reference or `Supplier<T>` transfers cleanup responsibility to the consuming object/application.
- `lazy` applies only to singletons. Prototype beans are inherently on demand.
- External builder bindings are injectable and discoverable but remain caller-owned; no init, injection, destroy, or `AutoCloseable.close()` is applied to them. XML `ownership="external"` still allows creation/injection but suppresses destruction.
- A supplier obtained from a bean remains linked to its context and fails after that context closes.
- Context close rejects new operations and waits for already-active operations. Calling `close()` from inside an active context operation is rejected.
- Custom converters may execute during value preparation. They should be deterministic and side-effect free. Any thrown nonfatal `Throwable` fails and rolls back the current root creation transaction.
- Concrete collection/map targets need a public no-argument constructor.
- Sorted collections/maps require mutually comparable keys/elements unless the concrete target supplies its own comparator.
- Property precedence is direct builder values, later custom/property-file sources, earlier custom sources, system properties, then `env.NAME`. `withoutDefaultPropertySources()` removes the final two sources.
- Sensitive property values are redacted from diagnostics and validation metadata. Custom property sources must mark secrets with `PropertyValue.secret(...)`; otherwise the container cannot infer sensitivity.
- Overlay order is deterministic. Replacing a prior bean requires `replaces` to equal the replacement bean ID. Includes remain composition within one layer and cannot silently replace definitions.
- External bindings and parent imports are validated against their declared `Class<T>` or `TypeRef<T>`, not their implementation class. Untyped `bind(id, object)` intentionally exposes the runtime class.
- Generic validation is invariant except where the target uses a wildcard. Raw types cannot prove compatibility with parameterized targets.
- Parent access is import-only. Imported instances remain parent-owned. A parent context rejects close while any child lease is active.
- `inspect(...)` is side-effect free and returns independent executable/type failures where parsing and the global graph are sufficiently valid to continue. `validate(...)` retains validate-or-throw behavior.

- Caller-supplied `InputStream` and `Reader` instances are never closed by simple-di. Inline inputs cannot use includes.
- File includes are confined to the root document directory by default and classpath includes to the root resource directory; hosts may set an explicit root or disable either include mechanism.
- Exact executable signatures use erased parameter classes. Named record arguments do not need `-parameters`; named ordinary executable arguments do. Indexed arguments do not.
- `BeanContextListener` callbacks are synchronous, run outside container monitors, and participate in rollback/failure aggregation.
- `auto-close` defaults to `fallback`; `before`, `after`, and `never` are explicit lifecycle policies.

## 2.4 activation, scopes, interception, and diagnostics

- Bean activation conditions are valid only on top-level beans and are evaluated before class loading and graph compilation. Inactive definitions and aliases targeting them are omitted from the active graph.
- `if-property` matches property presence, or equality when `if-value` is supplied. `match-if-missing` is valid only with `if-property`. `unless-property` already matches absence and optionally tests inequality through `unless-value`.
- Condition reports never include selected, expected, or actual values. A sensitive source is identified only by source name and a sensitivity flag.
- Custom scope names match `[A-Za-z][A-Za-z0-9._-]*` and cannot use `singleton` or `prototype`.
- A registered `BeanScope` is owned by exactly one context. The context closes scopes in reverse registration order before destroying context-owned singleton lifecycle entries.
- `BeanScopes.keyed(...)` keys and values are strongly retained until `release(key)`, `releaseCurrent()`, or context close. The host must release request/job/session keys promptly.
- Standard keyed scopes publish only after the root creation transaction commits, cancel and remove failed reservations, close values in reverse publication order, and detect cross-thread wait cycles. Third-party scopes must provide equivalent liveness guarantees.
- A singleton may not directly depend on a custom-scoped bean or caller-owned prototype. Use `Supplier<T>` for resolution inside the active scope. Custom-scoped beans may own caller prototypes created as part of their scoped subgraph.
- Lifecycle interceptors execute construction and pre-initialization in registration order, post-initialization in reverse order, pre-destruction in reverse order, and post-destruction in registration order. Failures participate in rollback or shutdown aggregation.
- `ValidationResult.diff(...)` is structural and value-free. It reports plan, alias target, dependency, property provenance, and condition-state changes, but intentionally does not fingerprint literal or resolved values.
- `BeanContext.snapshot()` exposes names and counts only, never instances, and never causes lazy or scoped creation.


## 2.5 reload contract

- A reload candidate is parsed, compiled, constructed, initialized, and handed off before publication. Pre-publication failure never changes the active generation.
- Publication replaces one generation reference atomically. A lease observes exactly one generation for its lifetime.
- Existing leases remain usable under graceful retirement; new leases never enter a retired generation.
- A prepared candidate is bound to its base generation and cannot publish after another candidate has replaced that base.
- Reload attempts are serialized. Ordinary lease acquisition and use remain concurrent with candidate preparation.
- `GRACEFUL` destroys a retired generation after its final lease. `GRACEFUL_WITH_TIMEOUT` may return with a still-draining retired generation. `IMMEDIATE` forcibly closes leases. `REJECT_WHILE_BUSY` does not publish while leases exist.
- Manager shutdown rejects new work, disposes unactivated candidates, retires all generations, applies the configured shutdown policy, and aggregates cleanup failures.
- Caller-owned prototype handles created through a lease are lease-owned and close in reverse creation order before that lease releases its generation.
- Revisions freeze each used property on first resolution. Sensitive values are represented only by a manager-keyed HMAC. Revisions and reload diagnostics expose no configuration values.
- Equal revisions skip construction. Hosts must provide `.revisionToken(...)` when an extension's behavior changes without its registration identity changing.
- Graph state is never reused or migrated implicitly. `GenerationHandoff` is the explicit state-transfer boundary and runs before publication.
- Custom scopes and generation-specific classloaders require a fresh builder from `builderFactory(...)` for each generation.
- `CLOSE_ON_RETIREMENT` requires a distinct `AutoCloseable` classloader for every changed generation. The loader closes only after generation leases and context lifecycle complete.
- `ReloadResult`, `ReloadEvent`, and `RetiredGeneration` retain only value diagnostics for application failures; they never retain an application `Throwable`, stack trace, reflective type, or bean instance.
- Reload listeners are synchronous. Failure before publication aborts the candidate; observer failure after publication or during destruction cannot roll publication back.
