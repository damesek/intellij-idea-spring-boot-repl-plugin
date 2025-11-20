package hu.baader.repl.agent

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.application.ApplicationManager
import hu.baader.repl.settings.PluginSettingsState
import java.io.File
import java.nio.file.Paths
import java.util.Properties

/**
 * Best-effort, silent auto-attach: finds a likely Spring Boot JVM, injects the
 * bundled dev-runtime agent, and reports back whether it succeeded. Designed
 * to hide all setup from the user ("Zero-Config").
 */
class AgentAutoAttacher(private val project: Project) {

    private data class VmCandidate(
        val descriptor: com.sun.tools.attach.VirtualMachineDescriptor,
        val score: Int
    )

    data class Result(
        val attached: Boolean,
        val pid: String? = null,
        val port: Int? = null,
        val message: String? = null
    )

    fun tryAutoAttach(): Result {
        val settings = PluginSettingsState.getInstance().state
        val agentJar = resolveAgentJar(settings)
            ?: return Result(false, message = "Agent JAR not found")

        // Choose a safe, free port to avoid collisions
        val (chosenPort, portFallback) = pickPort(settings.agentPort)
        if (chosenPort != settings.agentPort) {
            settings.agentPort = chosenPort
        }
        settings.port = chosenPort
        settings.host = "127.0.0.1"

        val candidates = listCandidateVms()
        if (candidates.isEmpty()) {
            return Result(false, message = "No candidate Spring Boot JVM found")
        }

        val target = candidates.first().descriptor
        return try {
            val vm = com.sun.tools.attach.VirtualMachine.attach(target)
            try {
                vm.loadAgent(agentJar, "port=$chosenPort")
            } finally {
                try { vm.detach() } catch (_: Throwable) {}
            }
            val suffix = if (portFallback) " (fallback port)" else ""
            notify("Agent auto-attached to PID ${target.id()} (port $chosenPort)$suffix", NotificationType.INFORMATION)
            Result(true, pid = target.id(), port = chosenPort)
        } catch (t: Throwable) {
            notify("Auto-attach failed: ${t.message}", NotificationType.WARNING)
            Result(false, message = t.message)
        }
    }

    private fun listCandidateVms(): List<VmCandidate> {
        return try {
            val scored = com.sun.tools.attach.VirtualMachine.list().mapNotNull { vm ->
                val props = readVmProperties(vm)
                val score = scoreVm(vm, props)
                if (score > 0) VmCandidate(vm, score) else null
            }
            scored.sortedWith(compareByDescending<VmCandidate> { it.score }
                .thenBy { it.descriptor.displayName() })
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun scoreVm(vm: com.sun.tools.attach.VirtualMachineDescriptor, props: Properties?): Int {
        val name = vm.displayName().lowercase()
        var score = 0
        // Display-name heuristics
        if ("org.springframework.boot.loader.jarlauncher" in name) score += 4
        if ("org.springframework.boot.loader.propertieslauncher" in name) score += 4
        if ("springapplication" in name) score += 3
        if ("springboot" in name) score += 3
        if ("spring" in name && "boot" in name) score += 2
        if ("org.springframework.boot" in name) score += 2
        if ("-dspring.profiles" in name || "-dspring.application" in name) score += 2
        if ("-jar" in name || name.endsWith(".jar")) score += 1
        if ("application" in name) score += 1

        // System property heuristics
        val cmd = props?.getProperty("sun.java.command")?.lowercase().orEmpty()
        if (cmd.contains("org.springframework.boot")) score += 5
        if (cmd.contains("spring.application")) score += 2
        if (cmd.contains("springbootapplication")) score += 3
        if (cmd.contains("jarlauncher") || cmd.contains("propertieslauncher")) score += 3

        val springApp = props?.getProperty("spring.application.name")
        if (!springApp.isNullOrBlank()) score += 3

        val serverPort = props?.getProperty("server.port")
        val managementPort = props?.getProperty("management.server.port")
        if (!serverPort.isNullOrBlank()) score += 2
        if (!managementPort.isNullOrBlank()) score += 1

        return score
    }

    private fun pickPort(preferred: Int): Pair<Int, Boolean> {
        if (isPortFree(preferred)) return preferred to false
        val fallback = findEphemeralPort()
        return (fallback ?: preferred) to (fallback != null)
    }

    private fun isPortFree(port: Int): Boolean {
        if (port <= 0 || port > 65535) return false
        return try {
            java.net.ServerSocket(port).use { true }
        } catch (_: Exception) { false }
    }

    private fun findEphemeralPort(): Int? {
        return try {
            java.net.ServerSocket(0).use { it.localPort }
        } catch (_: Exception) { null }
    }

    private fun resolveAgentJar(settings: PluginSettingsState.State): String? {
        val explicit = settings.agentJarPath.trim().takeIf { it.isNotEmpty() && File(it).exists() }
        if (explicit != null) return explicit

        BundledAgentProvider.getBundledAgentJar()?.let { return it }

        val version = settings.agentMavenVersion.trim().ifBlank { PluginSettingsState.DEFAULT_AGENT_VERSION }
        val home = System.getProperty("user.home") ?: return null
        val m2 = Paths.get(home, ".m2", "repository", "hu", "baader", "sb-repl-agent", version,
            "sb-repl-agent-$version.jar").toFile()
        if (m2.exists()) return m2.absolutePath

        // Try to resolve via Maven in the background (silent).
        var resolved: String? = null
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                resolved = tryResolveViaMaven(version, m2)
            },
            "Resolving sb-repl-agent $version…",
            true,
            project
        )
        return resolved
    }

    private fun tryResolveViaMaven(version: String, candidate: File): String? {
        return try {
            val mvn = detectMavenExecutable()
            val process = ProcessBuilder(
                mvn, "-q", "dependency:get",
                "-Dartifact=hu.baader:sb-repl-agent:$version",
                "-Dtransitive=false"
            ).redirectErrorStream(true).start()
            process.inputStream.bufferedReader().use { it.readText() }
            val exit = process.waitFor()
            if (exit == 0 && candidate.exists()) candidate.absolutePath else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun detectMavenExecutable(): String {
        val os = System.getProperty("os.name")?.lowercase() ?: return "mvn"
        return if (os.contains("win")) "mvn.cmd" else "mvn"
    }

    private fun readVmProperties(vm: com.sun.tools.attach.VirtualMachineDescriptor): Properties? {
        return try {
            val attached = com.sun.tools.attach.VirtualMachine.attach(vm)
            try {
                attached.systemProperties
            } finally {
                try { attached.detach() } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun notify(message: String, type: NotificationType) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Spring Boot REPL")
                .createNotification(message, type)
                .notify(project)
        }
    }
}
