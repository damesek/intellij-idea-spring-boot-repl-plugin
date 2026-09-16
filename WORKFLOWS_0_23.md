# Interactive Spring workflows - 0.23.0

This release connects recorded application calls to editable DATA, repeatable CASEs and HotSwap verification. The same workflows are available to MCP clients. It adds seven capabilities to the existing Java/Spring REPL.

| Capability | UI entry point | Result |
| --- | --- | --- |
| Recorded call to CASE | New recording: Capture replay DATA; selected call: Create CASE from call | Persistent typed input, expected output/exception and generated public bean/static invocation. |
| Edit DATA and make variants | Saved snapshots / Inspector: Edit DATA copy; CASE: Create variants | Validated detached JSON under a new name; parameterized CASE inputs. |
| Bean Explorer | Beans tab / Tools | Search, definition/proxy/dependency metadata, exact method overload and compatible DATA selection. |
| Pinned watches | Result: Watches / Watch result | Before/after field trees and changed paths after explicit eval/refresh. |
| Async flow | New recording: Link Executor / @Async / CompletableFuture tasks | Explicit task boundaries, queue wait, worker children and correlated SQL/ORM. |
| Affected CASE review | Cases / Reload: Reload + affected CASEs | Review suggested/unknown cases, reload successfully, then compare results and metrics. |
| MCP events and tasks | MCP: existing permissions; client resources/tasks APIs | 82 tools, event subscriptions, owned task IDs, status/results and cooperative cancellation. |

## Suggested workflow

1. Start the application with the updated agent. In New recording select focused application classes and enable Capture replay DATA before the real request.
2. Select a completed Java call and Create CASE from call. Choose the bean explicitly when more than one matches. Review the generated source, expected outcome and measured SQL/ORM budgets.
3. Run the CASE once to establish a session baseline. Edit its input as a new DATA copy; Create variants when several inputs should share the exercise. Review each row's expected DATA before running.
4. Change a supported Java method body. Reload + affected CASEs shows observed matches and unknown coverage for review. Tests run only after successful redefinition.
5. Inspect assertion outcomes and before/after result fingerprints, SQL/ORM counts and duration. Use the existing JUnit export to preserve the regression outside the IDE.

## Evidence and execution boundaries

- Full replay capture uses the snapshot codec, can invoke serializers/getters, and is opt-in. Limits are 2 MiB per input/outcome and 32 MiB per recording. It freezes entry arguments before application mutation. Future/CompletionStage results require capture of the completed value inside the task. Default graph previews do not invoke application getters.
- The portable `.sbrepl-recording` contains display evidence and source, not full replay DATA. Create the persistent CASE before clearing/replacing the runtime recording or restarting the target. Neither graph navigation nor opening an archive restores a JVM stack.
- Interactive DATA editing has a 2 MiB limit. Source checksums and unused names are checked under the repository lock. Related CASE/DATA entries are published together without overwriting concurrent writers. The original live object and DATA remain unchanged; typed validation can execute constructors/deserializers.
- Bean discovery reads definitions and existing singletons without initializing lazy beans. Dependency edges are those Spring has resolved so far. Prepared calls retain public proxy contracts; executing them can instantiate beans and produce real effects.
- Watches are session-local, bounded to 20 and sampled only after explicit eval/refresh. Default paths do not call application getters or initialize unfetched Hibernate attributes. Java method expressions require a separate opt-in. Partial/unreadable data is never reported as proven equal.
- Async links cover tested Java 17/21 executors and Spring @Async, including CompletableFuture continuations. Only recording identity crosses threads. Transactions, security/tenant context, MDC and arbitrary ThreadLocals are not copied; parent rollback cannot guarantee worker rollback. Unobserved executors/Reactor/remote messages remain unlinked. Pending/drop counts expose incomplete evidence.
- Affected CASEs use observed classes, not complete coverage. Unknown and unmatched cases can still be affected. Changed DATA, CASE definitions, context or execution policy invalidates comparison baselines. A single duration measurement is diagnostic context, not proof of a performance regression.
- MCP permissions and per-tool allowlists remain enforced. Resource events contain metadata, not code or captured values. Tasks are session-owned, in memory, bounded and cooperatively cancellable; cancellation does not undo effects or free a still-running evaluator. Never automatically retry a mutation after uncertain transport failure. Client support for experimental MCP tasks varies.

## Documentation

The [Hungarian](docs/repl-help-hu.md) and [English](docs/repl-help-en.md) guides cover all 12 main tabs and 82 MCP tools; chapters 43–49 explain the new workflows. Both PDFs are bundled under Help (PDF) and checked into `output/pdf/`. [Claude instructions](docs/claude-repl-instructions.md) and the [wire protocol](PROTOCOL.md) are updated together.

## Verification

Regression commands for this release:

```bash
./gradlew check -PtestJdk=17
./gradlew check -PtestJdk=21
./gradlew verifyPlugin
./gradlew buildPlugin -PtestJdk=17
```

Coverage includes typed captured-call replay/JUnit export, detached edit conflicts and stale sources, public JDK/CGLIB proxy advice, lazy bean discovery, watch state, CASE baseline invalidation, bounded metadata events, cross-client task/event isolation and real authenticated HTTP SSE. Packaged-agent subprocesses exercise ThreadPoolExecutor, CompletableFuture continuations, Spring SimpleAsyncTaskExecutor and proxied @Async/ThreadPoolTaskExecutor with correlated H2 SQL on Java 17/21. Existing Spring MVC/JDBC/Hibernate, rollback, snapshot and UI suites remain part of check. The responsive async graph is rendered and visually reviewed at 700 and 1280 pixels.

On September 16, 2026, **332 tests passed on each JDK**, with zero failures, errors or skips: 135 IDE/plugin tests, 14 JShell integration tests and 183 runtime tests. `buildPlugin` succeeded. The Plugin Verifier reports **Compatible** against both IC-252.23892.409 and IU-252.23892.409 (IntelliJ 2025.2), with 20 deprecated and 2 scheduled-removal API usages on each edition. These existing API warnings remain maintenance work; compatibility beyond the declared `252.*` range is not claimed.

Both Maven agent/bridge packages also build successfully. The Gradle and Maven agent package checker verifies the new runtime and bootstrap classes. The final plugin ZIP contains the matching agent and both 69-page guides, with no duplicated IntelliJ API classes. Each guide has 51 bookmarks; all 82 tool names are present. Pages were rendered for visual review and the bundled PDFs match the repository copies and source hashes.

The tests use isolated fixtures; they do not establish compatibility with every application configuration, executor implementation or external MCP host. Install `build/distributions/sb-repl-0.23.0.zip` through Plugins > Install Plugin from Disk, then restart IDEA and the target application so that the new agent is active.
