# Spring Boot REPL — IntelliJ IDEA Plugin

[![Maven Central – sb-repl-bridge 0.7.5](https://img.shields.io/badge/sb--repl--bridge-0.7.5-blue?logo=apache%20maven&style=for-the-badge)](https://central.sonatype.com/artifact/hu.baader/sb-repl-bridge/0.7.5)
[![Maven Central – sb-repl-agent 0.7.5](https://img.shields.io/badge/sb--repl--agent-0.7.5-blue?logo=apache%20maven&style=for-the-badge)](https://central.sonatype.com/artifact/hu.baader/sb-repl-agent/0.7.5)

Spring Boot REPL is an IntelliJ IDEA plugin that lets you evaluate Java code over nREPL against a live Spring Boot JVM, with JShell-based evaluation, Spring context binding, HotSwap and HTTP helpers.

## Modules
- `src/` – IntelliJ plugin (tool window UI, nREPL client, REPL editor, transcript, HTTP panel, snapshots, history).
- `dev-runtime/` – attachable JVM agent that exposes a JShell-based REPL and Spring context auto-bind.
- `sb-repl-bridge/` – Spring-side bridge library (shared `SpringContextHolder`).
- `sb-repl-agent/` – Maven-packaged dev-runtime agent, used when you do not want to point to a local JAR.
- `spring-boot-integration/` – example Spring Boot app with embedded nREPL server and Java evaluator.
- `docs/`, `SPRING_REPL_HELP.md` – additional usage notes and examples.

## Spring Boot integration (0.7.5)

Add the bridge and agent to your Spring Boot app:

```xml
<dependency>
  <groupId>hu.baader</groupId>
  <artifactId>sb-repl-bridge</artifactId>
  <version>0.7.5</version>
</dependency>
<dependency>
  <groupId>hu.baader</groupId>
  <artifactId>sb-repl-agent</artifactId>
  <version>0.7.5</version>
</dependency>
```

Make sure the Spring Boot application scans the bridge package so the auto-configuration is picked up, for example:

```java
@SpringBootApplication
@ComponentScan(basePackages = {
    "com.yourcompany.yourapp",
    "com.baader.sbrepl.bridge"
})
public class Application {
}
```

Start the application in a dev profile and make sure the agent is either:

- attached via `-javaagent` (see dev-runtime below), or  
- resolved from your local Maven repository as `sb-repl-agent:0.7.5`.

## Dev-runtime agent

Build the dev-runtime agent JAR:

```bash
./gradlew :dev-runtime:jar
```

This creates `dev-runtime/build/libs/dev-runtime-agent-0.7.5.jar`.

Run your Spring Boot app with the agent:

```bash
java \
  -javaagent:/path/to/dev-runtime-agent-0.7.5.jar=port=5557 \
  -jar your-app.jar
```

Alternatively, install `sb-repl-agent` into your local Maven repo:

```bash
mvn -f sb-repl-bridge/pom.xml clean install -Dgpg.skip=true
mvn -f sb-repl-agent/pom.xml clean install -Dgpg.skip=true
```

The plugin can then locate the agent under `~/.m2/repository/hu/baader/sb-repl-agent/0.7.3/...`.

## Plugin build and installation

Build the IntelliJ plugin ZIP:

```bash
./gradlew buildPlugin
```

Install in IntelliJ IDEA: `Settings → Plugins → Install from Disk…`, then select `build/distributions/sb-repl-0.7.5.zip` and restart the IDE.

## Plugin configuration

Open `Settings → Tools → Spring Boot REPL` and configure:

- **Host**: usually `127.0.0.1`
- **Port**: nREPL port, default `5557`
- **Agent JAR**:
  - leave empty if `sb-repl-agent:0.7.5` is available in `~/.m2`, or
  - point to the dev-runtime JAR, e.g. `dev-runtime/build/libs/dev-runtime-agent-0.7.5.jar`
- **Agent Maven version**: defaults to `0.7.5`

Then:

1. Start your Spring Boot application with the agent.
2. Open the **Spring Boot REPL** tool window.
3. Click **Connect**, then **Bind Spring Context** if needed.

When JShell mode is active you will see:

- `Mode: JShell session (stateful imports & defs)`
- Spring context binding messages in the log.

## REPL workflow – quick examples

Bind the Spring context and call a service:

```java
import com.baader.devrt.SpringContextHolder;
import org.springframework.context.ApplicationContext;

ApplicationContext ctx = (ApplicationContext) SpringContextHolder.get();
var ai = (com.ai.springboot.service.AIService)
        ctx.getBean(com.ai.springboot.service.AIService.class);
ai.generateResponse("Hello from Spring Boot REPL");
```

Create a logger from the REPL:

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
Logger logger = LoggerFactory.getLogger("REPL");
logger.info("AI service is available");
```

HotSwap a modified class:

1. Open the Java file in the editor and select the full class (or leave nothing selected to use the whole file).
2. In the **Spring Boot REPL** tool window, click **Hot Swap**.
3. The next REPL call uses the reloaded implementation.

### REPL UI and editor actions

- **REPL tab** – bottom Java editor (stateful JShell), top transcript with collapsible code/result blocks.
- **Last Result** popup – pretty-printed value of the last evaluation (JSON-aware, syntax-highlighted).
- **Log** popup – full console output (out/err, nREPL protocol messages).
- **Editor actions** (from SB Tools / context menu):
  - Run Selection in Spring Boot REPL
  - Evaluate at Caret
  - Reload Class (HotSwap)
  - Sync Imports and apply import aliases
  - Insert Bean Getter (searchable Spring bean picker).
