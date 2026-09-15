package hu.baader.repl.ui

import com.intellij.ui.components.JBScrollPane
import hu.baader.repl.nrepl.NreplService
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.*

/** Runtime-confirmed variables, with bounded inspection and explicit paging. */
class LoadedVariablesPanel(
    private val connection: () -> NreplService?,
    private val insertSnippet: (String) -> Unit,
    private val openInspector: ((Map<String,String>) -> Unit)? = null
) : JPanel(BorderLayout()) {
    private val model = DefaultListModel<String>()
    private val list = JList(model)
    private val details = JTextArea().apply { isEditable = false; lineWrap = true }
    private var offset = 0
    init {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT))
        fun button(text: String, action: () -> Unit) { toolbar.add(JButton(text).apply { addActionListener { action() } }) }
        button("Refresh", ::refreshVariables)
        button("Insert") { selected()?.let(insertSnippet) }
        button("Inspect") { offset = 0; inspect() }
        button("Previous") { offset = (offset - 50).coerceAtLeast(0); inspect() }
        button("Next") { offset += 50; inspect() }
        button("Drop") { selected()?.let { name -> connection()?.request("vars/drop", mapOf("name" to name), { refreshVariables() }, { details.text = it }) } }
        add(toolbar, BorderLayout.NORTH)
        add(JSplitPane(JSplitPane.VERTICAL_SPLIT, JBScrollPane(list), JBScrollPane(details)).apply { resizeWeight = 0.5 }, BorderLayout.CENTER)
    }
    private fun selected() = list.selectedValue?.substringBefore('\t')
    private fun inspect() {
        selected()?.let { name ->
            if (openInspector != null) { openInspector.invoke(mapOf("var" to name)); return }
            connection()?.request("inspect", mapOf("var" to name, "offset" to offset.toString()), { details.text = it["value"] }, { details.text = it })
        }
    }
    fun refreshVariables() {
        val service = connection() ?: return
        if (!service.isConnected()) { clear(); return }
        service.request("vars/list", onResult = {
            model.clear()
            it["value"].orEmpty().lines().filter(String::isNotBlank).forEach(model::addElement)
        }, onError = { details.text = it })
    }
    fun clear() { model.clear(); details.text = "" }
}
