package hu.baader.repl.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import hu.baader.repl.history.ReplHistoryService
import hu.baader.repl.nrepl.NreplService
import hu.baader.repl.ui.NotebookPanel
import hu.baader.repl.workspace.WorkspaceStore
import hu.baader.repl.workspace.WorkspaceDocument
import java.util.UUID

class NotebookController(
    private val project: Project, private val editor: Editor, private val service: NreplService,
    private val store: WorkspaceStore, private val status: (String) -> Unit, private val busyChanged: (Boolean) -> Unit
) : Disposable {
    val state get() = store.document.notebook
    private var busy = false
    private var generation = 0
    private var disposed = false
    private val owned = mutableSetOf<String>()
    private val responses = mutableMapOf<String, Map<String,String>>()
    private var halted = false
    private var restarting = false
    private val changes = mutableListOf<() -> Unit>()
    val results = CellResults()
    var suspended = false
    val panel = NotebookPanel({ state }) { index ->
        ReplCells.all(editor.document.text).getOrNull(index)?.let { editor.caretModel.moveToOffset(it.start); editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.MAKE_VISIBLE); editor.contentComponent.requestFocusInWindow() }
    }
    init {
        if (state.text.isEmpty() && state.cells.isEmpty() && state.counter == 0L) state.sync(editor.document.text)
        state.sync(state.text)
        WriteCommandAction.runWriteCommandAction(project) { editor.document.setText(state.text) }
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                generation++
                runCatching { state.sync(editor.document.text); changed() }.onFailure { status(it.message.orEmpty()) }
            }
        }, this)
        Disposer.register(this, service.onMessage { m ->
            val op = m["op"]
            m["id"]?.takeIf { it in owned }?.let { responses[it] = m }
            when {
                op == "connection" && (m["state"] in setOf("CONNECTING", "DISCONNECTED", "FAILED") || m["detail"].orEmpty().contains("context changed", true)) -> {
                    generation++; halted = true; owned.clear(); responses.clear(); results.clear(); state.invalidate("Connection or Spring context changed"); setBusy(false); changed()
                }
                op == "session/reset" && m["err"].isNullOrEmpty() -> { if (!restarting) { generation++; halted = true }; results.clear(); state.invalidate("Session was reset"); changed() }
                op in setOf("eval", "java-eval") && m["id"] !in owned -> { halted = true; state.invalidate("Code ran outside the workbook"); changed() }
                op in setOf("vars/drop", "imports/add", "bind-spring", "inspector/bind", "debug/claim", "class-reload") && m["err"].isNullOrEmpty() -> {
                    halted = true; state.invalidate("Session state changed outside cell execution"); changed()
                }
                op == "snapshot/load" && m["err"].isNullOrEmpty() -> {
                    val variable = m["var"].orEmpty(); val name = m["snapshot"].orEmpty()
                    if (variable.isNotBlank() && name.isNotBlank()) {
                        store.document.bindings.removeIf { it.variable == variable }
                        store.document.bindings += WorkspaceDocument.Binding(variable, name, m["snapshot-version"].orEmpty(), m["type"].orEmpty(), m["restorable"] == "true")
                    }
                    state.invalidate("A DATA/LIVE variable was loaded"); changed()
                }
            }
            m["imports"]?.let { store.document.imports = it.lines().filter(String::isNotBlank).toMutableList() }
        })
        panel.refresh()
    }
    private fun setBusy(value: Boolean) { busy = value; busyChanged(value) }
    fun synchronizeSource() { state.sync(editor.document.text) }
    fun onChanged(action: () -> Unit): Disposable { changes += action; return Disposable { changes -= action } }
    fun changed() { results.retain(state.cells.map { it.id }.toSet()); panel.refresh(); changes.toList().forEach { it() }; store.checkpoint(); if (store.lastError.isNotBlank()) status(store.lastError) }
    fun runCell(id: String) {
        val index = state.cells.indexOfFirst { it.id == id }; if (index < 0) return
        val part = ReplCells.all(editor.document.text).getOrNull(index) ?: return
        editor.selectionModel.removeSelection(); editor.caretModel.moveToOffset(part.start); run()
    }
    fun stopQueue() { halted = true; status("Batch stopped; the active cell can be interrupted separately") }
    fun run(mode: String = "current", advance: Boolean = false) {
        if (busy || disposed || suspended) return
        if (!service.isConnected()) { status("Connect an application first"); return }
        if (!service.canExecute()) { status("Wait for confirmed execution settings, or refresh them in Session"); return }
        try { state.sync(editor.document.text) } catch (e: Exception) { status(e.message.orEmpty()); return }
        halted = false
        val source = editor.document.text; val caret = editor.caretModel.offset; val index = state.indexAt(caret)
        val selected = editor.selectionModel.selectedText
        if (mode == "current" && !selected.isNullOrBlank()) {
            state.invalidate("Only a selection was executed; full cell state is unknown")
            execute(selected, null, { setBusy(false); changed() }); return
        }
        val gen = generation
        setBusy(true)
        fun start() {
            if (disposed || gen != generation || halted) { setBusy(false); return }
            val ids = when(mode) {
                "above" -> state.cells.take(index).map { it.id }
                "from" -> state.cells.drop(index).map { it.id }
                "affected" -> state.affected(state.cells[index].id)
                "all", "restart" -> state.cells.map { it.id }
                else -> listOf(state.cells[index].id)
            }.filter { state.cell(it)?.source?.isNotBlank() == true }
            fun next(position: Int) {
                if (disposed || gen != generation || halted || position >= ids.size) {
                    setBusy(false)
                    if (!disposed && gen == generation && !halted && advance && editor.caretModel.offset == caret) {
                        ReplCells.at(source, caret).next?.let { editor.caretModel.moveToOffset(it); editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.MAKE_VISIBLE) }
                    }
                    return
                }
                val id = ids[position]; val code = state.cell(id)?.source ?: return
                execute(code, id) { ok -> if (ok) next(position + 1) else setBusy(false) }
            }
            next(0)
        }
        if (mode == "restart") {
            restarting = true
            service.resetSession({ restarting = false; analyzeAll(::start) }, { restarting = false; status(it); setBusy(false) })
        }
        else if (mode == "affected") analyzeAll(::start)
        else start()
    }
    fun analyzeDependencies() { if (!busy && !suspended) { halted = false; setBusy(true); analyzeAll { setBusy(false) } } }
    private fun analyzeAll(done: () -> Unit = {}) {
        if (disposed || !service.isConnected()) { status("Connect to analyze declarations"); setBusy(false); return }
        val gen = generation; val cells = state.cells.map { it.id to it.source }
        fun next(i: Int) {
            if (disposed || gen != generation || halted) { setBusy(false); return }
            if (i >= cells.size) { changed(); done(); return }
            val (id, code) = cells[i]
            service.request("notebook/symbols", mapOf("code" to code), { response ->
                state.analyze(id, code, response["declared-symbols"].orEmpty().lines().filter(String::isNotBlank), response["conservative"] != "false")
                next(i + 1)
            }, { status(it); setBusy(false) })
        }
        next(0)
    }
    private fun execute(code: String, cellId: String?, done: (Boolean) -> Unit) {
        setBusy(true)
        val id = UUID.randomUUID().toString(); owned += id
        var sequence: Long? = null; var started = System.nanoTime(); val gen = generation
        fun finish(response: Map<String,String>, failure: Boolean) {
            owned -= id
            val completeResponse = (responses.remove(id) ?: emptyMap()) + response
            if (disposed) return
            if (cellId != null && sequence != null) state.finish(cellId, sequence!!, code,
                listOfNotNull(completeResponse["out"], completeResponse["stderr"], completeResponse["values"] ?: completeResponse["value"], completeResponse["err"]).joinToString("\n"), failure, completeResponse["execution-duration-ms"]?.toLongOrNull() ?: ((System.nanoTime() - started) / 1_000_000))
            if (cellId != null && sequence != null && gen == generation && state.cell(cellId)?.status in setOf("SUCCESS", "ERROR"))
                results.record(cellId, sequence!!, completeResponse)
            if (failure) status(response["err"].orEmpty()) else status(service.state.name)
            changed(); done(!failure)
        }
        fun dispatch() {
            if (disposed || gen != generation || halted) { owned -= id; done(false); return }
            sequence = cellId?.let { state.begin(it) }; started = System.nanoTime()
            cellId?.let { results.remove(it) }
            status("Queued / running"); changed(); ReplHistoryService.getInstance(project).add(code)
            service.eval(code, { finish(it, false) }, { finish(mapOf("err" to it), true) }, id)
        }
        if (cellId == null) dispatch() else service.request("notebook/symbols", mapOf("code" to code), { response ->
            state.analyze(cellId, code, response["declared-symbols"].orEmpty().lines().filter(String::isNotBlank), response["conservative"] != "false")
            dispatch()
        }, { finish(mapOf("err" to it), true) })
    }
    fun replace(document: WorkspaceDocument) {
        check(!busy) { "Wait for the active cell before opening another workspace" }
        generation++; results.clear(); document.notebook.restored(); store.document = document
        WriteCommandAction.runWriteCommandAction(project) { editor.document.setText(document.notebook.text); editor.caretModel.moveToOffset(0) }
        changed()
    }
    override fun dispose() { disposed = true; generation++; halted = true }
}
