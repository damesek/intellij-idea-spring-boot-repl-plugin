package hu.baader.repl.ui

import com.intellij.openapi.ide.CopyPasteManager
import hu.baader.repl.protocol.ValueTree
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import java.awt.datatransfer.StringSelection
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel

/** Shared automatic Tree / Formatted / Raw view for eval results and the current Inspector value. */
class StructuredValuePanel : JPanel(BorderLayout()) {
    private val tree = JTree(DefaultMutableTreeNode("No value yet"))
    private val formatted = textArea()
    private val raw = textArea()
    private val tabs = JTabbedPane()
    private val note = JLabel("No value yet")
    init {
        tree.isRootVisible = true; tree.showsRootHandles = true
        tree.cellRenderer = object : DefaultTreeCellRenderer() {
            override fun getTreeCellRendererComponent(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, focus: Boolean): Component {
                super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focus)
                val node = (value as? DefaultMutableTreeNode)?.userObject as? ValueTree ?: return this
                val description = when (node.kind()) {
                    "OBJECT" -> "{ ${node.children().size} fields }"
                    "ARRAY" -> "[ ${node.children().size} elements ]"
                    "STRING" -> "\"${node.text().replace("\n", "\\n").replace("\r", "\\r").take(240)}\""
                    "REFERENCE" -> "↩ ${node.text().take(240)}"
                    else -> node.text().take(240)
                }
                text = "<html><b>${escape(node.label().take(160))}</b>: ${escape(description)} <i>${escape(node.type().take(200))}</i></html>"
                toolTipText = null
                return this
            }
        }
        val controls = WorkbookToolbar()
        controls.add(JButton("Expand").apply { addActionListener { var row = 0; while (row < tree.rowCount) tree.expandRow(row++) } })
        controls.add(JButton("Collapse").apply { addActionListener { for (row in tree.rowCount-1 downTo 1) tree.collapseRow(row) } })
        controls.add(JButton("Copy formatted").apply { addActionListener { CopyPasteManager.getInstance().setContents(StringSelection(formatted.text)) } })
        tabs.addTab("Tree", JScrollPane(tree)); tabs.addTab("Formatted", JScrollPane(formatted)); tabs.addTab("Raw", JScrollPane(raw))
        add(controls, BorderLayout.NORTH); add(tabs, BorderLayout.CENTER); add(note, BorderLayout.SOUTH)
    }
    fun showValue(message: Map<String, String>, fallback: String = "") {
        val display = ValueDisplay.fromMessage(message, fallback)
        fun node(value: ValueTree): DefaultMutableTreeNode = DefaultMutableTreeNode(value).apply { value.children().forEach { add(node(it)) } }
        tree.model = DefaultTreeModel(node(display.root)); tree.expandRow(0)
        formatted.text = display.formatted; raw.text = display.raw
        formatted.caretPosition = 0; raw.caretPosition = 0; note.text = display.note
        tabs.selectedIndex = if (display.root.kind() in setOf("OBJECT", "ARRAY")) 0 else 1
    }
    fun clear() { tree.model = DefaultTreeModel(DefaultMutableTreeNode("No current value")); formatted.text = ""; raw.text = ""; note.text = "No current value" }
    companion object {
        private fun textArea() = JTextArea().apply { isEditable = false; font = Font(Font.MONOSPACED, Font.PLAIN, 13); tabSize = 2 }
        private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }
}
