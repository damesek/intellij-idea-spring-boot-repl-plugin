# Running the Java over nREPL stack on a new macOS machine

1. Install bridge and agent artifacts into the local Maven repo:

   ```bash
   cd /Users/USERNAME/Documents/Codes/sb-repl
   mvn -f sb-repl-bridge/pom.xml clean install -Dgpg.skip=true
   mvn -f sb-repl-agent/pom.xml clean install -Dgpg.skip=true
   ```

   A JAR-ok a `sb-repl-bridge/target` és `sb-repl-agent/target` alatt jelennek meg, és bekerülnek a `~/.m2/repository/hu/baader/...` könyvtárba.

2. Wire the Spring application (0.7.1):

   ```xml
   <dependency>
       <groupId>hu.baader</groupId>
       <artifactId>sb-repl-bridge</artifactId>
       <version>0.7.1</version>
   </dependency>
   ```

3. Build and install the IntelliJ plugin:

   ```bash
   ./gradlew buildPlugin
   ```

   Telepítés: IntelliJ → Settings → Plugins → Install from Disk, válaszd a `build/distributions/sb-repl-*.zip` fájlt, majd restart.

4. Indítsd el a Spring Boot appot, nyisd meg az SB Tools tool windowot, és nyomd meg a Connect gombot.
