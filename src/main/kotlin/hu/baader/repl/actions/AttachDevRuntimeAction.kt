package hu.baader.repl.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import hu.baader.repl.agent.AgentJarResolver
import hu.baader.repl.nrepl.NreplService
import java.nio.file.Files
import java.util.Base64

class AttachDevRuntimeAction : AnAction("Attach & Inject Dev Runtime") {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            try {
                val jar = AgentJarResolver.resolve() ?: error("Agent JAR not found. Configure it in Settings.")
                val vms = com.sun.tools.attach.VirtualMachine.list().filter { it.id() != ProcessHandle.current().pid().toString() }
                app.invokeLater {
                    if (project.isDisposed) return@invokeLater
                    if (vms.isEmpty()) { Messages.showInfoMessage(project, "No attachable JVM found.", "Spring Boot REPL"); return@invokeLater }
                    val items = vms.map { it.id() + "  " + it.displayName() }.toTypedArray()
                    val selected = Messages.showChooseDialog("Select the JVM to attach", "Spring Boot REPL", items, items.first(), null)
                    if (selected < 0) return@invokeLater
                    app.executeOnPooledThread {
                        try {
                            val file = Files.createTempFile("sb-repl-attach-", ".properties")
                            file.toFile().deleteOnExit()
                            val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(file.toString().toByteArray(Charsets.UTF_8))
                            val vm = com.sun.tools.attach.VirtualMachine.attach(vms[selected])
                            try { vm.loadAgent(jar.absolutePath, "port=0,endpoint64=" + encoded) } finally { vm.detach() }
                            NreplService.getInstance(project).connectEndpoint(file)
                        } catch (failure: Exception) { notify(project, failure.message.orEmpty()) }
                    }
                }
            } catch (failure: Exception) { notify(project, failure.message.orEmpty()) }
        }
    }
    private fun notify(project: com.intellij.openapi.project.Project, message: String) {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) NotificationGroupManager.getInstance().getNotificationGroup("Spring Boot REPL")
                .createNotification(message, NotificationType.ERROR).notify(project)
        }
    }
}
