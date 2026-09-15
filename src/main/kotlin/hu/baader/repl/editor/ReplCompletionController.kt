package hu.baader.repl.editor

import com.intellij.codeInsight.lookup.*
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import hu.baader.repl.nrepl.NreplService
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.KeyStroke
import javax.swing.Timer

/** One asynchronous request in flight; no network/compiler work under the IDE read lock or EDT. */
class ReplCompletionController(
    private val project: Project, private val editor: Editor, private val service: NreplService,
    private val canComplete: () -> Boolean, private val feedback: (String) -> Unit
) : Disposable {
    private var disposed = false
    private var generation = 0L
    private var sequence = 0L
    private var active: Long? = null
    private var pending = false
    private var ownedLookup: LookupEx? = null
    private val timer = Timer(300) { request(false) }.apply { isRepeats = false }
    init {
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (event.newLength <= 4 && !disposed) timer.restart()
            }
        }, this)
        Disposer.register(this, service.onMessage {
            if (it["op"] in setOf("connection", "session/reset", "eval", "java-eval", "snapshot/load", "vars/drop", "imports/add", "class-reload", "inspector/bind", "debug/claim")) invalidate()
        })
        object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) = request(true)
        }.registerCustomShortcutSet(CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK)), editor.contentComponent, this)
    }
    fun invalidate() {
        generation++; active = null; pending = false; timer.stop()
        if (!editor.isDisposed && ownedLookup != null && LookupManager.getActiveLookup(editor) === ownedLookup) ownedLookup?.hideLookup(true)
        ownedLookup = null
    }
    fun request(explicit: Boolean = true) {
        if (disposed || editor.isDisposed || project.isDisposed) return
        if (!service.isConnected() || !canComplete()) { if (explicit) feedback("Completion is available when the REPL is connected and idle."); return }
        if (active != null) { pending = true; return }
        if (!explicit && (!editor.contentComponent.isFocusOwner || !editor.contentComponent.isShowing)) return
        val text = editor.document.text
        val caret = editor.caretModel.offset
        if (!explicit && (caret == 0 || !(text[caret - 1] == '.' || Character.isJavaIdentifierPart(text[caret - 1])))) return
        val query = CompletionRequest.capture(text, caret, editor.document.modificationStamp, generation) ?: return
        val ticket = ++sequence; active = ticket
        if (explicit) editor.contentComponent.requestFocusInWindow()
        fun finish(reply: Map<String, String>?, error: String?) {
            if (disposed || active != ticket) return
            active = null
            if (!editor.isDisposed && query.matches(editor.document.modificationStamp, editor.caretModel.offset, generation) && canComplete()) {
                if (error != null) { if (explicit) feedback(error) }
                else if (editor.contentComponent.isShowing && editor.contentComponent.isFocusOwner) show(query, reply?.get("completions").orEmpty(), explicit)
            }
            if (pending) { pending = false; timer.restart() }
        }
        service.request("complete", mapOf("code" to query.source, "cursor" to query.cursor.toString()), { finish(it, null) }, { finish(null, it) })
    }
    private fun show(query: CompletionRequest, reply: String, explicit: Boolean) {
        val choices = query.choices(reply)
        if (choices.isEmpty()) { if (explicit) feedback("No session completions. Run declaration cells first."); return }
        val anchor = choices.first().anchor
        val items = choices.filter { it.anchor == anchor }.map {
            LookupElementBuilder.create(it.text).withTypeText("REPL session", true).withAutoCompletionPolicy(AutoCompletionPolicy.NEVER_AUTOCOMPLETE)
        }.toTypedArray()
        if (!editor.isDisposed && ownedLookup != null && LookupManager.getActiveLookup(editor) === ownedLookup) ownedLookup?.hideLookup(true)
        val prefix = editor.document.getText(com.intellij.openapi.util.TextRange(anchor, query.base + query.cursor))
        val lookup = LookupManager.getInstance(project).showLookup(editor, items, prefix) ?: return
        ownedLookup = lookup
        lookup.addLookupListener(object : LookupListener {
            override fun beforeItemSelected(event: LookupEvent) = !disposed && canComplete() && service.isConnected() &&
                query.matches(editor.document.modificationStamp, editor.caretModel.offset, generation)
        })
    }
    override fun dispose() { disposed = true; invalidate() }
}
