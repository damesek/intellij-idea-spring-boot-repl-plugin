# Spring Boot Debug REPL and MCP agent

The agent runs the Java session, snapshots and recording engine inside a development JVM. The IntelliJ plugin already bundles the matching agent; use this Maven module when you need a standalone JAR.

## Build and start

```sh
mvn -B -f sb-repl-agent/pom.xml package -Dgpg.skip=true
java -javaagent:sb-repl-agent/target/sb-repl-agent-0.24.0.jar=port=0 -jar your-app.jar
```

`port=0` chooses an available loopback port. The agent generates an authentication token and writes the connection information to `~/.sb-repl/endpoints/<pid>.properties`. Optional `endpoint=<path>` changes that location; `endpoint64` accepts a URL-safe Base64-encoded UTF-8 path. A caller-supplied token is not supported. Keep endpoint files private.

For ordinary IDE use, enable **Spring Boot Debug REPL and MCP** in the run configuration. To attach to an existing process, use **Attach & Inject Dev Runtime** and select the JVM. The [optional bridge](../sb-repl-bridge/README.md) can provide the ready Spring context after late attachment.

## Packaging and publication

The POM compiles the same `dev-runtime` and `repl-protocol` sources as the Gradle build. Byte Buddy is nested in `agent-libs/` and loaded privately; application Spring/Hibernate libraries are not bundled.

Validate an artifact with:

```sh
python3 scripts/check-agent-package.py sb-repl-agent/target/sb-repl-agent-0.24.0.jar
```

The Maven coordinates are `hu.baader:sb-repl-agent:0.24.0`. A local build does not imply that this version has been published. Publication requires the explicit `central` or `github` Maven profile; ordinary package/install selects neither. See the [release and verification guide](../docs/engineering.md#documentation-and-release-ownership).
