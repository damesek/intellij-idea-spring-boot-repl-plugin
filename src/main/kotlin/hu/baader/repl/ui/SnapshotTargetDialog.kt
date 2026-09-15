package hu.baader.repl.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import hu.baader.repl.nrepl.NreplService
import java.awt.BorderLayout
import javax.swing.*

object SnapshotDestination {
    fun error(name: String, type: String): String? = when {
        name.isBlank() || name.length > 128 || name.contains('/') || name.contains('\\') || name.contains("..") || name.any(Char::isISOControl) -> "Enter a snapshot name of 1–128 characters, without paths or control characters."
        type.length > 1024 || (type.isNotBlank() && !type.matches(Regex("[\\w.$<>?, \\[\\];]+"))) -> "Enter a Java type, or leave the type empty."
        else -> null
    }
}

/** Chooses a destination; the expression that produced the handle is never evaluated again. */
class SnapshotTargetDialog(project: Project, private val service: NreplService, suggested: String, description: String) : DialogWrapper(project) {
    private val nameBox = JComboBox(arrayOf(suggested)).apply { isEditable = true; selectedItem = suggested }
    private val type = JTextField()
    private val descriptionLabel = JTextArea(description, 2, 44).apply { isEditable = false; lineWrap = true; wrapStyleWord = true; isOpaque = false }
    private var closed = false
    val snapshotName get() = nameBox.editor.item?.toString().orEmpty().trim()
    val declaredType get() = type.text.trim()
    init {
        title = "Save DATA Snapshot"; setOKButtonText("Save snapshot"); init()
        service.snapshotListSimple({ rows ->
            if (!closed) {
                val entered = nameBox.editor.item
                rows.take(1000).map { it.substringBefore('\t') }.distinct().filter { it != suggested }.forEach(nameBox::addItem)
                nameBox.editor.item = entered
            }
        })
    }
    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, 8)).apply {
        add(descriptionLabel, BorderLayout.NORTH)
        add(JPanel(java.awt.GridLayout(0, 1, 0, 6)).apply {
            add(JLabel("Snapshot name · an existing name creates a new version").apply { labelFor = nameBox }); add(nameBox)
            add(JLabel("Declared Java type (optional)").apply { labelFor = type }); add(type)
        }, BorderLayout.CENTER)
    }
    override fun getPreferredFocusedComponent(): JComponent = nameBox
    override fun doValidate(): ValidationInfo? = SnapshotDestination.error(snapshotName, declaredType)?.let { ValidationInfo(it, nameBox) }
    override fun dispose() { closed = true; super.dispose() }
    companion object {
        fun save(project: Project, service: NreplService, handle: String, suggested: String, description: String,
                 saved: () -> Unit, report: (String) -> Unit) {
            val target = service.debuggerTarget() ?: return
            val dialog = SnapshotTargetDialog(project, service, suggested, description)
            if (!dialog.showAndGet()) return
            if (target != service.debuggerTarget()) { report("The REPL session changed. Evaluate or select a result in the current session first."); return }
            service.request("snapshot/save", mapOf("name" to dialog.snapshotName, "handle" to handle, "type" to dialog.declaredType),
                { report("Snapshot saved: ${dialog.snapshotName}"); saved() }, report)
        }
    }
}
