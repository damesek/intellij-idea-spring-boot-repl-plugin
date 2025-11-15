package hu.baader.repl.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
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
import com.intellij.openapi.util.TextRange
import hu.baader.repl.settings.PluginSettingsState
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
            val settings = PluginSettingsState.getInstance().state

            if (!settings.showInlineResultPopupForCaretEval) {
                service.debugLog("[EvaluateAtCaret] sending (REPL-only) code:\n$code")
                service.eval(code)
                notify(project, "Expression sent to Spring Boot REPL", NotificationType.INFORMATION)
                return
            }

            val editorForResult = editor
            val disposableRef = arrayOf<com.intellij.openapi.Disposable?>(null)
            val listener: (Map<String, String>) -> Unit = listener@{ msg ->
                service.debugLog("[EvaluateAtCaret] received msg: $msg")
                val value = msg["value"]
                val err = msg["err"]
                val exText = msg["ex"]
                val output = msg["output"]

                val rawText = when {
                    value != null -> value
                    exText != null -> "Exception: $exText"
                    err != null -> err
                    output != null && output.isNotBlank() -> output
                    else -> return@listener
                }

                val pretty = prettyPrintJsonIfLikely(rawText)
                val finalText = pretty ?: rawText

                WriteCommandAction.runWriteCommandAction(project) {
                    val document = editorForResult.document
                    val caretModel = editorForResult.caretModel
                    val line = caretModel.logicalPosition.line
                    if (line < 0 || line >= document.lineCount) {
                        return@runWriteCommandAction
                    }
                    val lineStart = document.getLineStartOffset(line)
                    val lineEnd = document.getLineEndOffset(line)
                    val lineText = document.getText(TextRange(lineStart, lineEnd))
                    val indent = lineText.takeWhile { it == ' ' || it == '\t' }

                    val lines = finalText.lines()
                    if (lines.isEmpty()) {
                        return@runWriteCommandAction
                    }

                    val comment = buildString {
                        append("\n")
                        append(indent)
                        append("// => ")
                        append(lines.first())
                        append("\n")
                        for (extra in lines.drop(1)) {
                            append(indent)
                            append("//    ")
                            append(extra)
                            append("\n")
                        }
                    }

                    document.insertString(lineEnd, comment)
                }

                disposableRef[0]?.dispose()
            }
            val disposable = service.onMessage(listener)
            disposableRef[0] = disposable
            service.eval(code)
        } catch (ex: Exception) {
            notify(project, "Failed to evaluate: ${ex.message}", NotificationType.ERROR)
        }
    }

    override fun update(e: AnActionEvent) {
        // Temporarily disabled in UI to avoid confusing behaviour.
        e.presentation.isEnabledAndVisible = false
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

    private fun prettyPrintJsonIfLikely(raw: String?): String? {
        val original = raw?.trim() ?: return null
        if (original.length < 2) return null

        val s = if (original.first() == '"' && original.last() == '"') {
            val inner = original.substring(1, original.length - 1)
            buildString {
                var escape = false
                for (ch in inner) {
                    if (escape) {
                        when (ch) {
                            '\\' -> append('\\')
                            '"' -> append('"')
                            'n' -> append('\n')
                            'r' -> append('\r')
                            't' -> append('\t')
                            else -> append(ch)
                        }
                        escape = false
                    } else if (ch == '\\') {
                        escape = true
                    } else {
                        append(ch)
                    }
                }
            }.trim()
        } else {
            original
        }

        if (s.length < 2) return null
        val first = s.first()
        val last = s.last()
        if (!((first == '{' && last == '}') || (first == '[' && last == ']'))) return null
        if (!s.contains(':') && !s.contains('"')) return null

        val sb = StringBuilder()
        var indent = 0
        var inString = false
        var escape = false

        fun appendIndent() {
            repeat(indent) { sb.append("  ") }
        }

        for (ch in s) {
            when {
                inString -> {
                    sb.append(ch)
                    if (escape) {
                        escape = false
                    } else if (ch == '\\') {
                        escape = true
                    } else if (ch == '"') {
                        inString = false
                    }
                }
                ch == '"' -> {
                    inString = true
                    sb.append(ch)
                }
                ch == '{' || ch == '[' -> {
                    sb.append(ch)
                    sb.append('\n')
                    indent++
                    appendIndent()
                }
                ch == '}' || ch == ']' -> {
                    sb.append('\n')
                    indent--
                    if (indent < 0) indent = 0
                    appendIndent()
                    sb.append(ch)
                }
                ch == ',' -> {
                    sb.append(ch)
                    sb.append('\n')
                    appendIndent()
                }
                ch == ':' -> {
                    sb.append(": ")
                }
                ch.isWhitespace() -> {
                    // skip
                }
                else -> {
                    sb.append(ch)
                }
            }
        }

        return sb.toString()
    }
}
