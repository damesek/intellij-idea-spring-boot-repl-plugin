package hu.baader.repl.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import hu.baader.repl.nrepl.NreplService
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.Timer

/** Debounced compiler-only checking; edits immediately remove diagnostics from an older document. */
class ReplDiagnosticsController(private val project: Project, private val editor: Editor, private val service: NreplService,
                                private val idle: () -> Boolean, private val feedback: (String) -> Unit) : Disposable {
    private var disposed = false
    private var enabled = true
    private var generation = 0L
    private var active: AnalysisRequest? = null
    private var pending = false
    private val highlights = mutableListOf<RangeHighlighter>()
    private var shownIssues = emptyList<AnalysisRequest.Issue>()
    private val hover = object : MouseMotionAdapter() {
        override fun mouseMoved(event: MouseEvent) {
            if (editor.isDisposed) return
            val offset = editor.logicalPositionToOffset(editor.xyToLogicalPosition(event.point))
            editor.contentComponent.toolTipText = shownIssues.firstOrNull { offset in it.start until it.end }?.let { "<html>${escape(it.message)}</html>" }
        }
    }
    private val timer = Timer(650) { request() }.apply { isRepeats = false }
    init {
        editor.contentComponent.addMouseMotionListener(hover)
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = invalidate()
        }, this)
        Disposer.register(this, service.onMessage {
            if (it["op"] == "connection" && it["state"] in setOf("DISCONNECTED", "CONNECTING", "FAILED")) active = null
            if (it["op"] in setOf("connection", "session/reset", "eval", "java-eval", "snapshot/load", "vars/drop", "imports/add", "class-reload", "inspector/bind", "debug/claim")) invalidate()
        })
        invalidate()
    }
    fun setEnabled(value: Boolean) { enabled = value; invalidate() }
    fun invalidate() {
        generation++; clear(); timer.stop()
        if (disposed) return
        if (!enabled) { feedback("Live check off"); return }
        feedback(if (service.isConnected()) "Code check pending · no execution" else "Connect to check against the REPL session")
        if (active != null) pending = true else timer.restart()
    }
    fun request() {
        if (disposed || !enabled || editor.isDisposed || project.isDisposed) return
        if (!service.isConnected()) { feedback("Connect to check against the REPL session"); return }
        if (active != null) { pending = true; return }
        if (!idle()) { feedback("Code check waits for evaluation to finish"); timer.restart(); return }
        val source = editor.document.text
        if (source.length > 100_000) { feedback("Live check limit: 100000 characters"); return }
        if (source.isBlank()) { clear(); feedback("No code to check"); return }
        val query = AnalysisRequest(source, editor.document.modificationStamp, generation)
        active = query; pending = false; feedback("Checking code · no execution")
        fun finish(reply: Map<String, String>?, error: String?) {
            if (active !== query) return
            active = null
            if (disposed || editor.isDisposed || project.isDisposed) return
            if (query.matches(editor.document.modificationStamp, generation) && enabled) {
                clear()
                if (error != null) feedback("Code check: $error")
                else {
                    val issues = query.issues(reply?.get("diagnostics").orEmpty())
                    shownIssues = issues
                    for (issue in issues) {
                        val key = if (issue.severity == "ERROR") CodeInsightColors.ERRORS_ATTRIBUTES else CodeInsightColors.WARNINGS_ATTRIBUTES
                        val attributes = editor.colorsScheme.getAttributes(key)
                        val highlight = editor.markupModel.addRangeHighlighter(issue.start, issue.end, HighlighterLayer.ERROR, attributes, HighlighterTargetArea.EXACT_RANGE)
                        highlight.errorStripeMarkColor = attributes?.errorStripeColor ?: attributes?.effectColor
                        highlight.errorStripeTooltip = "<html>${escape(issue.message)}</html>"
                        highlights += highlight
                    }
                    val errors = issues.count { it.severity == "ERROR" }; val warnings = issues.size - errors
                    val partial = reply?.get("analysis-limited") == "true" || reply?.get("context-incomplete") == "true"
                    feedback(if (partial) "Partial code check · $errors errors, $warnings warnings"
                        else if (issues.isEmpty()) "Code check OK · no execution" else "$errors errors, $warnings warnings · hover the underlined code")
                }
            }
            if (pending) { pending = false; timer.restart() }
        }
        service.request("analyze", mapOf("code" to source), { finish(it, null) }, { finish(null, it) })
    }
    private fun clear() {
        if (!editor.isDisposed) { highlights.forEach { editor.markupModel.removeHighlighter(it) }; editor.contentComponent.toolTipText = null }
        shownIssues = emptyList()
        highlights.clear()
    }
    override fun dispose() { disposed = true; generation++; timer.stop(); clear(); editor.contentComponent.removeMouseMotionListener(hover) }
    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
