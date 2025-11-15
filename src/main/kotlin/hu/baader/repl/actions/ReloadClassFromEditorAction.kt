package hu.baader.repl.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import hu.baader.repl.nrepl.NreplService

class ReloadClassFromEditorAction : AnAction("Reload Class") {

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return

        val text = editor.document.text
        if (text.isBlank()) {
            notify(project, "No Java source to reload", NotificationType.WARNING)
            return
        }

        val service = NreplService.getInstance(project)
        if (!service.isConnected()) {
            notify(project, "Not connected to nREPL server", NotificationType.WARNING)
            return
        }

        try {
            service.hotSwap(
                text,
                onResult = { msg ->
                    notify(project, msg.ifBlank { "HotSwap completed" }, NotificationType.INFORMATION)
                },
                onError = { err ->
                    notify(project, "HotSwap error: $err", NotificationType.ERROR)
                }
            )
        } catch (ex: Exception) {
            notify(project, "HotSwap failed: ${ex.message}", NotificationType.ERROR)
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabled = project != null && editor != null
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Spring Boot REPL")
            .createNotification(message, type)
            .notify(project)
    }
}
