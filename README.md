# Java over nREPL — IntelliJ IDEA Plugin

<!-- Badges -->
[![Maven Central – sb-repl-bridge](https://img.shields.io/maven-central/v/hu.baader/sb-repl-bridge.svg?label=sb-repl-bridge&logo=apache%20maven&style=for-the-badge)](https://central.sonatype.com/artifact/hu.baader/sb-repl-bridge)
[![Maven Central – sb-repl-agent](https://img.shields.io/maven-central/v/hu.baader/sb-repl-agent.svg?label=sb-repl-agent&logo=apache%20maven&style=for-the-badge)](https://central.sonatype.com/artifact/hu.baader/sb-repl-agent)


![IntelliJ Java REPL](docs/images/intellij-idea-plugin-java-repl.png)

All-in-one toolkit for evaluating Java code over nREPL inside IntelliJ IDEA while talking to a live Spring Boot application. The repository contains the IDE plugin, the attachable dev-runtime agent, and helper modules that make the Spring context instantly available in the REPL.

## What's inside?
- **IntelliJ plugin (`src/`)** – Kotlin-based UI + nREPL client, tool window, editor actions, configurable settings, history/snapshot helpers, import aliases.
- **Dev runtime (`dev-runtime/`)** – attachable JVM agent exposing a lightweight nREPL server that can hot-swap Java code and auto-bind the Spring `ApplicationContext`.
- **Spring Boot integration (`spring-boot-integration/`)** – sample server-side components if you want to embed the runtime directly in your app.
- **Distributable modules** – `sb-repl-bridge` (Spring auto-config + SnapshotHelper) and `sb-repl-agent` (packaged attachable agent) ready for Maven Central publishing.

## Quick start
1. **Add the bridge to your Spring app**
   ```xml
   <dependency>
     <groupId>hu.baader</groupId>
     <artifactId>sb-repl-bridge</artifactId>
     <version>0.5.0-SNAPSHOT</version>
   </dependency>
   ```
   Start the application (dev profile), so the bridge can push the running `ApplicationContext` into the agent once attached.

2. **Build and install the IntelliJ plugin**
   ```bash
   ./gradlew buildPlugin
   # install build/distributions/sb-repl-*.zip via File → Settings → Plugins → Install from Disk
   ```
   The new Attach action will automatically pull the agent JAR from `~/.m2/repository/hu/baader/sb-repl-agent/...` if you installed it locally.

3. **Use the REPL**
   - Run your Spring Boot app (with `nrepl.enabled=true`, default port `5557`).
   - IntelliJ → View → Tool Windows → Java REPL.
   - Click **Attach & Inject**, then **Bind Spring Context** (or the combined Quick Action).
   - Select code and hit **Ctrl+Enter** to evaluate it inside the running JVM.

## Highlighted plugin features
- Tool window with console + editor, syntax highlighting, Ctrl+Enter execution.
- Built-in hot swap button (compiles the selected class and redefines it in the target JVM).
- Command history with toolbar navigation + popup, plus persistent history across IDE restarts.
- Snapshots tab (save/load/pin/delete, JSON import) + Loaded Variables panel + new Import Aliases panel that expands custom aliases before execution.
- Automatic Spring context injection (`applicationContext` variable) once the agent is bound.
- Attach action that remembers the local agent path or resolves it from Maven local.

## Dev runtime capabilities
- Small nREPL server that interprets both Java snippets and special ops (bind-spring, snapshot-*, class-reload, etc.).
- Background auto-binder tries LiveBeansView, static fields, and the custom bridge to locate the `ApplicationContext`.
- SnapshotStore + SnapshotManager keep values in memory and JSON so the IDE panel and Spring helper both see the same data.
- Hot-swap support through `Instrumentation.redefineClasses` with Can-Redefine manifest entries.

## Sample REPL snippet
```java
var repo = applicationContext.getBean(
    com.intuitech.cvprocessor.security.api.AuditLogRepository.class
);
var page = repo.findAll(
    org.springframework.data.domain.PageRequest.of(0, 5,
        org.springframework.data.domain.Sort.by("timestamp").descending())
);
SnapshotHelper.save("auditPageLimit5", page);
return new com.fasterxml.jackson.databind.ObjectMapper()
    .writerWithDefaultPrettyPrinter()
    .writeValueAsString(page);
```

## Project layout (abridged)
```
sb-repl/
├── src/main/kotlin/hu/baader/repl      # IntelliJ plugin sources
├── dev-runtime                          # JVM agent classes
├── sb-repl-bridge                       # Spring Boot auto-config + SnapshotHelper
├── sb-repl-agent                        # Mavenized agent wrapper (reuses dev-runtime)
└── spring-boot-integration              # Optional embedded server reference
```

## Security checklist
- nREPL executes arbitrary Java code – never expose it on a public interface.
- Keep it dev-only: bind to `127.0.0.1`, guard with Spring profiles, disable entirely in prod.
- JetBrains Attach API requires appropriate JVM flags on newer JDKs (`-XX:+EnableDynamicAgentLoading` on 21+).

## Troubleshooting
- **"Not connected"** – make sure the Spring app is running and the plugin host/port matches (`5557` by default).
- **"Java compiler not available"** – use a full JDK; check `javac -version`.
- **Snapshot missing in the UI** – use the Snapshots tab’s refresh; the new SnapshotHelper saves to both the agent store and JSON so the panel sees it instantly.
- **GPG / deploy** – run `mvn -f sb-repl-bridge/pom.xml clean deploy` and the same for `sb-repl-agent` once Sonatype tokens + GPG agent are configured.

## Contributing
Issues and pull requests are welcome. If you add new dev-runtime ops or UI panels, please update the README + SPRING_REPL_HELP.md so users can discover them quickly.
