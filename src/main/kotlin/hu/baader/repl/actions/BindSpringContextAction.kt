package hu.baader.repl.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import hu.baader.repl.nrepl.NreplService
import com.intellij.icons.AllIcons

class BindSpringContextAction : AnAction("Bind Spring Context", "Connect to Spring ApplicationContext for bean access", AllIcons.Actions.Lightning) {
    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val svc = hu.baader.repl.nrepl.NreplService.getInstance(project)
        if (!svc.isConnected()) {
            notify(project, "Not connected to dev runtime", NotificationType.WARNING)
            return
        }

        // Ask for optional custom expression
        val expr = Messages.showInputDialog(
            project,
            "Optional: custom Java expression to obtain ApplicationContext (leave empty for auto):",
            "Bind Spring Context",
            null
        )?.trim()

        if (!expr.isNullOrBlank()) {
            svc.bindSpring(expr, onResult = { v ->
                notify(project, "Bind Spring (custom expr): $v", NotificationType.INFORMATION)
            }, onError = { err ->
                notify(project, "Bind Spring error: $err", NotificationType.ERROR)
            })
        } else {
            svc.bindSpring(null, onResult = { v ->
                notify(project, "Bind Spring (auto): $v", NotificationType.INFORMATION)
            }, onError = { err ->
                notify(project, "Bind Spring error: $err", NotificationType.ERROR)
            })
        }
    }

    private fun notify(project: Project, msg: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Java REPL")
            .createNotification(msg, type)
            .notify(project)
    }
}
