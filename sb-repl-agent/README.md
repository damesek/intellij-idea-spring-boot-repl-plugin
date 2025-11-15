# sb-repl-agent

Maven-distributed JVM agent that powers the sb-repl IntelliJ plugin. Attach it to a running Spring Boot JVM (or start the process with `-javaagent`) and the dev runtime will open its lightweight nREPL server, auto-bind the Spring context, and serve Java snippets.

## 📦 Dependency / Download

```xml
<dependency>
  <groupId>hu.baader</groupId>
  <artifactId>sb-repl-agent</artifactId>
  <version>0.7.2</version>
</dependency>
```

After publishing to Sonatype Central, the IDE plugin can resolve the JAR from the local Maven cache instead of asking the user to pick a file manually.

## 🔧 Usage

* **Attach to running process**: the IntelliJ plugin's single **Connect** action looks up `~/.m2/repository/hu/baader/sb-repl-agent/.../sb-repl-agent-...jar` automatically.
* **Start with agent**:
  ```bash
  java -javaagent:sb-repl-agent-0.7.2.jar=port=5557 -jar your-app.jar
  ```
  Optional args: `port`, `token`, future switches pass straight through the manifest.

## 🛠 Build Notes

The POM pulls source files directly from `../dev-runtime/src/main/java`, so you only maintain the agent code in one place. The `maven-jar-plugin` injects the same manifest (`Premain-Class`, `Agent-Class`, `Can-Redefine-Classes=true`) as the Gradle build.

Publishing uses the same Central Portal configuration (GPG + `central-publishing-maven-plugin`) as the other sb-repl artifacts.
