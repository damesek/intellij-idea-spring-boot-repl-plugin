# sb-repl Bridge & Agent Publishing Checklist

Artifacts to publish with every sb-repl release:

1. `hu.baader:sb-repl-bridge:<version>`
   - Source: `sb-repl/sb-repl-bridge`
   - Maven deploy command (`settings.xml` contains Sonatype token + GPG config):
     ```bash
     mvn -f sb-repl-bridge/pom.xml clean deploy
     ```
2. `hu.baader:sb-repl-agent:<version>`
   - Source: `sb-repl/sb-repl-agent` (reuses `dev-runtime/src/main/java`)
   - Command:
     ```bash
     mvn -f sb-repl-agent/pom.xml clean deploy
     ```

## IntelliJ Plugin Integration TODO

* Update `AttachDevRuntimeAction` so it first tries to locate `~/.m2/repository/hu/baader/sb-repl-agent/<ver>/sb-repl-agent-<ver>.jar`. Fall back to manual picker only if the artifact is missing.
* Expose the default coordinate (`hu.baader:sb-repl-agent`) + version input in plugin settings so users can override the resolved version if needed.
* Document in `SPRING_REPL_HELP.md` that the Spring project only needs `implementation("hu.baader:sb-repl-bridge:<ver>")` and the IDE automatically finds the agent.

Once this is wired, onboarding shrinks to “add dependency + click Attach”.
