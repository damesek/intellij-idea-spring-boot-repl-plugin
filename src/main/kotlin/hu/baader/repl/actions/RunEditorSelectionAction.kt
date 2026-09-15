package hu.baader.repl.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import hu.baader.repl.nrepl.NreplService

class RunEditorSelectionAction : AnAction("Run Selection") {
    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        
        val text = editor.selectionModel.selectedText.orEmpty()
        
        if (text.isBlank()) {
            showNotification(project, "Select the Java snippet to execute", NotificationType.WARNING)
            return
        }
        
        EvaluateAtCaretAction().evaluate(project, editor, text)
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
