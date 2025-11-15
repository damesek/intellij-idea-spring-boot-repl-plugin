package hu.baader.repl.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import hu.baader.repl.nrepl.NreplService

class RunEditorSelectionAction : AnAction("Run Selection") {
    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        
        val text = editor.selectionModel.selectedText ?: editor.document.text
        
        if (text.isBlank()) {
            showNotification(project, "No code to execute", NotificationType.WARNING)
            return
        }
        
        val service = NreplService.getInstance(project)
        
        if (!service.isConnected()) {
            showNotification(project, "Not connected to nREPL server", NotificationType.WARNING)
            return
        }
        
        try {
            service.eval(text)
            // Bring the Spring Boot REPL tool window to front so the user
            // immediately sees the result and can continue in the console.
            ToolWindowManager.getInstance(project)
                .getToolWindow("Spring Boot REPL")
                ?.activate(null, true)
            showNotification(project, "Code sent to Spring Boot REPL", NotificationType.INFORMATION)
        } catch (ex: Exception) {
            showNotification(project, "Failed to execute: ${ex.message}", NotificationType.ERROR)
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        
        e.presentation.isEnabled = project != null && editor != null
        
        if (project != null) {
            val service = NreplService.getInstance(project)
            e.presentation.text = if (service.isConnected()) {
                "Run Selection (Connected)"
            } else {
                "Run Selection (Disconnected)"
            }
        }
    }
    
    private fun showNotification(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Spring Boot REPL")
            .createNotification(message, type)
            .notify(project)
    }
}
