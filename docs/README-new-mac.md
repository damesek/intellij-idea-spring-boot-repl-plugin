# Running the Java over nREPL stack on a new macOS machine

Use these steps to bring up the same setup on another Mac (replace `USERNAME` with the actual account name).

<!-- Badges -->
[![Maven Central – sb-repl-bridge](https://img.shields.io/maven-central/v/hu.baader/sb-repl-bridge.svg?label=sb-repl-bridge&logo=apache%20maven&style=for-the-badge)](https://central.sonatype.com/artifact/hu.baader/sb-repl-bridge)
[![Maven Central – sb-repl-agent](https://img.shields.io/maven-central/v/hu.baader/sb-repl-agent.svg?label=sb-repl-agent&logo=apache%20maven&style=for-the-badge)](https://central.sonatype.com/artifact/hu.baader/sb-repl-agent)


1. **Install bridge and agent artifacts into the local Maven repo**
   ```bash
   cd /Users/USERNAME/Documents/Codes/sb-repl
   mvn -f sb-repl-bridge/pom.xml clean install -Dgpg.skip=true
   mvn -f sb-repl-agent/pom.xml clean install -Dgpg.skip=true
   ```
   This produces the JARs under `sb-repl-bridge/target` and `sb-repl-agent/target`. Copy these modules (or the entire repo) to the new Mac if needed.

   On the new machine, run the same commands so the artifacts land in `~/.m2/repository/hu/baader/...`.

2. **Wire the Spring application**
   Add the bridge dependency to the project's `pom.xml` (or `implementation("hu.baader:sb-repl-bridge:0.5.0-SNAPSHOT")` in Gradle with `mavenLocal()` enabled):
   ```xml
   <dependency>
       <groupId>hu.baader</groupId>
       <artifactId>sb-repl-bridge</artifactId>
       <version>0.5.0-SNAPSHOT</version>
   </dependency>
   ```
   Restart the Spring app so the bridge auto-registers the ApplicationContext.

3. **Install the IntelliJ plugin**
   Build the plugin ZIP on the first machine:
   ```bash
   ./gradlew buildPlugin
   ```
   Copy `build/distributions/sb-repl-*.zip` to the new Mac and install it in IntelliJ (File → Settings → Plugins → Install from Disk). The updated Attach action will automatically resolve the `sb-repl-agent` JAR from `~/.m2`.

That's it—once the Spring app is running and the plugin is installed, open the Java REPL tool window, attach the agent, and bind the Spring context as usual.
