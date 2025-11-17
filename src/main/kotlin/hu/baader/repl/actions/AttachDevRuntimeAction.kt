package hu.baader.repl.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.application.ApplicationManager
import hu.baader.repl.nrepl.NreplService
import hu.baader.repl.settings.PluginSettingsState
import java.io.File
import java.nio.file.Paths

import com.intellij.icons.AllIcons

class AttachDevRuntimeAction : AnAction("Attach & Inject Dev Runtime", "Attach to a running JVM and inject the development runtime", AllIcons.Actions.Compile) {
    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val settings = PluginSettingsState.getInstance().state

        var agentJar = settings.agentJarPath.trim()
        if (agentJar.isEmpty() || !File(agentJar).exists()) {
            // Prefer dev-runtime agent built from this repo if available (JShell features)
            val devRt = resolveDevRuntimeFromProject()
            if (devRt != null) {
                agentJar = devRt.absolutePath
                settings.agentJarPath = agentJar
                notify(project, "Using dev-runtime agent (${devRt.name}).", NotificationType.INFORMATION)
            }
        }
        if (agentJar.isEmpty() || !File(agentJar).exists()) {
            // Fallback to Maven local wrapper; if missing, try to resolve via Maven
            val autoJar = resolveAgentFromMaven(project, settings)
            if (autoJar != null) {
                agentJar = autoJar.absolutePath
                settings.agentJarPath = agentJar
                notify(project, "Using sb-repl-agent from Maven local (${autoJar.name}).", NotificationType.INFORMATION)
            }
        }
        if (agentJar.isEmpty() || !File(agentJar).exists()) {
            // Offer a file chooser to pick the agent JAR on-the-fly
            val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
                .withTitle("Select Dev Runtime Agent JAR")
            val file: VirtualFile? = FileChooser.chooseFile(descriptor, project, null)
            if (file != null && !file.isDirectory) {
                val chosen = file.path
                if (chosen.endsWith(".jar", ignoreCase = true) && File(chosen).exists()) {
                    agentJar = chosen
                    settings.agentJarPath = agentJar
                }
            }
            if (agentJar.isEmpty() || !File(agentJar).exists()) {
                // Fallback: prompt for path manually
                val manual = Messages.showInputDialog(
                    project,
                    "Enter absolute path to dev-runtime-agent JAR:",
                    "Dev Runtime Agent Path",
                    null,
                    agentJar,
                    null
                )
                if (manual.isNullOrBlank() || !File(manual).exists()) {
                    notify(project, "Agent JAR not selected.", NotificationType.WARNING)
                    return
                } else {
                    agentJar = manual.trim()
                    settings.agentJarPath = agentJar
                }
            }
        }

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
            notify(project, "Agent injected into PID ${vmDesc.id()}.", NotificationType.INFORMATION)

            // Auto-connect to the dev runtime server
            val svc = NreplService.getInstance(project)
            try {
                // Use configured host/port (host likely 127.0.0.1)
                svc.disconnect()
                PluginSettingsState.getInstance().state.host = "127.0.0.1"
                PluginSettingsState.getInstance().state.port = settings.agentPort
                svc.connectAsync()
                notify(project, "Connecting to dev runtime on ${settings.agentPort}…", NotificationType.INFORMATION)
                // After connect, check capabilities; if legacy mode, offer to pick dev-runtime jar
                ApplicationManager.getApplication().executeOnPooledThread {
                    try { Thread.sleep(1500) } catch (_: InterruptedException) {}
                    ApplicationManager.getApplication().invokeLater {
                        if (!svc.isJshellMode()) {
                            notify(project, "Legacy agent detected (no JShell). Select dev-runtime-agent JAR for full features.", NotificationType.WARNING)
                        }
                    }
                }
            } catch (t: Throwable) {
                notify(project, "Connect failed after injection: ${t.message}", NotificationType.WARNING)
            }
        } catch (t: Throwable) {
            notify(project, "Attach failed: ${t.message}", NotificationType.ERROR)
        }
    }

    private fun notify(project: Project, msg: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Java REPL")
            .createNotification(msg, type)
            .notify(project)
    }

    private fun resolveAgentFromMaven(project: Project, settings: PluginSettingsState.State): File? {
        val version = settings.agentMavenVersion.trim().ifBlank { PluginSettingsState.DEFAULT_AGENT_VERSION }
        val home = System.getProperty("user.home") ?: return null
        val candidate = Paths.get(home, ".m2", "repository", "hu", "baader", "sb-repl-agent", version,
            "sb-repl-agent-$version.jar").toFile()
        if (candidate.exists()) return candidate

        // Attempt to resolve via Maven (dependency:get) so the JAR appears in ~/.m2.
        return ProgressManager.getInstance().runProcessWithProgressSynchronously<File?>(
            {
                try {
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
