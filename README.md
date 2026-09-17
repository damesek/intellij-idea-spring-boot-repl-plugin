# Spring Boot Debug REPL and MCP

**Turn a live Spring Boot bug into a repeatable test — without leaving IntelliJ IDEA.**

An IntelliJ workflow that turns live Spring Boot behavior into reusable snapshots, repeatable checks, and JUnit tests — accessible to developers and AI agents through MCP.

Capture the input that caused a problem, inspect what happened inside the running application, and check a fix against the same example. Developers and AI agents can work from shared runtime evidence.

**Capture → reproduce → investigate → fix → verify → keep the test.**

## What it helps you do

| Your goal | How the plugin helps |
| --- | --- |
| Understand a hard-to-reproduce bug | Save inputs and results as snapshots; explore objects and recorded calls next to the source. |
| Find where a request becomes slow | Follow the call graph through SQL and Hibernate; investigate suspected N+1 queries. |
| Check a change with confidence | Run saved reproduction cases, compare results, and export JUnit regression tests. |
| Give an AI agent useful context | Let Claude, Codex or another MCP client inspect runtime data and run permitted experiments. |

The Java REPL is an interactive workspace inside your running application. It keeps variables between evaluations and can call real Spring beans. Workbooks, snapshots and saved cases make an investigation reusable and shareable.

## Get started

1. Build the plugin with `./gradlew buildPlugin` using JDK 17. The build includes regression checks; the ZIP is in `build/distributions/`.
2. In IntelliJ IDEA, choose **Settings → Plugins → Install Plugin from Disk**, select the ZIP, and restart the IDE.
3. Open an existing Spring Boot or Java Application run configuration and enable **Spring Boot Debug REPL and MCP**. Keep your usual profiles and arguments, then start the application.
4. Open the **Spring Boot Debug REPL and MCP** tool window. Inspect a bean, evaluate a small Java expression, or capture an input for a saved case.

For AI access, open the **MCP** tab, choose permissions, and use **Start MCP → Copy client config**. The [agent guide](docs/claude-repl-instructions.md) explains the available tools and workflow.

## Designed for development

Java evaluation runs inside the application and can change its state. Optional database rollback covers participating synchronous transactions; it cannot undo external calls, files or all application changes. MCP access is local and authenticated, with explicit permissions and audit logging.

HotSwap supports compatible method-body changes. Structural changes can require a restart. Snapshots preserve selected data, not an entire running JVM.

**Version 0.24.0 · IntelliJ IDEA 2024.1–2025.2.x · Java 17 and 21 tested.** See the [engineering guide](docs/engineering.md) for the exact verification matrix and known limits.

## Read more

- **User handbooks:** [English PDF](output/pdf/spring-boot-repl-guide-en.pdf) · [Magyar PDF](output/pdf/spring-boot-repl-guide-hu.pdf). Both are also available offline in **Help (PDF)** inside the plugin.
- **AI agents:** [Tools and usage](docs/claude-repl-instructions.md) · [Claude setup in Hungarian](docs/claude-repl-guide-hu.md).
- **Contributors:** [Architecture, build and maintenance](docs/engineering.md) · [Wire protocol](PROTOCOL.md).
- **Documentation:** [Index and release history](docs/README.md).
