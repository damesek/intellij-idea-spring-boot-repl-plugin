# Spring Boot REPL

An IntelliJ workflow that turns live Spring Boot behavior into reusable snapshots, repeatable checks, and JUnit tests — accessible to developers and AI agents through MCP.

Evaluate Java inside your running Spring Boot application from IntelliJ IDEA. Inspect real bean results, capture a failing input, reproduce it in a workbook, update supported method bodies with HotSwap, and turn the result into a CASE or JUnit test.

**Current version: 0.23.0 · IntelliJ IDEA through 2025.2.x · Java 17/21 tested · 82 MCP tools**

[English PDF handbook](output/pdf/spring-boot-repl-guide-en.pdf) · [Magyar PDF kézikönyv](output/pdf/spring-boot-repl-guide-hu.pdf) · [Claude setup](docs/claude-repl-guide-hu.md) · [AI agent instructions and tool reference](docs/claude-repl-instructions.md) · [Protocol](PROTOCOL.md)

## What is included

| Capability | Where to use it |
| --- | --- |
| Persistent Java/JShell session, Spring `ctx` and bean access | **Java REPL**, **Variables** |
| Workbook cells, completion, compiler checks, inline results and stale-state indicators | **Java REPL**, Java editor |
| Object inspection, formatted JSON and expandable value trees | **Inspector**, result views |
| LIVE references, DATA snapshots up to 200 MiB, RECIPE source, versions and field comparison | **Snapshots** |
| Application capture rules and debugger snapshot points | **Snapshots → Capture next**, editor/gutter context menu |
| Recorded call graph, source values, filters, timeline, JDBC/SQL and suspected N+1 | **Tap / Trace → Recorded calls** |
| Transaction modes, reproduction bundles, assertions, parameterized CASEs and JUnit export | **Java REPL**, **Cases / Reload** |
| Workspace save/restore, supported HotSwap and debugger object transfer | Workbook menus, **Cases / Reload**, **Debugger** |
| Saved HTTP requests, reviewed AI prompts and a local MCP server | **HTTP**, **AI**, **MCP** |

Version 0.23 adds **recorded-call CASE creation, editable DATA copies/variants, Bean Explorer, watches, async handoffs, affected-CASE review after HotSwap, and MCP tasks/subscriptions**. See [workflow details and validation](WORKFLOWS_0_23.md).

| New workflow | Where to use it |
| --- | --- |
| Full typed call input/outcome → CASE | **Recorded calls → New recording → Capture replay DATA**, then **Create CASE from call…** |
| Detached JSON editing and parameter variations | **Snapshots → Edit DATA copy**, **Cases / Reload → Create variants…** |
| Bean metadata/dependencies and prepared calls | **Beans**, or **Tools → Bean explorer** |
| Pinned before/after field changes | **Java REPL → Watches**, **Watch result** |
| Executor / Spring @Async / CompletableFuture task links | **New recording → Link … tasks**, dashed **Async handoff** nodes |
| Reviewed HotSwap followed by suggested cases and result/SQL/ORM comparison | **Cases / Reload → Reload + affected CASEs…** |
| Client notifications and identifiable/cancellable long work | MCP `repl://session/events`, optional MCP 2025-11-25 tasks; **82 tools** |

Replay DATA capture is opt-in (2 MiB input/result, 32 MiB per recording) and can invoke snapshot serializers. The portable display recording does not contain full replay DATA: create the persistent CASE before replacing/resetting the runtime recording. DATA editing uses a new name and checks the original version. Affected CASEs are suggestions from observed classes, not complete coverage. Watches sample only after explicit evaluation/refresh; Java method expressions require an opt-in.

Async capture propagates recording identity only. It does not move transactions/security/MDC to a worker, and rollback of the submitting thread does not undo worker effects. MCP task cancellation is cooperative; a cancelled task does not make an evaluator available until it returns. Task results and event journals are bounded, session-local and not durable across server restarts.

Version 0.22 adds **Hibernate 6.6 entity/lazy/flush/cache/transaction evidence, ORM-aware N+1 hints, lazy-safe inspection, CASE ORM limits and three more MCP tools**. See [Hibernate implementation and validation](HIBERNATE_0_22.md).

Version 0.21 adds **JDBC/SQL nodes, suspected N+1 alerts, SQL baselines, CASE SQL budgets and three MCP SQL tools**. Synchronous Spring MVC request roots connect selected application calls to observed JDBC operations. See [SQL recording and validation](SQL_RECORDINGS_0_21.md).

## Hibernate in the recorded flow

In **New recording…**, leave **Record Hibernate 6.6 entity / session events** enabled together with SQL. The graph and timeline show **Java call → Hibernate operation → JDBC**, with real parent IDs. Open an ORM node or the **Hibernate** detail tab, search by entity/relationship/source, or choose **Findings**. It captures entity load/insert/update/delete, proxy/collection/enhanced-attribute initialization, flush/dirty checking, transaction begin/commit/rollback/completion, and L2/query-cache hits/misses/puts. Query text is normalized; entity IDs and field values are not included in ORM events.

**Open originating call** returns to the recorded Java call and source; **Open entity mapping** opens the current entity class. **Pin Hibernate baseline** compares the selected request root across recordings. Repeated lazy initializations become an ORM N+1 hint only when correlated with actual SELECTs. Lazy loading during synchronous MVC response handling has its own finding. Existing application listeners/StatementInspector are preserved; global Hibernate statistics are never enabled, reset or subtracted.

**Inspector** shows proxy/collection initialization and observed managed/detached state without initializing them. Enhanced lazy fields are marked unfetched. In **Cases / Reload → Options**, set optional entity-load, flush, lazy-initialization and response-lazy limits. Incomplete evidence cannot pass a bound. JUnit export retains the limits and includes a matching `runtime/sb-repl-agent.jar`; **ORM assertions require that agent in the test JVM**. SQL-only JUnit exports remain agent-free.

MCP adds `repl_recording_hibernate`, `repl_recording_hibernate_findings`, `repl_recording_hibernate_compare` under the existing recording-sharing permission. The catalog now has **82 tools**. ORM events have an independent 1000-event / 1.5-million-character budget. The tested adapter is **Hibernate 6.6.29.Final, Java 17/21, synchronous sessions**; unsupported versions and incomplete observations are explicit. No inferred async/reactive propagation, L1-cache hit counts or database-internal plans are claimed. An unavailable source/association stays unknown.

## Follow a request to the database

Open **Tap / Trace → Recorded calls → New recording…**, choose the controller/service/repository classes (1–8), and leave **Record JDBC/SQL and synchronous Spring MVC requests** enabled. Trigger the request in the application, then stop recording. The graph adds real JDBC children; **Group SQL** collapses matching operations under the same parent, while the timeline keeps individual spans.

**SQL & N+1** shows sanitized SQL, execution count, client duration, datasource identity, error type and source location. Expand a group to see each SQL observation and its actual parent. **Open originating call and source** returns to the captured application call. The default suspected N+1 threshold is five repeated SELECTs per request, datasource, query and call site; change it to 2–1000 when starting a recording. Repetition is a hint to investigate, not proof of a fetching defect.

Choose a **Root** filter and **Pin SQL baseline**, fix/reload the code, start a new recording and select the corresponding root. The baseline survives and compares query count, total JDBC time and maximum repetition. In **Cases / Reload → Options**, set **Maximum SQL executions** and/or **Maximum SQL repetition** (zero allowed). The bounds cover code and result expression, excluding setup/cleanup. SQL-only JUnit export preserves them with a portable Spring DataSource counter; no agent is needed for that exported test.

SQL literals are replaced with `?`; bound parameters, credentials and result rows are not captured. A JDBC batch counts once. Connection acquisition is measured separately. Each recording keeps at most 1000 JDBC observations / 1.5 million encoded characters, independently of the 200 Java-call limit. Omitted, unfinished or unavailable evidence is explicit; CASE assertions cannot pass on incomplete SQL evidence.

This implementation follows **synchronous Spring MVC and JDBC/JPA**, through the explicitly selected Java classes. Unselected intermediate methods are not invented. Opt-in Executor/@Async/CompletableFuture handoffs connect supported worker threads in 0.23. General reactive propagation, R2DBC and database-internal execution plans/locks are outside this capture. A datasource label identifies its class and instance, not its URL/password. Late attach can leave pre-existing prepared statements without SQL text; these are marked incomplete.

## Quick start

1. With JDK 17 installed, run `./gradlew :buildPlugin`. This includes regression checks and produces `build/distributions/sb-repl-0.23.0.zip`.
2. In IDEA, choose **Settings → Plugins → Install Plugin from Disk**, select that ZIP and restart the IDE.
3. Open your existing **Spring Boot** or **Application** Run Configuration and enable **Enable Spring Boot REPL**. Keep its main class, **Active profiles**, environment, VM options and program arguments.
4. Start the application, open the **Spring Boot REPL** tool window and wait for **READY**. The ready application context is available as `ctx`.

The agent is bundled; normal startup through the checkbox needs no application dependency. Port `0` chooses a free loopback port. A private endpoint file carries the actual port, process ID and connection token. Plugin settings also support an endpoint file or a custom matching agent JAR.

For an **Application** configuration, activate profiles using `--spring.profiles.active=dev,llm-openai` as a program argument, or the corresponding VM property/environment variable. A bare `dev,llm-openai` argument does not activate profiles. The dedicated legacy **Spring Boot REPL** configuration remains readable; the normal Spring Boot configuration provides Spring-specific settings.

To attach after startup, use **Tools → Attach & Inject Dev Runtime** and choose the JVM explicitly. The optional [Spring bridge](sb-repl-bridge/README.md) exposes an already-started context and provides application capture/tap helpers. The target must permit the Java Attach API.

## Work in a persistent Java session

Imports, variables and methods survive between evaluations:

```java
// %% Setup
int limit = 10;
int doubled(int n) { return n * 2; }

// %% Experiment
doubled(limit)
```

A standalone `// %%` line starts a cell; a title is optional. **Run cell / selection** evaluates the selection, or the current cell when nothing is selected. **Run all** runs cells sequentially and stops on errors or edits. Saving/opening a workbook, inserting history or loading a recipe does not execute code.

| Action | Shortcut or location |
| --- | --- |
| Run cell / selection | Ctrl+Enter / Cmd+Enter |
| Run and advance to the next cell | Shift+Enter |
| Complete at the caret | Ctrl+Space |
| Evaluate Java source or a selection | **Code → Spring Boot REPL** or the editor context menu |
| Find commands / assign shortcuts | IDEA **Find Action**, search `REPL:`; configure in **Keymap** |

Use **Insert bean** to browse names and types without instantiating lazy/prototype beans. Retrieve a bean explicitly using your application's public types:

```java
// Replace this type and method with ones from your application.
var users = ctx.getBean(com.example.UserService.class);
var page = users.findActiveUsers(limit);
page
```

- `last1`, `last2` and `last3` retain recent results; `lastError` records the last evaluation error.
- **Inspect result / Variables** navigate existing objects without rerunning the expression. **Value → Tree / Formatted / Raw** formats JSON strings and displays bounded Java field previews.
- **Live check / Check code** checks syntax and types against the session and preceding workbook declarations without executing initializers or methods. This is compiler checking, not a complete static-analysis rule set.
- Cell inlays show execution number, duration, output and source changes. **Analyze dependencies**, **Run above**, **Run from here**, **Run affected** and **Restart + run all** help reproduce an execution order.
- **Evaluate at Caret / Run Selection** shows results beside Java source. Execution uses JShell; method-local variables require debugger transfer or a snapshot point.
- **Interrupt** is cooperative. **Reset** discards session definitions and live references. A replaced Spring context invalidates old objects; code is never automatically replayed.

## Browse actual calls beside the source

From a Java file, choose **Spring Boot REPL → Record Class Calls…**, then trigger the relevant application request. Explore it in **Tap / Trace → Recorded calls**:

- Scroll, zoom, pan, **Fit graph**, **Show selected**, or detach the browser into a separate window.
- Collapse branches and filter by value search, root, thread, error, duration or time range.
- Navigate with **Previous / Next / Next error / Caller** and clickable caller paths.
- Open a node's captured inputs, result/exception tree and corresponding source.
- Use **Pin reference → Compare calls** for field-level differences.
- Select a range on the per-thread **Timeline** to filter the graph.
- Save/open `.sbrepl-recording` files for offline browsing.

Selecting a node opens its source with the captured input and result. If the current source differs, a captured source copy remains available. Recording observes future calls in 1–8 selected classes, with a maximum of 200 calls and a 32 MiB preview budget. Supported task handoffs can be recorded explicitly; unsupported async/reactive relationships are not inferred. Values are bounded display evidence, not a saved JVM stack or restorable live object.

See the [recording guide](RECORDED_CALLS_0_18.md) and [graph navigation guide](CALL_BROWSER_0_19.md).

## Capture and reuse data

| Snapshot mode | Contents | Lifetime |
| --- | --- | --- |
| **LIVE** | Existing object reference; later mutations remain visible | Current session, up to 30 minutes |
| **DATA** | Typed JSON envelope, loaded as a new value | Persistent, including across JVM restarts when its type is available |
| **RECIPE** | Java source for explicit reuse | Persistent; loading does not run it |

Use **Pin LIVE / Freeze DATA** on an existing result or variable. DATA uses a copy of an already-created application Jackson `ObjectMapper`, or a separate mapper with discoverable modules. Snapshot-specific mix-ins can omit fields without changing application serialization. Prefer DTOs, records or projections for portable fixtures.

```java
import com.baader.devrt.SnapshotManager;

SnapshotManager.save("active-users", page);
var restored = SnapshotManager.load("active-users");

// Preserve collection element types explicitly:
SnapshotManager.save("user-dtos", dtos, "java.util.List<com.example.UserDto>");
var restoredDtos =
    SnapshotManager.loadTyped("user-dtos", "java.util.List<com.example.UserDto>");
```

**DATA is capped at 200 MiB per envelope**, including metadata and UTF-8 encoding. **Export file / Import file** support large snapshots; inline export and **Paste JSON** retain a 2 MiB limit. File operations require IDEA and the application to access the same local path. Loading/importing can require several times the serialized size in heap.

Snapshots live under `~/.java-repl-snapshots/v1/<application-hash>/`. Set `-Dsb.repl.applicationId=my-app` for a stable identity across launch layouts. Atomic writes preserve the previous snapshot if serialization fails. **Versions / Provenance / Load version / Restore as latest** expose immutable, checksummed revisions; up to 100 versions per name are retained, and explicit deletion removes that history. **Compare** reads stored DATA without instantiating DTOs.

### Snapshot points in the editor

Start the application in **Debug** mode with REPL enabled. Select an initialized local variable, then right-click the source or gutter and choose **Snapshot point…**. Set the snapshot name, optional type and capture count. A purple gutter marker saves DATA when execution reaches that line, then resumes the application. Use **Configure snapshot / rearm…** to rearm it.

This briefly pauses debugger execution while evaluating and serializing. Normal **Run** mode does not capture arbitrary line-local variables. See the [snapshot point guide](REPL_UX_0_17.md).

### Triggers in application code

Add the optional matching [0.23.0 bridge](sb-repl-bridge/README.md) to your development application:

```java
import com.baader.sbrepl.bridge.SnapshotHelper;

SnapshotHelper.capture("order-processing", requestId, inputDto);
// Project only after an armed rule matches:
SnapshotHelper.captureLazy("order-processing", requestId, () -> projectToDto(input));
```

In **Snapshots → Capture next**, choose the point, output name and optional exact request/case ID, then arm a rule. Up to 16 independent rules can request 1–100 captures with sampling. Sequence suffixes keep multiple captures distinct. Unarmed/non-matching calls or an absent agent do not invoke the lazy supplier. Saving remains synchronous on the calling thread; capture failures appear in rule status without failing the application request.

`SnapshotHelper.tap("order-input", inputDto)` instead sends a live value to explicitly subscribed **Tap / Trace** sessions without persisting a DATA snapshot.

## Execution modes, reproduction and tests

The workbook displays the server-confirmed **LIVE / DB rollback / DB read-only** mode. Choose the transaction manager and timeout in **Session → Execution settings**. Java and CASE execution wait until the mode is confirmed.

Both transaction modes roll back participating synchronous database work after success or failure. Read-only is a transaction-manager/driver hint. HTTP calls, messages, files, in-memory mutations, independent `REQUIRES_NEW` transactions and work on other threads are outside that rollback. **Side-effect hints** flags source patterns without running code; it is not an outbound-call firewall. Interrupt/reset do not provide general undo.

A typical workflow is:

1. Capture a failing input as DATA and load it into the workbook.
2. Run a small reproducing snippet against the real application bean.
3. Use **Create reproduction** with the original DATA to create input/expected snapshots, CASE, RECIPE and available environment metadata.
4. Export a `.sbrepl-bundle` to share it. Import assigns new names and executes no code; recorded profiles, principal, tenant or timezone are evidence, not automatically restored execution context.
5. Update a supported method body with **Reload Class**, then **Reload + run selected** CASEs.
6. **Export JUnit 5 / AssertJ** to obtain test source and JSON resources for the application's normal test build.

**CASE 2.0** supports include/ignore paths, unordered lists, numeric/time tolerances, regex and structural conditions, expected exceptions, duration assertions, parameter rows, setup/cleanup, tags and disabled cases. Runs use fresh JShell sessions and the selected execution policy. Batches run sequentially; workbook definitions remain separate.

HotSwap is explicit and preserves existing objects for supported method-body changes. Structural changes such as adding fields/methods require a restart on a standard JVM. JUnit export uses application types and configuration, which must be checked in the target project.

See [execution and reproduction](SAFETY_REPRODUCTION_0_14.md) and [CASE/JUnit details](CASE_2_0_0_15.md).

## Save the workspace

**Save workspace / Open workspace** exchange a checksummed `.sbrepl-workspace` containing workbook source and execution evidence, imports, DATA bindings, snapshots/CASEs/recipes, Inspector bookmarks, optional HTTP requests/transcript and observed environment.

Imported snapshots get new names and CASE references are remapped. Use **Restore DATA binding** and **Insert saved imports** to resume explicitly. Recovery checkpoints retain source and stale execution evidence; live objects, database state and the original call stack are not restored. Source literals mentioning old snapshot names may need adjustment after import.

See the [notebook and workspace guide](NOTEBOOK_WORKSPACE_0_16.md).

## Give Claude and other AI agents MCP access

1. Open **Spring Boot REPL → MCP** while the server is stopped.
2. Select the required permissions and, for Java/CASE execution, its transaction mode, manager and timeout.
3. Enable **Share IDE recordings with MCP** to expose the project's recorded calls.
4. Click **Start MCP → Copy client config** and add that configuration to your client.

The server listens on `127.0.0.1` and creates a new token on each start. Refresh the client's configuration and tool list after restarting it. Each MCP client has its own persistent Java session; the IDE recording is shared separately and each client has its own frozen comparison reference.

The catalog contains **82 tools**, filtered by permissions and **Choose allowed tools**. Snapshot writes/deletion, CASE runs, capture/trace changes and HotSwap have separate controls. Execution mode, timeout, result limits and per-session quota are enforced at the MCP entry point. Recognized secret patterns are redacted by default and operations are audited locally.

| Recording tools added in 0.20 | Purpose |
| --- | --- |
| `repl_recording_status`, `repl_recording_calls`, `repl_recording_call` | Recording state, filtered/paged call tree and invocation navigation |
| `repl_recording_values`, `repl_recording_source`, `repl_recording_timeline` | Captured values, captured source and timing |
| `repl_recording_pin`, `repl_recording_compare` | Per-client reference and structural comparison |
| `repl_recording_select` | Select a node and open its source/values in IDEA |
| `repl_recording_start`, `repl_recording_stop` | Control application-call recording |

Reading shared recordings requires no Java execution permission. Start/stop additionally require **Allow Java execution / state changes** and **Allow capture / trace changes**; IDE selection requires the state-change permission. Tool permissions do not sandbox arbitrary Java inside the target JVM.

Use the [Claude connection guide](docs/claude-repl-guide-hu.md) and give your agent the [standalone instructions with all tools and arguments](docs/claude-repl-instructions.md). The [0.20 report](MCP_RECORDINGS_0_20.md) explains recording IDs, paging, partial evidence and permissions.

## HTTP, AI and documentation

The **HTTP** tab stores requests with `${ENV_NAME}` placeholders, explicit execution, cancellation and bounded responses. **AI** prepares a prompt for review; only **Send** contacts the configured provider, and inserting a response does not execute it. HTTP values and API keys use IntelliJ PasswordSafe. History is in memory by default; persistence is optional.

The **English and Hungarian handbooks** each cover **49 chapters**, all **12 main tabs**, their buttons/fields, keyboard actions and all **82 MCP tools**, including call graphs, SQL/N+1 and Hibernate:

- [English PDF](output/pdf/spring-boot-repl-guide-en.pdf) · [Magyar PDF](output/pdf/spring-boot-repl-guide-hu.pdf).
- Both are bundled in the plugin. Open **Code → Spring Boot REPL → Help (PDF) → English / Magyar**, or the same submenu in the Java editor context menu. Workbook **Tools → Help (PDF)** and the Help action open a language picker. No application connection or internet is needed.
- Editable sources: [English](docs/repl-help-en.md) · [Magyar](docs/repl-help-hu.md). See also [Claude setup](docs/claude-repl-guide-hu.md) and the [protocol reference](PROTOCOL.md).

Rebuild both PDFs with `python3 scripts/build-help-pdf.py` using ReportLab and DejaVu Sans/Mono or Arial/Courier New fonts. Use `--language en` or `--language hu` for one edition (`--output` requires a single language). The generator updates repository copies, bundled resources and source hashes. Normal plugin builds need no Python: `verifyBundledHelp` checks both manuals for source freshness and identical repository/bundled copies before packaging.

## Build and verification

The declared IDEA range is `241.0–252.*`. Compilation targets IC 2024.1.4; current compatibility verification covers **Community and Ultimate 2025.2**. Java source targets 17, with regression tests on JDK 17 and 21.

```sh
# Regression checks and installable ZIP; JDK 17 toolchain required
./gradlew :check :buildPlugin

# Repeat tests with a locally available JDK 21 toolchain
./gradlew :check :buildPlugin -PtestJdk=21

# Binary compatibility against Community and Ultimate 2025.2
./gradlew :verifyPlugin -PtestJdk=21

# Separate compatibility run for IC 2024.1.4 and IU 2025.1.7
./gradlew :verifyPlugin -PtestJdk=21 -PverifyPreviousIdes=true
```

The [0.23 validation](WORKFLOWS_0_23.md) records **332 passing tests on each JDK** and **Compatible** results on both 2025.2 editions. Tests cover JShell/Spring integration, transaction rollback, snapshots, debugger capture, recordings, UI models and MCP permissions, including real Spring MVC/JDBC/Hibernate and Executor/@Async/CompletableFuture fixtures with the packaged agent. The verifier reports 20 deprecated and 2 scheduled-removal API usages on each edition. These results do not imply verification against IDEA versions after 2025.2 or every application's runtime configuration.

GitHub Actions runs regression/build checks on pushes to `main` and pull requests, plus IDE verification and Maven packaging. Library publishing is configured for `v*` tags. The installable ZIP is a build output, not a tracked repository file.

## Upgrade and project layout

Install the new ZIP, restart IDEA and restart the target development JVM to use the matching bundled agent. Clear an obsolete custom agent path if necessary. Run configuration IDs are retained; upgrading does not require deleting caches or configurations. Older snapshot files are left untouched; import copies of useful JSON explicitly. Protocol-incompatible agents are rejected rather than silently reused.

| Module | Role |
| --- | --- |
| `src/` | IntelliJ plugin, workbook, source integration, UI and MCP server |
| `repl-protocol/` | Shared framing, endpoints, value/call formats, audit and archive utilities |
| `dev-runtime/` | Agent, JShell, instrumentation, transactions, snapshots and CASE engine |
| `sb-repl-agent/` | Maven packaging of the same runtime/protocol sources |
| `sb-repl-bridge/` | Optional Spring lifecycle bridge and application capture/tap facade |
| `spring-boot-integration/` | Archived experiments; excluded from the supported build |

Versioned reports describe the scope and checks of each increment. The [handbook](docs/repl-help-hu.md) and this README describe current use. General JVM undo, automatic schema migration, outbound stubbing, cross-thread reactive tracing, Kubernetes attach and a standalone bundle CLI remain outside the implemented feature set.
