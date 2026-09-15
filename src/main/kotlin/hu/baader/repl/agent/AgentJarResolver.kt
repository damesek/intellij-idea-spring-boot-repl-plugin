package hu.baader.repl.agent

import hu.baader.repl.settings.PluginSettingsState
import java.io.File
import java.nio.file.Paths

/**
 * Resolves the dev-runtime agent JAR for run configurations and auto-attach flows.
 * Priority:
 * 1) explicit path from settings (if it exists),
 * 2) bundled agent inside the plugin,
 * 3) locally cached Maven artifact in ~/.m2.
 */
object AgentJarResolver {
    fun resolve(): File? = resolve(PluginSettingsState.getInstance().state)

    fun resolve(settings: PluginSettingsState.State): File? {
        if (settings.agentJarPath.isNotBlank()) return validate(File(settings.agentJarPath.trim()))

        BundledAgentProvider.getBundledAgentJar()?.let { path ->
            val file = File(path)
            if (file.exists()) return validate(file)
        }

        val version = settings.agentMavenVersion.trim().ifBlank { PluginSettingsState.DEFAULT_AGENT_VERSION }
        val home = System.getProperty("user.home") ?: return null
        val m2 = Paths.get(
            home, ".m2", "repository", "hu", "baader", "sb-repl-agent", version,
            "sb-repl-agent-$version.jar"
        ).toFile()
        if (m2.exists()) return validate(m2)

        return null
    }
    private fun validate(file: File): File {
        require(file.isFile) { "Agent JAR does not exist" }
        java.util.jar.JarFile(file).use { jar ->
            require(jar.manifest?.mainAttributes?.getValue("Premain-Class") == "com.baader.devrt.Agent") { "Invalid REPL agent manifest" }
            require(jar.manifest?.mainAttributes?.getValue("SB-Repl-Protocol") == "1") { "Agent is incompatible; use the agent bundled with this plugin" }
            for (entry in listOf("com/baader/devrt/Agent.class", "com/baader/devrt/JShellSession.class", "com/baader/devrt/AgentInstrumentation.class", "hu/baader/repl/protocol/EndpointFile.class"))
                require(jar.getJarEntry(entry) != null) { "Incomplete REPL agent" }
            require(jar.entries().asSequence().any { it.name.startsWith("agent-libs/byte-buddy-") && it.name.endsWith(".jar") }) { "Agent instrumentation libraries are missing" }
        }
        return file
    }

}
