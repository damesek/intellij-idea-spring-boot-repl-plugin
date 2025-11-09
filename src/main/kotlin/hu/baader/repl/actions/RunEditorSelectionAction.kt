package hu.baader.repl.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import hu.baader.repl.nrepl.NreplService

class RunEditorSelectionAction : AnAction("Run Selection in Java REPL") {
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
            service.evalJava(text)
            showNotification(project, "Code sent to Java REPL", NotificationType.INFORMATION)
        } catch (ex: Exception) {
            showNotification(project, "Failed to execute: ${ex.message}", NotificationType.ERROR)
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        
        e.presentation.isEnabled = project != null && editor != null
        
        // Show connection status in text
        if (project != null) {
            val service = NreplService.getInstance(project)
            e.presentation.text = if (service.isConnected()) {
                "Run Selection in Java REPL (Connected)"
            } else {
                "Run Selection in Java REPL (Disconnected)"
            }
        }
    }
    
    private fun showNotification(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Java REPL")
            .createNotification(message, type)
            .notify(project)
    }
}