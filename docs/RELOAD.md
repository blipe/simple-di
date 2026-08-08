# Safe reload architecture

`simple-di` 2.5 reloads complete object graphs. It never mutates an active graph or swaps individual bean references.

## State machine

```text
ACTIVE --publish replacement--> RETIRED --last lease/force--> CLOSED
```

A candidate remains private until it completes parsing, graph compilation, eager startup, and optional handoff. Publication is a single active-generation reference change. The previous generation stops accepting leases at that point.

## Typical use

```java
try (ReloadableBeanContext manager = XmlBeans.reloadable()
        .reloadPolicy(ReloadPolicy.GRACEFUL)
        .load(Path.of("beans-v1.xml"))) {
    try (ContextLease lease = manager.acquire()) {
        lease.require("service", Service.class).execute();
    }
    ReloadResult result = manager.reload(Path.of("beans-v2.xml"));
}
```

Every operation that touches generation-owned objects should stay inside a lease. A lease context view rejects calls after lease closure.

## Prepare and activate

`prepare(path)` performs validation and starts the complete candidate but does not publish it. This allows deployment policy to inspect `ValidationResult`, `ConfigurationDiff`, and the candidate revision. `activate()` compares the recorded base generation with the current active generation; a mismatch produces `STALE_CANDIDATE` and closes the candidate.

Closing a `PreparedReload` without activation always destroys its candidate.

## Revision semantics

The revision digest covers semantic XML, includes, overlays, condition outcomes, the exact property values frozen during candidate compilation, external-binding metadata, extension registrations, parent/classloader identities, and explicit host revision tokens. A secret contributes `HMAC(manager-local-key, value)`, never the raw value or a portable unsalted hash.

A revision is an optimization, not a state migration protocol. Equal revision means the candidate need not be constructed. Different revision means a complete replacement is built.

## Failure boundaries

Before publication, all failures close the candidate and preserve the active generation. This includes parser/compiler errors, constructors, factories, converters, injection, initialization, listeners, interceptors, custom scopes, and handoff.

After publication, old-generation cleanup failure is reported but cannot undo publication. `ReloadFailure` is intentionally value-only so storing results or events cannot retain plugin exception classes.

## Classloader ownership

The default is `EXTERNAL`: the host owns every classloader. `CLOSE_ON_RETIREMENT` is intended for plugins and requires the builder factory to return a distinct `AutoCloseable` loader for each changed generation. The manager closes it only after context destruction. Rejected candidates also release their owned loaders.

## Shutdown

Manager close rejects acquisitions/reloads, disposes prepared candidates, retires every generation, and applies the shutdown policy newest-to-oldest. It waits for a generation destructor already running on another thread. Forced lease closure first closes all lease-owned prototype handles, then releases the generation.

## JPMS targets

A target module may export its bean package only to `io.github.simpledi`; an unconditional export or `opens` directive is not required for public constructors and methods. The runtime uses cached method handles where available and a cached reflective invoker for qualified exports.
