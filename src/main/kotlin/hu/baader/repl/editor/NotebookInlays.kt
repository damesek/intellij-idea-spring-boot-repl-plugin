package hu.baader.repl.editor

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.Alarm
import hu.baader.repl.nrepl.NreplService
import hu.baader.repl.ui.SnapshotTargetDialog
import java.awt.Point
import javax.swing.SwingUtilities

class NotebookInlays(private val project: Project, private val editor: Editor, private val notebook: NotebookController,
                     private val service: NreplService, private val inspect: (Map<String,String>) -> Unit,
                     private val showOutput: (String) -> Unit, private val saved: () -> Unit, private val report: (String) -> Unit) : Disposable {
    private data class Entry(val inlay: Inlay<InlineResultRenderer>, val gutter: RangeHighlighter)
    private val entries = mutableMapOf<String, Entry>()
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var disposed = false
    init {
        Disposer.register(this, notebook.onChanged { alarm.cancelAllRequests(); alarm.addRequest({ refresh() }, 100) })
        Disposer.register(this, service.onMessage { m ->
            if (m["op"] in setOf("connection", "execution/selection", "activity")) { alarm.cancelAllRequests(); alarm.addRequest({ refresh() }, 100) }
        })
        editor.scrollingModel.addVisibleAreaListener({ e ->
            if (e.oldRectangle?.width != e.newRectangle.width) { alarm.cancelAllRequests(); alarm.addRequest({ refresh() }, 100) }
        }, this)
        editor.addEditorMouseListener(object : EditorMouseListener {
            override fun mouseClicked(e: EditorMouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e.mouseEvent)) return
                val inlay = e.inlay ?: editor.inlayModel.getElementAt(e.mouseEvent.point) ?: return
                if (entries.values.none { it.inlay === inlay }) return
                val bounds = inlay.bounds ?: return
                if ((inlay.renderer as? InlineResultRenderer)?.click(e.mouseEvent.x - bounds.x, e.mouseEvent.y - bounds.y) == true) e.consume()
            }
        }, this)
        editor.addEditorMouseMotionListener(object : com.intellij.openapi.editor.event.EditorMouseMotionListener {
            override fun mouseMoved(e: EditorMouseEvent) {
                val inlay = e.inlay ?: editor.inlayModel.getElementAt(e.mouseEvent.point)
                val bounds = inlay?.bounds
                val link = bounds != null && entries.values.any { it.inlay === inlay } &&
                    (inlay.renderer as? InlineResultRenderer)?.isLink(e.mouseEvent.x - bounds.x, e.mouseEvent.y - bounds.y) == true
                editor.contentComponent.cursor = java.awt.Cursor.getPredefinedCursor(if (link) java.awt.Cursor.HAND_CURSOR else java.awt.Cursor.TEXT_CURSOR)
            }
        }, this)
        refresh()
    }
    private fun links(id: String): List<InlineResultRenderer.Link> {
        fun handle() = notebook.state.cell(id)?.let(notebook.results::handle)
        return listOf(
            InlineResultRenderer.Link("Run", { service.canExecute() && !service.isExecuting() }) { notebook.runCell(id) },
            InlineResultRenderer.Link("Inspect", { service.isConnected() && handle() != null }) { handle()?.let { inspect(mapOf("handle" to it)) } },
            InlineResultRenderer.Link("Snapshot", { service.isConnected() && handle() != null }) {
                val cell = notebook.state.cell(id)
                handle()?.let { SnapshotTargetDialog.save(project, service, it, "cell-${notebook.state.cells.indexOf(cell) + 1}",
                    "Save the live result of run [${cell?.sequence}]. Its expression is not run again.", saved, report) }
            },
            InlineResultRenderer.Link("Output") { showOutput(id) }
        )
    }
    private fun refresh() {
        if (disposed || editor.isDisposed) return
        val state = notebook.state
        val parts = ReplCells.all(editor.document.text)
        entries.keys.filter { state.cell(it) == null }.toList().forEach(::remove)
        state.cells.forEachIndexed { i, cell ->
            val part = parts.getOrNull(i) ?: return@forEachIndexed
            val start = part.start.coerceAtMost(editor.document.textLength)
            val end = (part.start + cell.source.trimEnd().length).coerceIn(start, editor.document.textLength)
            val line = editor.document.getLineNumber(start)
            var entry = entries[cell.id]
            if (entry != null && (!entry.inlay.isValid || entry.inlay.offset != end || !entry.gutter.isValid || editor.document.getLineNumber(entry.gutter.startOffset) != line)) { remove(cell.id); entry = null }
            if (entry == null) {
                val renderer = InlineResultRenderer("", "", "", false, false, links(cell.id))
                val inlay = editor.inlayModel.addBlockElement(end, true, false, 0, renderer) ?: return@forEachIndexed
                val gutter = editor.markupModel.addLineHighlighter(line, HighlighterLayer.ADDITIONAL_SYNTAX, null)
                gutter.gutterIconRenderer = object : GutterIconRenderer() {
                    override fun getIcon() = AllIcons.Actions.Execute
                    override fun getTooltipText() = "REPL cell ${i + 1}: run, inspect or save a snapshot of its result"
                    override fun isNavigateAction() = true
                    override fun getPopupMenuActions() = renderer.menu()
                    override fun getClickAction() = object : DumbAwareAction() {
                        override fun actionPerformed(e: AnActionEvent) {
                            val popup = ActionManager.getInstance().createActionPopupMenu("SpringRepl.CellGutter", renderer.menu())
                            val point = editor.offsetToXY(part.start.coerceAtMost(editor.document.textLength))
                            popup.component.show(editor.contentComponent, point.x, point.y + editor.lineHeight)
                        }
                    }
                    override fun equals(other: Any?) = this === other
                    override fun hashCode() = cell.id.hashCode()
                }
                entry = Entry(inlay, gutter); entries[cell.id] = entry
            }
            entry.inlay.renderer.apply {
                title = "Cell ${i + 1} · ${if (cell.sequence == 0L) "—" else "[${cell.sequence}]"} · ${cell.status.lowercase()}" + if (cell.sequence > 0) " · ${cell.durationMs} ms" else ""
                preview = cell.output.ifBlank { if (cell.status == "RUNNING") "Evaluating…" else "Run this cell to see its result here." }
                detail = if (cell.modified) "This cell changed since run [${cell.sequence}]" else cell.stale
                failed = cell.status == "ERROR"; stale = cell.modified || cell.stale.isNotBlank()
            }
            entry.inlay.update()
        }
    }
    private fun remove(id: String) { entries.remove(id)?.let { it.inlay.dispose(); if (it.gutter.isValid) editor.markupModel.removeHighlighter(it.gutter) } }
    override fun dispose() { disposed = true; entries.keys.toList().forEach(::remove) }
}
