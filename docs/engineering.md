# Engineering and maintenance

This is a development tool that executes code inside another application. Enterprise readiness is assessed through the concrete checks below, not a certification or a guarantee that arbitrary Java is safe.

## Acceptance criteria

1. **Repository ownership.** Every maintained source belongs to a built module or a documented tool. Remove abandoned experiments, generated backup files and superseded implementations. Keep historical release evidence separate from current instructions.
2. **Compatibility.** Preserve plugin and action IDs, wire operations, snapshot formats and supported workflows. Classloader isolation and the declared IDE/JDK support range remain explicit.
3. **Architecture.** Keep transport, session lifecycle, execution, presentation and persistence responsibilities distinct. Share validation and serialization at the boundary that owns them; avoid introducing generic frameworks for one-off operations.
4. **Lifecycle and failure handling.** Bound queues, sessions and results. Release owned resources on close. Preserve cancellation and uncertain-outcome semantics; never silently replay application mutations.
5. **Security.** Keep local binding, authentication, permissions, secret handling, redaction and auditing intact. Java evaluation is privileged application execution, not a sandbox or a general undo mechanism.
6. **Maintainability.** Use descriptive names, focused functions and readable formatting. Remove dead branches only after checking source, resources, reflection, packaging and tests.
7. **Verification.** Exercise behavior at the affected boundaries, run the complete regression suite on JDK 17 and 21, build the installable ZIP, validate agent/bridge packaging and check supported IDE compatibility. Report any checks that could not run.
8. **Documentation.** The README explains the problem, product value, workflow and entry points in plain language. Handbooks own feature details; contributor documentation owns build, architecture and release procedures.

## Review method

Inspect module entry points and cross-references before removing code. Prefer small behavior-preserving extractions over rewrites. A successful build alone does not establish application compatibility: use integration tests for session lifecycle, runtime instrumentation, persistence and MCP boundaries.

Generated plugin ZIPs, diagnostic logs and render previews belong in ignored build directories. The two versioned PDF handbooks are intentional deliverables: repository and bundled copies must remain identical and match their source hashes.

The product display name is **Spring Boot Debug REPL and MCP**. The plugin ID (`hu.baader.java-over-nrepl`), tool-window/notification IDs (`Spring Boot REPL`), credential keys and MCP server name (`spring-boot-repl`) are stable compatibility contracts. Change visible labels without renaming those identifiers or the artifact coordinates.

## Module boundaries

| Module | Owns | Must not bundle |
| --- | --- | --- |
| `src/` | IntelliJ integration, editors, UI, nREPL client and local MCP server | IntelliJ SDK, application Spring/Hibernate libraries |
| `repl-protocol/` | Wire formats, endpoint metadata, bounded values, audit and archive utilities | IntelliJ, Spring or instrumentation dependencies |
| `dev-runtime/` | Agent, JShell, snapshots, CASE execution, recordings and application lifecycle | Application framework libraries; Byte Buddy stays in the private agent loader |
| `sb-repl-agent/` | Maven packaging of the same runtime and protocol sources | A second runtime implementation |
| `sb-repl-bridge/` | Optional Spring lifecycle integration and capture/tap API | Runtime instrumentation implementation |

`ReplHandler` owns dispatch and auditing. `ReplSession` owns session state and resource release; `CaseOperations` owns the CASE workflow under that session's lock and snapshot scope. Control operations must remain available while evaluation is running. Queues remain bounded, and closing a session must not wait on user code on the transport thread.

The MCP catalog, permission model, argument validation and response rendering live in separate files. `McpResponses` formats and redacts existing results; it must never rerun Java to obtain a preview. `McpRouter` owns client lifecycle, dispatch, quotas and audit boundaries.

`HttpRequestsPanel` owns editing. Request models, identifier allocation and `HttpRequestService` persistence have separate owners. Preserve the service's package, state name and XML storage name when refactoring: these are installed-user data contracts. Request contents remain in PasswordSafe; workspace exports containing HTTP data are explicit user actions.

## Build and check

Use the checked-in Gradle wrapper. Install JDK 17 for compilation and JDK 21 for the second test run. `buildPlugin` depends on all regression checks; it also validates PDF freshness and Maven module versions. Python is needed only for repository checks and handbook generation, not an ordinary plugin build.

```sh
python3 scripts/check-repository.py
./gradlew check buildPlugin -PtestJdk=17
./gradlew check -PtestJdk=21
./gradlew verifyPlugin -PtestJdk=21
./gradlew verifyPlugin -PtestJdk=21 -PverifyPreviousIdes=true
mvn -B -f sb-repl-agent/pom.xml package -Dgpg.skip=true
python3 scripts/check-agent-package.py sb-repl-agent/target/sb-repl-agent-0.24.0.jar
mvn -B -f sb-repl-bridge/pom.xml package -Dgpg.skip=true
```

`build.sh` runs checks, packaging and the default verifier; `run.sh` starts a development IDE. Both work from another directory and forward arguments to the wrapper. `deploy-to-local-maven.sh` installs the two Maven libraries locally and never selects a remote publishing profile.

Supported IDEA build range: `241.0–252.*`. Compilation uses IC 2024.1.4; the two verifier runs cover IC/IU 2025.2 and IC 2024.1.4/IU 2025.1.7. Java source targets 17. A passing verifier checks binary compatibility, not every UI interaction or application configuration. Hibernate instrumentation is tested against 6.6.29.Final; arbitrary Java execution and all reactive contexts are not sandboxed or universally supported.

## Documentation and release ownership

Keep the README focused on user value and getting started. Feature details belong in the [handbooks](README.md); old increment reports belong in [history](history/README.md). Only runtime resources from `src/main/resources/` and the generated agent go into the plugin. Repository documentation and screenshots are not classpath resources.

To update the offline manuals, edit `docs/repl-help-en.md` and `docs/repl-help-hu.md`, then run `python3 scripts/build-help-pdf.py` with ReportLab and supported TTF fonts. Review the changed PDF pages before committing all four PDF copies and both source hashes. Normal builds reject stale or mismatched copies.

The root Gradle version is the source of truth for Gradle modules. Update both Maven POM versions together; `verifyModuleVersions` prevents drift. Release tags must match `v<version>`; the repository checker validates this in tag-triggered CI. Rebuild the handbooks after a version change.

Remote publication uses explicit Maven profiles: `central` or `github`. Ordinary `package` and `install` select neither. Both tag-triggered publishing workflows depend on the full verification workflow. Central uses signing credentials imported by `setup-java`; GitHub Packages has its own distribution target and token. Never put secret values directly in shell commands. See [setup-java's Maven publishing contract](https://github.com/actions/setup-java/blob/v4/docs/advanced-usage.md#publishing-using-apache-maven) and [Central publishing](https://central.sonatype.org/publish/publish-portal-maven/).

## Cleanup review, September 2026

- Reviewed tracked source ownership, descriptors/reflection entry points, dependencies, build scripts, packaging, CI and documentation links across all five modules.
- Removed the excluded `spring-boot-integration` experiment, duplicate unused REPL operation constants, unused HTTP quick-actions UI, POM backups, obsolete screenshots and fallback packaging/classloader probes superseded by regression tests and Plugin Verifier.
- Archived 21 historical reports; retained current manuals and their intentional bundled PDF copies.
- Separated runtime session/CASE responsibilities, MCP contracts/results and HTTP editing/persistence. Reset and expiry now release cached evaluation data through a shared lifecycle path. Rejected CASE edits retain the previous result.
- Removed broad `docs/` resource inclusion, aligned Gradle module versions, made archive order/timestamps reproducible and corrected publishing target/credential configuration.

These checks improve maintainability and release discipline; they are not an enterprise certification. The settings file chooser no longer calls the API scheduled for removal. The toolbar text override remains for the oldest supported IDE; replacing it requires an IDE-baseline decision and UI verification. Other IntelliJ deprecated APIs, application-specific integrations and the privileged nature of runtime Java remain explicit constraints. Remote publication requires configured repository credentials and is not exercised by local validation.

### Validation of this cleanup

| Check | Result |
| --- | --- |
| Full regression suite, JDK 17 and JDK 21 | 338 tests per JDK: 138 plugin, 14 full-JDK integrations, 186 runtime; zero failures, errors or skips |
| Clean Gradle build and installable ZIP | Passed; no duplicate classes, SDK classes, obsolete HTTP panel or repository documentation in the plugin |
| Plugin Verifier | Compatible on IC 2024.1.4, IU 2025.1.7, IC 2025.2 and IU 2025.2 |
| Remaining 2025.2 verifier notices | 20 deprecated API usages and 1 scheduled-removal usage per edition |
| Maven agent and bridge | Both packages built; agent contents/isolation checked; default/Central/GitHub effective models validated without publishing |
| Repository and release checks | 120 local documentation links valid; mismatched release tag rejected; shell scripts and workflow YAML parsed |
| English and Hungarian manuals | 69 pages and 51 outline entries each; changed page 35 rendered and reviewed; repository/bundled copies and source hashes checked |

The 0.23.0 cleanup ZIP, including the new product name, was `build/distributions/sb-repl-0.23.0.zip` (6,579,070 bytes; previously 9,326,147 bytes). Its SHA-256 is `cfa13b31aec89e957a3064d7344299a7b4e2e894247cff2042c6865b98a73756`. The renamed English and Hungarian manuals retain 69 pages each; covers and pages 4, 23 and 29 were rendered and reviewed. Local check logs and machine-readable summaries are under `build/cleanup-*` and `build/rename-*`; they are generated evidence, not versioned source files.
