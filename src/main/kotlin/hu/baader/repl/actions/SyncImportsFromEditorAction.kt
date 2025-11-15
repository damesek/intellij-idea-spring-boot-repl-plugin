package hu.baader.repl.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiJavaFile
import hu.baader.repl.nrepl.NreplService

class SyncImportsFromEditorAction : AnAction("Sync Imports") {

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)

        if (psiFile !is PsiJavaFile) {
            notify(project, "Current file is not a Java file", NotificationType.WARNING)
            return
        }

        val importList = psiFile.importList
        val imports = importList?.allImportStatements
            ?.mapNotNull { it.text?.trim()?.takeIf { t -> t.startsWith("import") } }
            ?.toList()
            ?: emptyList()

        if (imports.isEmpty()) {
            notify(project, "No imports found to sync", NotificationType.INFORMATION)
            return
        }

        val service = NreplService.getInstance(project)
        if (!service.isConnected()) {
            notify(project, "Not connected to nREPL server", NotificationType.WARNING)
            return
        }

        try {
            service.addImports(
                imports,
                onResult = {
                    notify(
                        project,
                        "Synced ${imports.size} imports to Spring Boot REPL",
                        NotificationType.INFORMATION
                    )
                },
                onError = { err ->
                    notify(project, "Import sync error: $err", NotificationType.ERROR)
                }
            )
        } catch (ex: Exception) {
            notify(project, "Import sync failed: ${ex.message}", NotificationType.ERROR)
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
