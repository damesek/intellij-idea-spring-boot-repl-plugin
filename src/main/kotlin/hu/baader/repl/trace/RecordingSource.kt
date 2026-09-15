package hu.baader.repl.trace

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.*
import com.intellij.openapi.editor.event.*
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.TypeConversionUtil
import com.intellij.testFramework.LightVirtualFile
import hu.baader.repl.editor.InlineResultRenderer
import hu.baader.repl.protocol.RecordedCall
import hu.baader.repl.ui.StructuredValuePanel
import java.awt.Dimension
import javax.swing.JTabbedPane
import javax.swing.SwingUtilities

object RecordingSource {
    fun find(project: Project, name: String) = JavaPsiFacade.getInstance(project).findClass(name.replace('$','.'), GlobalSearchScope.allScope(project))
    fun binaryName(type: PsiClass): String? = type.containingClass?.let { parent -> binaryName(parent)?.let { "$it\$${type.name ?: return null}" } } ?: type.qualifiedName
    fun descriptor(method: PsiMethod): String? {
        fun type(input: PsiType): String? = when (val t = TypeConversionUtil.erasure(input)) {
            is PsiArrayType -> type(t.componentType)?.let { "[$it" }
            is PsiPrimitiveType -> mapOf("byte" to "B", "char" to "C", "double" to "D", "float" to "F", "int" to "I", "long" to "J", "short" to "S", "boolean" to "Z", "void" to "V")[t.canonicalText]
            is PsiClassType -> t.resolve()?.let(::binaryName)?.let { "L${it.replace('.','/')};" }
            else -> null
        }
        val parameters = method.parameterList.parameters.map { type(it.type) ?: return null }.joinToString("")
        return "($parameters)${method.returnType?.let(::type) ?: return null}"
    }
    fun capture(project: Project, classes: List<String>): List<CapturedSource> {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        return ReadAction.compute<List<CapturedSource>, RuntimeException> {
            var total = 0
            classes.mapNotNull { name ->
                val type = find(project,name) ?: return@mapNotNull null
                val file = type.containingFile ?: return@mapNotNull null
                // A compiled library's .class mirror is not a captured Java source file.
                if (!file.name.matches(Regex("[\\w$.-]+\\.java"))) return@mapNotNull null
                val text = file.text
                if (text.length > CapturedSource.MAX_SOURCE || total + text.length > 4_000_000) return@mapNotNull null
                val methods=type.methods.mapNotNull { method ->
                    descriptor(method)?.let { "${method.name}$it" to method.textRange.startOffset }
                }.toMap()
                if (methods.size > 512) return@mapNotNull null
                total += text.length
                CapturedSource(name,file.name,text,CapturedSource.hash(text),methods)
            }
        }
    }
    fun values(call: RecordedCall) = JTabbedPane().apply {
        fun add(title: String, data: String, fallback: String) { addTab(title,StructuredValuePanel().apply { showValue(if (data.isEmpty()) emptyMap() else mapOf("view-data" to data),fallback) }) }
        add("Input at entry",call.input(),"Input was not captured")
        add("Result at exit",call.output(),if (call.status() == "RUNNING") "Still running" else if (call.status() == "ERROR") "The method threw an exception; see Exception." else if (call.status()=="INCOMPLETE") "Call completion was not captured." else if (call.descriptor().endsWith("V")) "void" else "Result was not captured")
        if (call.exception().isNotEmpty()) add("Exception",call.exception(),call.summary())
        preferredSize = Dimension(680,420)
    }
}

/** Source overlays are historical display data. Clicking them never evaluates Java or restores a stack frame. */
@Service(Service.Level.PROJECT)
class RecordedCallInlays(private val project: Project) : Disposable {
    private data class Entry(val inlay: Inlay<InlineResultRenderer>, val owner: Disposable)
    private val entries = linkedMapOf<Editor, MutableMap<String,Entry>>()
    private val files = linkedMapOf<String,LightVirtualFile>()
    init { EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
        override fun editorReleased(event: EditorFactoryEvent) { remove(event.editor) }
    },this) }
    fun show(call: RecordedCall, source: CapturedSource?, open: Boolean, graph: () -> Unit): String {
        if (source == null) return "Source was unavailable at recording start; recorded values remain available below."
        val offset = source.methods[call.method()+call.descriptor()] ?: return "Source overload could not be matched; use recorded values below."
        val openEditors = EditorFactory.getInstance().allEditors.filter { it.project == project && !it.isDisposed && it.document.text == source.text }
        if (!open) { openEditors.forEach { put(it,offset,call,graph) }; return "" }
        val original = ReadAction.compute<com.intellij.openapi.vfs.VirtualFile?,RuntimeException> {
            RecordingSource.find(project,call.className())?.containingFile?.takeIf { psi ->
                (psi.virtualFile?.let { FileDocumentManager.getInstance().getCachedDocument(it)?.text } ?: psi.text) == source.text
            }?.virtualFile
        }
        val recorded = original == null
        val file = original ?: files.getOrPut(call.recording()+source.sha256) {
            while (files.size >= 16) files.remove(files.keys.first())
            LightVirtualFile("Recorded-${call.recording().take(8)}-${source.fileName}",com.intellij.ide.highlighter.JavaFileType.INSTANCE,source.text).apply { isWritable = false }
        }
        val editors = FileEditorManager.getInstance(project).openFile(file,false).filterIsInstance<TextEditor>().map { it.editor }
        editors.forEach { editor ->
            editor.caretModel.moveToOffset(offset.coerceIn(0,editor.document.textLength))
            editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
            put(editor,offset,call,graph)
        }
        return if (recorded) "Recorded source opened read-only; current source differs or is unavailable." else "Input and result captured during this call. Source saved at recording start."
    }
    private fun put(editor: Editor, offset: Int, call: RecordedCall, graph: () -> Unit) {
        if (editor.isDisposed) return
        val key = call.className()+call.method()+call.descriptor()
        val values = entries.getOrPut(editor) { linkedMapOf() }
        values.remove(key)?.let { it.inlay.dispose(); Disposer.dispose(it.owner) }
        while (values.size >= 64) values.remove(values.keys.first())?.let { it.inlay.dispose(); Disposer.dispose(it.owner) }
        val owner = Disposer.newDisposable("Recorded method result"); Disposer.register(this,owner)
        val stamp = editor.document.modificationStamp
        fun popup() { val content=RecordingSource.values(call)
            JBPopupFactory.getInstance().createComponentPopupBuilder(content,content).setTitle("Recorded call #${call.id()} · ${call.method()}${call.descriptor()}")
                .setResizable(true).setMovable(true).setRequestFocus(true).createPopup().showInBestPositionFor(editor)
        }
        val renderer = InlineResultRenderer("CALL #${call.id()} · ${call.status()} · ${"%.2f".format(java.util.Locale.ROOT,call.durationNanos()/1_000_000.0)} ms",
            call.summary(),"Recorded input / result · thread ${call.threadId()}",call.status()=="ERROR",false,
            listOf(InlineResultRenderer.Link("Input / result",run=::popup),InlineResultRenderer.Link("Call graph",run=graph)))
        val inlay = editor.inlayModel.addBlockElement(offset.coerceIn(0,editor.document.textLength),true,true,1,renderer)
        if (inlay == null) { Disposer.dispose(owner); return }
        values[key] = Entry(inlay,owner)
        fun refresh() { if (inlay.isValid) {
            renderer.stale = editor.document.modificationStamp != stamp
            renderer.detail = if (renderer.stale) "Source changed · these values belong to recorded call #${call.id()}" else "Recorded input / result · thread ${call.threadId()} · preview limits apply"
            inlay.update()
        } }
        editor.document.addDocumentListener(object : DocumentListener { override fun documentChanged(event: DocumentEvent) = refresh() },owner)
        editor.scrollingModel.addVisibleAreaListener({ refresh() },owner)
        editor.addEditorMouseListener(object : EditorMouseListener {
            override fun mouseClicked(e: EditorMouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e.mouseEvent) || (e.inlay ?: editor.inlayModel.getElementAt(e.mouseEvent.point)) !== inlay) return
                val bounds = inlay.bounds ?: return
                if (renderer.click(e.mouseEvent.x-bounds.x,e.mouseEvent.y-bounds.y)) e.consume()
            }
        },owner)
        refresh()
    }
    private fun remove(editor: Editor) { entries.remove(editor)?.values?.forEach { it.inlay.dispose(); Disposer.dispose(it.owner) } }
    fun clear() { entries.keys.toList().forEach(::remove) }
    override fun dispose() { clear(); files.clear() }
    companion object { fun get(project: Project): RecordedCallInlays = project.service() }
}
