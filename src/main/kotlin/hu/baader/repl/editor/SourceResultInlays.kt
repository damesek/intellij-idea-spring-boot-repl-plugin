package hu.baader.repl.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import hu.baader.repl.actions.WorkbenchActions
import hu.baader.repl.nrepl.NreplService
import hu.baader.repl.ui.SnapshotTargetDialog
import hu.baader.repl.ui.StructuredValuePanel
import javax.swing.SwingUtilities

/** CIDER-style feedback stays at the evaluation site; closing editors releases their references. */
@Service(Service.Level.PROJECT)
class SourceResultInlays(private val project: Project) : Disposable {
    private data class Result(val inlay: Inlay<InlineResultRenderer>, val owner: Disposable)
    private val entries = linkedMapOf<Editor, Result>()
    private val service get() = NreplService.getInstance(project)
    init {
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorReleased(event: EditorFactoryEvent) { remove(event.editor) }
        }, this)
    }
    fun show(editor: Editor, offset: Int, sourceStamp: Long, target: Pair<String,Long>?, response: Map<String,String>) {
        if (editor.isDisposed || project.isDisposed) return
        remove(editor)
        while (entries.size >= 10) remove(entries.keys.first())
        val owner = Disposer.newDisposable("REPL source result"); Disposer.register(this, owner)
        val reference = response["handle"]
        val output = listOfNotNull(response["out"], response["stderr"], response["values"] ?: response["value"], response["err"]).joinToString("\n").take(100000)
        val preview = response.filterKeys { it in setOf("view-data", "view-kind", "view-truncated", "value", "values", "err") }
            .filterValues { it.length <= 128000 }
        var expired = false
        fun live() = !expired && reference != null && target != null && target == service.debuggerTarget() && service.isConnected()
        fun detail() {
            val panel = StructuredValuePanel().apply { showValue(preview, output); preferredSize = java.awt.Dimension(650, 360) }
            JBPopupFactory.getInstance().createComponentPopupBuilder(panel, panel).setTitle("REPL result · ${response["execution-duration-ms"].orEmpty()} ms")
                .setMovable(true).setResizable(true).setRequestFocus(true).createPopup().showInBestPositionFor(editor)
        }
        val renderer = InlineResultRenderer("REPL · ${if (response["err"].isNullOrEmpty()) "result" else "error"} · ${response["execution-duration-ms"].orEmpty()} ms",
            output, "Evaluated in the REPL session", !response["err"].isNullOrEmpty(), false,
            listOf(
                InlineResultRenderer.Link("Inspect", ::live) { ToolWindowManager.getInstance(project).getToolWindow("Spring Boot REPL")?.show {
                    if (live()) WorkbenchActions.get(project).inspect(mapOf("handle" to reference!!))
                } },
                InlineResultRenderer.Link("Snapshot", ::live) { SnapshotTargetDialog.save(project, service, reference!!, "result",
                    "Save this evaluation's live result. The source expression is not run again.", {}, { message ->
                        com.intellij.notification.NotificationGroupManager.getInstance().getNotificationGroup("Spring Boot REPL")
                            .createNotification(message, com.intellij.notification.NotificationType.INFORMATION).notify(project)
                    }) },
                InlineResultRenderer.Link("Output", run = ::detail)
            ))
        val inlay = editor.inlayModel.addBlockElement(offset.coerceIn(0, editor.document.textLength), true, false, 0, renderer)
        if (inlay == null) { Disposer.dispose(owner); return }
        entries[editor] = Result(inlay, owner)
        fun refresh() {
            if (!inlay.isValid || editor.isDisposed) return
            renderer.stale = sourceStamp != editor.document.modificationStamp || target != service.debuggerTarget() || expired
            renderer.detail = when {
                expired || target != service.debuggerTarget() -> "Previous session result · live reference unavailable"
                sourceStamp != editor.document.modificationStamp -> "Source changed since this evaluation"
                else -> "Evaluated in the REPL session · method locals require Debugger transfer"
            }
            inlay.update()
        }
        editor.document.addDocumentListener(object : DocumentListener { override fun documentChanged(event: DocumentEvent) = refresh() }, owner)
        editor.scrollingModel.addVisibleAreaListener({ refresh() }, owner)
        Disposer.register(owner, service.onMessage { if (it["op"] == "session/reset") expired = true; if (it["op"] in setOf("connection", "session/reset")) refresh() })
        editor.addEditorMouseListener(object : EditorMouseListener {
            override fun mouseClicked(e: EditorMouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e.mouseEvent) || (e.inlay ?: editor.inlayModel.getElementAt(e.mouseEvent.point)) !== inlay) return
                val bounds = inlay.bounds ?: return
                if (renderer.click(e.mouseEvent.x - bounds.x, e.mouseEvent.y - bounds.y)) e.consume()
            }
        }, owner)
        refresh()
    }
    private fun remove(editor: Editor) { entries.remove(editor)?.let { it.inlay.dispose(); Disposer.dispose(it.owner) } }
    override fun dispose() { entries.keys.toList().forEach(::remove) }
    companion object { fun get(project: Project): SourceResultInlays = project.service() }
}
