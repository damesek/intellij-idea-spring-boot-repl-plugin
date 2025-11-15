# Java over nREPL — IntelliJ IDEA Plugin

[![Maven Central – sb-repl-bridge 0.7.1](https://img.shields.io/badge/sb--repl--bridge-0.7.1-blue?logo=apache%20maven&style=for-the-badge)](https://central.sonatype.com/artifact/hu.baader/sb-repl-bridge/0.7.1)
[![Maven Central – sb-repl-agent 0.7.1](https://img.shields.io/badge/sb--repl--agent-0.7.1-blue?logo=apache%20maven&style=for-the-badge)](https://central.sonatype.com/artifact/hu.baader/sb-repl-agent/0.7.1)

All-in-one toolkit for evaluating Java code over nREPL inside IntelliJ IDEA while talking to a live Spring Boot application.

## Modules
- `src/` – IntelliJ plugin (UI, nREPL kliens, REPL panel, HTTP panel, history).
- `dev-runtime/` – attacholható JVM agent JShell-es REPL-pel és Spring auto-binddal.
- `sb-repl-bridge/` – Spring Boot auto-config, ami betolja az `ApplicationContext`-et az agentbe.
- `sb-repl-agent/` – Mavenes agent csomag, ugyanarra a dev-runtime kódra építve.

## Spring Boot integráció (0.7.1)
`pom.xml`:

```xml
<dependency>
  <groupId>hu.baader</groupId>
  <artifactId>sb-repl-bridge</artifactId>
  <version>0.7.1</version>
</dependency>
<dependency>
  <groupId>hu.baader</groupId>
  <artifactId>sb-repl-agent</artifactId>
  <version>0.7.1</version>
</dependency>
```

`@SpringBootApplication`:

```java
@SpringBootApplication
@ComponentScan(basePackages = {
    "com.yourcompany.yourapp",
    "com.baader.sbrepl.bridge"
})
public class Application { }
```

Indítsd el az alkalmazást `dev` profillal, a bridge automatikusan regisztrálja az `ApplicationContext`-et az agent felé.

## Dev-runtime agent használata

### 1. JAR buildelése

```bash
./gradlew :dev-runtime:jar
```

Ez létrehozza a `dev-runtime/build/libs/dev-runtime-agent-0.7.0.jar` fájlt.

### 2/a. Indítás javaagent-tel (közvetlen)

```bash
java \
  -javaagent:/Users/USERNAME/Documents/Codes/sb-repl/dev-runtime/build/libs/dev-runtime-agent-0.7.0.jar=port=5557 \
  -jar your-app.jar
```

### 2/b. sb-repl-agent 0.7.1 Mavenből

Miután lefuttattad:

```bash
mvn -f sb-repl-bridge/pom.xml clean install -Dgpg.skip=true
mvn -f sb-repl-agent/pom.xml  clean install -Dgpg.skip=true
```

az IDE plugin a `~/.m2/repository/hu/baader/sb-repl-agent/0.7.1/...` alól is fel tudja venni az agentet.

## Plugin build és telepítés (0.7.0)

```bash
./gradlew buildPlugin
```

Telepítés IntelliJ-ben: `Settings → Plugins → Install from Disk`, válaszd a `build/distributions/sb-repl-0.7.0.zip` fájlt, majd restart.

## Plugin konfigurálása

`Settings → Tools → Java over nREPL`:

- `Host`: `127.0.0.1`
- `Port`: `5557`
- `Agent JAR path`:
  - üresen hagyható, ha `sb-repl-agent 0.7.1` Mavenből elérhető (`~/.m2`),
  - vagy add meg a dev-runtime JAR-t:
    - `/Users/USERNAME/Documents/Codes/sb-repl/dev-runtime/build/libs/dev-runtime-agent-0.7.0.jar`

Mentés után:

1. Indítsd el a Spring Boot appot.
2. Nyisd meg az **SB Tools** tool windowot.
3. Nyomd meg a **Connect** gombot.

Ha a dev-runtime agent fut, a konzolban ezt látod:

- `Mode: JShell session (stateful imports & defs)`
- `Automatically binding Spring context...`
- `Spring context bound and session updated.`

## REPL használat – tipikus lépések

1. Importok és Spring context:

```java
import com.baader.devrt.SpringContextHolder;
import org.springframework.context.ApplicationContext;
import com.ai.springboot.controller.WebController;

ApplicationContext ctx = (ApplicationContext) SpringContextHolder.get();
WebController web = ctx.getBean(WebController.class);
```

2. Bean hívás és LLM válasz:

```java
((com.ai.springboot.service.AIService)
        ctx.getBean(com.ai.springboot.service.AIService.class))
    .generateResponse("Szia, működik az LLM REPL-ből?");
```

3. Saját logger JShell-ben:

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
Logger logger = LoggerFactory.getLogger("REPL");
logger.info("✅ AI service is available");
```

4. HotSwap (osztály újratöltése):

- Nyisd meg a módosított osztályt (pl. `AIService`).
- Jelöld ki a teljes osztályt vagy az egész fájlt.
- SB Tools ablakban nyomd meg a **Hot Swap** gombot, vagy használd a `Reload Class in SB Tools` menüpontot (Cmd+Shift+R).

Sikeres hot swap esetén a konzolban „Reloaded classes: ...” jellegű üzenet jelenik meg, és a következő REPL hívás már az új kódot futtatja.
