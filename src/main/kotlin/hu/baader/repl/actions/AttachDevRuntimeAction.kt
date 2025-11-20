package hu.baader.repl.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import hu.baader.repl.nrepl.NreplService
import hu.baader.repl.settings.PluginSettingsState
import hu.baader.repl.agent.BundledAgentProvider
import java.io.File
import java.nio.file.Paths

import com.intellij.icons.AllIcons

class AttachDevRuntimeAction : AnAction("Attach & Inject Dev Runtime", "Attach to a running JVM and inject the development runtime", AllIcons.Actions.Compile) {
    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val settings = PluginSettingsState.getInstance().state
        val agentJar = resolveAgentJar(project, settings) ?: return

        // List JVMs
        val vms = try {
            com.sun.tools.attach.VirtualMachine.list()
        } catch (t: Throwable) {
            notify(project, "Attach API not available: ${t.message}", NotificationType.ERROR)
            return
        }
        if (vms.isEmpty()) {
            notify(project, "No JVM processes found.", NotificationType.WARNING)
            return
        }

        val items = vms.map { "${it.id()}  ${it.displayName()}" }.toTypedArray()
        val idx = Messages.showChooseDialog(
            "Select target JVM to inject dev-runtime agent",
            "Attach & Inject Dev Runtime",
            items,
            items.first(),
            null
        )
        if (idx < 0) return
        val vmDesc = vms[idx]

        // Build agent args: port and optional token
        val args = "port=${settings.agentPort}"

        try {
            val vm = com.sun.tools.attach.VirtualMachine.attach(vmDesc)
            try {
                vm.loadAgent(agentJar, args)
            } finally {
                try { vm.detach() } catch (_: Throwable) {}
            }

            // Save the port used for the agent to settings
            PluginSettingsState.getInstance().state.port = settings.agentPort
            notify(project, "Agent injected into PID ${vmDesc.id()} on port ${settings.agentPort}. You can now press 'Connect' in the REPL window.", NotificationType.INFORMATION)

        } catch (t: Throwable) {
            notify(project, "Attach failed: ${t.message}", NotificationType.ERROR)
        }
    }

    private fun notify(project: Project, msg: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Spring Boot REPL")
            .createNotification(msg, type)
            .notify(project)
    }

    private fun resolveAgentJar(project: Project, settings: PluginSettingsState.State): String? {
        val explicit = settings.agentJarPath.trim()
        if (explicit.isNotEmpty() && File(explicit).exists()) {
            return explicit
        }

        // Prefer a locally built dev-runtime agent when working from source
        val devRt = resolveDevRuntimeFromProject()
        if (devRt != null) {
            val path = devRt.absolutePath
            settings.agentJarPath = path
            notify(project, "Using dev-runtime agent (${devRt.name}).", NotificationType.INFORMATION)
            return path
        }

        BundledAgentProvider.getBundledAgentJar()?.let {
            notify(project, "Using bundled dev-runtime agent.", NotificationType.INFORMATION)
            return it
        }

        // Fallback to Maven local wrapper; if missing, try to resolve via Maven
        val autoJar = resolveAgentFromMaven(project, settings)
        if (autoJar != null) {
            val path = autoJar.absolutePath
            settings.agentJarPath = path
            notify(project, "Using sb-repl-agent from Maven local (${autoJar.name}).", NotificationType.INFORMATION)
            return path
        }

        // Offer a file chooser to pick the agent JAR on-the-fly
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
            .withTitle("Select Dev Runtime Agent JAR")
        val file: VirtualFile? = FileChooser.chooseFile(descriptor, project, null)
        if (file != null && !file.isDirectory) {
            val chosen = file.path
            if (chosen.endsWith(".jar", ignoreCase = true) && File(chosen).exists()) {
                settings.agentJarPath = chosen
                return chosen
            }
        }

        // Fallback: prompt for path manually
        val manual = Messages.showInputDialog(
            project,
            "Enter absolute path to dev-runtime-agent JAR:",
            "Dev Runtime Agent Path",
            null,
            settings.agentJarPath,
            null
        )
        if (manual.isNullOrBlank() || !File(manual).exists()) {
            notify(project, "Agent JAR not selected.", NotificationType.WARNING)
            return null
        } else {
            val path = manual.trim()
            settings.agentJarPath = path
            return path
        }
    }

    private fun resolveAgentFromMaven(project: Project, settings: PluginSettingsState.State): File? {
        val version = settings.agentMavenVersion.trim().ifBlank { PluginSettingsState.DEFAULT_AGENT_VERSION }
        val home = System.getProperty("user.home") ?: return null
        val candidate = Paths.get(home, ".m2", "repository", "hu", "baader", "sb-repl-agent", version,
            "sb-repl-agent-$version.jar").toFile()
        if (candidate.exists()) return candidate

        // Attempt to resolve via Maven (dependency:get) so the JAR appears in ~/.m2.
        var resolved: File? = null
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                resolved = try {
                    val mvnExecutable = detectMavenExecutable()
                    val process = ProcessBuilder(
                        mvnExecutable,
                        "-q",
                        "dependency:get",
                        "-Dartifact=hu.baader:sb-repl-agent:$version",
                        "-Dtransitive=false"
                    )
                        .redirectErrorStream(true)
                        .start()

                    // Drain output so the process does not block on full buffers.
                    process.inputStream.bufferedReader().use { it.readText() }
                    val exitCode = process.waitFor()
                    if (exitCode == 0 && candidate.exists()) candidate else null
                } catch (_: Throwable) {
                    null
                }
            },
            "Resolving sb-repl-agent $version via Maven…",
            true,
            project
        )
        return resolved
    }

    private fun detectMavenExecutable(): String {
        val os = System.getProperty("os.name")?.lowercase() ?: return "mvn"
        return if (os.contains("win")) "mvn.cmd" else "mvn"
    }

    private fun resolveDevRuntimeFromProject(): File? {
        return try {
            val base = System.getProperty("user.dir") ?: return null
            val dir = Paths.get(base, "dev-runtime", "build", "libs").toFile()
            if (dir.exists()) {
                dir.listFiles { f -> f.isFile && f.name.endsWith(".jar") && f.name.contains("dev-runtime-agent") }?.maxByOrNull { it.lastModified() }
            } else null
        } catch (_: Throwable) { null }
    }
}
