# Spring Boot Debug REPL and MCP bridge

The optional 0.24.0 bridge exposes a **ready** Spring context to the development agent. Normal startup through the IntelliJ **Enable Spring Boot Debug REPL and MCP** checkbox already instruments startup, so the bridge is primarily useful when attaching after the application has started and for application capture/tap calls.

Build the bridge locally using `./gradlew :sb-repl-bridge:jar` or `mvn -f sb-repl-bridge/pom.xml package`. The project version is `hu.baader:sb-repl-bridge:0.24.0`; publication to a remote Maven repository is a separate release step.

`DevRuntimeBridgeConfig` records its own context on `ApplicationReadyEvent`, clears it on `ContextClosedEvent`, and ignores unrelated child contexts. It can remember readiness before an agent is attached. The agent remains the sole owner of `com.baader.devrt.SpringContextHolder`; the bridge contains no duplicate class with that name. Disable the bridge using `sb.repl.bridge.enabled=false`.

After explicitly choosing the JVM in **Attach & Inject Dev Runtime**, the plugin uses its private endpoint to connect. The ready context is exposed as `ctx` in the Java REPL. A context restart expires old session objects and requires Reset.

`com.baader.sbrepl.bridge.SnapshotHelper` delegates to the canonical runtime snapshot repository, resolving the agent on each call so an earlier absent agent is not cached forever:

```java
SnapshotHelper.pin("live-object", object);
SnapshotHelper.save("data", dto);
var restored = SnapshotHelper.load("data");
```

LIVE references have session/application scope and expire. DATA is an atomic JSON snapshot capped at 200 MiB including metadata and requires application Jackson. `save` is synchronous; DATA captured from an application callback is accessible from the REPL in the same application namespace. Its serialized size is not a heap limit: loading/importing large data needs additional application memory. Missing codecs and serialization errors are reported; saving does not silently switch modes. Calls made from ordinary application threads use application scope; LIVE pins in a REPL session belong to that session. See the [handbook](../docs/repl-help-en.md) for generic types, mix-ins, limits and migration.


## Capture rules

```java
import com.baader.sbrepl.bridge.SnapshotHelper;

SnapshotHelper.capture("cv-input", requestId, inputDto);
// Build an expensive projection only when the next matching capture is armed:
SnapshotHelper.captureLazy("cv-input", requestId, () -> projectToDto(input));
```

Arm the point from **Snapshots → Capture next** in the REPL. Set a snapshot name, optional exact case ID filter, capture count (1–100) and sampling interval (1–10000). A matching application call claims the applicable rules and returns `true` when capture succeeds. Non-matches, exhausted rules, expiry or an absent agent return `false` without calling the supplier. Projection/serialization failures return `false` and appear in trigger status. Fatal JVM errors are not swallowed. The ordinary `save` API continues to throw on failure.

There are up to 16 independent session-owned rules per JVM. Multiple saves receive a sequence suffix, or use `${sequence}` in the output name; overlapping active output names are rejected. Pending rules expire after five minutes in the UI and are released on reset, disconnect or context replacement. A capture already claimed can finish. Saving is synchronous: it captures the caller's selected data before that caller continues. Snapshot mix-ins from the arming session are copied before projection begins, and overlapping rules share one supplier invocation. Use stable DTOs; this is not a transaction over concurrent mutations.

Build/install the matching version from this checkout with `mvn -f sb-repl-bridge/pom.xml install -Dgpg.skip=true`, then use `hu.baader:sb-repl-bridge:0.24.0` in the development application. A source checkout or local build does not imply that this version has been published to Maven Central.

## Live values

`SnapshotHelper.tap("cv-input", inputDto)` sends a live value to explicitly subscribed REPL sessions. Enable **Tap / Trace → Start tap** in IDEA, optionally with an exact label filter. An absent agent or no matching subscriber returns `false`; the call does not serialize or save the object. Double-click the event to inspect it, bind a nested value, or freeze a DATA snapshot. Event retention is 128 references and five minutes per session. See [the current handbook](../docs/repl-help-en.md).
