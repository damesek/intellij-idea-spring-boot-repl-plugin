package hu.baader.repl.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiExpressionStatement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiStatement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import hu.baader.repl.nrepl.NreplService

class EvaluateAtCaretAction : AnAction("Evaluate at Caret") {

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return

        val selectionText = editor.selectionModel.selectedText
        val code = when {
            !selectionText.isNullOrBlank() -> selectionText
            else -> findExpressionOrStatementAtCaret(project, psiFile, editor)
        }

        if (code.isNullOrBlank()) {
            notify(project, "No expression found at caret", NotificationType.WARNING)
            return
        }

        val service = NreplService.getInstance(project)
        if (!service.isConnected()) {
            notify(project, "Not connected to nREPL server", NotificationType.WARNING)
            return
        }

        try {
            service.eval(code)
            notify(project, "Expression sent to Java REPL", NotificationType.INFORMATION)
        } catch (ex: Exception) {
            notify(project, "Failed to evaluate: ${ex.message}", NotificationType.ERROR)
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabled = project != null && editor != null
    }

    private fun findExpressionOrStatementAtCaret(project: Project, psiFile: PsiFile, editor: Editor): String? {
        if (psiFile !is PsiJavaFile) {
            return null
        }

        val document = editor.document
        PsiDocumentManager.getInstance(project).commitDocument(document)

        val offset = editor.caretModel.offset.coerceIn(0, document.textLength.coerceAtLeast(1) - 1)

        var element = psiFile.findElementAt(offset)
        if (element is PsiWhiteSpace || element == null) {
            element = psiFile.findElementAt((offset - 1).coerceAtLeast(0))
        }

        if (element == null || element is PsiWhiteSpace || element is PsiComment) {
            return null
        }

        // Felfelé keresünk egy kifejezésig, majd szükség esetén a hozzá tartozó statementig
        var expr: PsiExpression? = PsiTreeUtil.getParentOfType(
            element,
            PsiExpression::class.java,
            false,
            PsiClass::class.java,
            PsiMethod::class.java
        )

        if (expr == null) {
            val stmt = PsiTreeUtil.getParentOfType(
                element,
                PsiStatement::class.java,
                false,
                PsiClass::class.java,
                PsiMethod::class.java
            )
            return stmt?.text?.trim()?.takeIf { it.isNotEmpty() }
        }

        // Ha a kifejezés egy expression statement része (pl. függvényhívás;),
        // akkor inkább csak az expression szövegét küldjük, nem az egész statementet.
        if (expr.parent is PsiExpressionStatement) {
            return expr.text.trim().takeIf { it.isNotEmpty() }
        }

        return expr.text.trim().takeIf { it.isNotEmpty() }
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("SB Tools")
            .createNotification(message, type)
            .notify(project)
    }
}
