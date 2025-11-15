# sb-repl-bridge

Spring Boot auto-configuration that exposes the currently running `ApplicationContext` to the sb-repl development agent. Add the dependency, run the plugin's **Attach & Inject** action, and the REPL immediately sees `applicationContext` without any manual helper classes in your project.

## 📦 Dependency

```xml
<dependency>
  <groupId>hu.baader</groupId>
  <artifactId>sb-repl-bridge</artifactId>
  <version>0.7.1</version>
</dependency>
```

Gradle (Kotlin):

```kotlin
dependencies {
    implementation("hu.baader:sb-repl-bridge:0.7.1")
}
```

## ⚙️ What it does

* Registers `DevRuntimeBridgeConfig`, an `ApplicationContextAware` bean that pushes the context into `com.baader.devrt.SpringContextHolder` as soon as Spring finishes bootstrapping.
* Ships the matching `SpringContextHolder` copy so both the agent and the host JVM see the same fully qualified type.
* Auto-configures itself only when the sb-repl agent is present. Disable via `sb.repl.bridge.enabled=false` if needed.

## 🚀 Usage Steps

1. Add the dependency to your Spring Boot application.
2. Start the app normally (no extra configuration required).
3. In IntelliJ, open **SB Tools** and hit the single **Connect** button (attaches, loads the agent, and binds Spring context in one go).
4. The REPL now exposes `applicationContext` immediately; bean lookups and helper buttons (Insert Bean Getter) work without extra reflection hacks.

## 🔧 Configuration Options

| Property | Default | Description |
|----------|---------|-------------|
| `sb.repl.bridge.enabled` | `true` | Flip to `false` to skip registering the bridge. |

## 📤 Publishing Notes

A complete Sonatype Central Portal setup (GPG + `central-publishing-maven-plugin`) is already present in the `pom.xml`. Reuse the same Maven settings you used for `spring-boot-clojure-repl` to sign and push the `0.7.x` artifacts in sync with each sb-repl release.

## 🛣 Next Steps

* Publish `sb-repl-bridge` together with `sb-repl` `0.7.x` releases so Spring apps consume a stable dependency instead of copying helper classes.
* Publish/update the `sb-repl-agent` artifact that exposes the dev runtime JAR via Maven Central, allowing IDE tooling to resolve it without manual file pickers.

## 💽 SnapshotHelper

A `com.baader.sbrepl.bridge.SnapshotHelper` segít abban, hogy egyszerűen ments/felolvass futás közben adatokat a dev-runtime snapshot tárába:

```java
import com.baader.sbrepl.bridge.SnapshotHelper;

SnapshotHelper.save("auditPageLimit10", page);
var saved = SnapshotHelper.load("auditPageLimit10");
```

- A helper egyszerre hívja a dev-runtime `SnapshotStore`-t és `SnapshotManager`-t, így a mentés azonnal megjelenik az IntelliJ Snapshots paneljén **és** JSON-ként is letárolódik a `~/.java-repl-snapshots` könyvtárban.
- A snapshot elnevezésed lehet tetszőleges (például `auditPageLimit10`), így több állapot is külön néven visszanézhető.
- Méretlimit ugyanaz, mint eddig: a JSON fájlok mérete határozza meg, mennyit tudsz tartósan megtartani, a memória mód pedig a JVM heapet használja.
