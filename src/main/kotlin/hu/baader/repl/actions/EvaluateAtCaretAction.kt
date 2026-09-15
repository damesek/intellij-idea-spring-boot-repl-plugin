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
import com.intellij.psi.PsiThrowStatement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiStatement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import hu.baader.repl.settings.PluginSettingsState
import hu.baader.repl.nrepl.NreplService

class EvaluateAtCaretAction : AnAction("Evaluate at Caret") {

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)

        val selectionText = editor.selectionModel.selectedText
        val code = when {
            !selectionText.isNullOrBlank() -> selectionText
            psiFile != null -> findExpressionOrStatementAtCaret(project, psiFile, editor)
            else -> null
        }

        if (code.isNullOrBlank()) {
            notify(project, "No expression found at caret", NotificationType.WARNING)
            return
        }

        evaluate(project, editor, code)
    }

    internal fun evaluate(project: Project, editor: Editor, code: String) {
        val file = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(editor.document)
        if (file?.getUserData(hu.baader.repl.editor.JavaReplEditorProvider.REPL_FILE) == true) {
            val actions = WorkbenchActions.get(project)
            val reason = actions.reason(WorkbenchCatalog.byKey("RunCell")!!)
            if (reason == null) actions.perform("RunCell") else notify(project, reason, NotificationType.WARNING)
            return
        }
        val service = NreplService.getInstance(project)
        if (!service.isConnected()) {
            notify(project, "Not connected to nREPL server", NotificationType.WARNING)
            return
        }

        val stamp = editor.document.modificationStamp
        val anchor = editor.document.createRangeMarker(editor.document.getLineEndOffset(editor.caretModel.logicalPosition.line), editor.document.getLineEndOffset(editor.caretModel.logicalPosition.line))
        val target = service.debuggerTarget()
        fun present(response: Map<String,String>) {
            val offset = if (anchor.isValid) anchor.startOffset else editor.caretModel.offset
            anchor.dispose()
            if (!editor.isDisposed && PluginSettingsState.getInstance().state.showInlineResultPopupForCaretEval)
                hu.baader.repl.editor.SourceResultInlays.get(project).show(editor, offset, stamp, target, response)
        }
        service.eval(code, onResult = ::present, onError = { present(mapOf("err" to it)); notify(project, it, NotificationType.ERROR) })
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.EDITOR) != null
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

        // Find the closest containing statement (throw, expression, declaration, etc.)
        val stmt: PsiStatement? = PsiTreeUtil.getParentOfType(
            element,
            PsiStatement::class.java,
            false,
            PsiClass::class.java,
            PsiMethod::class.java
        )

        // Prefer a meaningful expression at caret, but avoid returning just a bare literal
        // inside a larger construct; climb up through nested PsiExpression nodes.
        var expr: PsiExpression? = PsiTreeUtil.getParentOfType(
            element,
            PsiExpression::class.java,
            false,
            PsiClass::class.java,
            PsiMethod::class.java
        )

        while (expr != null && expr.parent is PsiExpression && expr.parent !is PsiExpressionStatement) {
            expr = expr.parent as? PsiExpression
        }

        // If we are inside an expression statement (e.g. service.call();), use just the expression.
        if (expr != null && expr.parent is PsiExpressionStatement) {
            return expr.text.trim().takeIf { it.isNotEmpty() }
        }

        // If the caret is inside a throw statement, prefer the whole throw for clarity.
        if (expr != null && expr.parent is PsiThrowStatement && stmt != null) {
            return stmt.text.trim().takeIf { it.isNotEmpty() }
        }

        // Otherwise, fall back to the best expression we have.
        val exprText = expr?.text?.trim()
        if (!exprText.isNullOrEmpty()) {
            return exprText
        }

        // As a last resort, use the full statement text (declaration, return, throw, etc.).
        val stmtText = stmt?.text?.trim()
        return stmtText?.takeIf { it.isNotEmpty() }
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Spring Boot REPL")
            .createNotification(message, type)
            .notify(project)
    }

}
