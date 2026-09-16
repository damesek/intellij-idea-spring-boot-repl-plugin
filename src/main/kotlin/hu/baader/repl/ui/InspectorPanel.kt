package hu.baader.repl.ui

import com.intellij.openapi.Disposable
import hu.baader.repl.nrepl.NreplService
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.table.DefaultTableModel

class InspectorPanel(private val service: NreplService, private val changed: () -> Unit) : JPanel(BorderLayout()), Disposable {
    private val model = object : DefaultTableModel(arrayOf("Field / index", "Type", "Preview", "Access"), 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val table = JTable(model)
    private val breadcrumb = JLabel("Open a result, variable or event")
    private val summary = JTextArea(3, 40).apply { isEditable = false; lineWrap = true }
    private val variable = JTextField("inspected", 12)
    private val type = JTextField(24)
    private val snapshot = JTextField("inspected-data", 16)
    private val next = JButton("Next")
    private val structured = StructuredValuePanel()
    private var offset = 0
    private var revision = ""
    private var generation = 0
    private var disposed = false
    var bookmarks: (() -> MutableList<hu.baader.repl.workspace.WorkspaceDocument.Bookmark>)? = null
    var bookmarksChanged: (() -> Unit)? = null
    private var rootVariable = ""
    private var bookmarkPath = ""
    private val listener = service.onMessage {
        if (it["op"] == "session/reset" || it["op"] == "connection" && it["state"] in setOf("DISCONNECTED", "CONNECTING", "FAILED")) clear()
    }
    init {
        val navigation = WorkbookToolbar()
        fun button(label: String, action: () -> Unit) { navigation.add(JButton(label).apply { addActionListener { action() } }) }
        button("Open selected", ::openSelected)
        button("Back") { navigate("inspector/back") }
        button("Refresh") { navigate("inspector/page", mapOf("offset" to offset.toString())) }
        button("Bookmark") {
            val saved = bookmarks?.invoke() ?: return@button
            if (rootVariable.isBlank()) { error("Bookmarks need a named root variable; bind this value and open it from Variables first"); return@button }
            if (saved.size >= 100) { error("Bookmark limit reached (100)"); return@button }
            val label = com.intellij.openapi.ui.Messages.showInputDialog("Bookmark name", "Inspector Bookmark", null, breadcrumb.text, null) ?: return@button
            saved += hu.baader.repl.workspace.WorkspaceDocument.Bookmark(label.take(256), rootVariable, bookmarkPath)
            bookmarksChanged?.invoke()
        }
        button("Open bookmark") {
            val saved = bookmarks?.invoke().orEmpty(); if (saved.isEmpty()) return@button
            val labels = saved.map { it.label }.toTypedArray()
            val selected = com.intellij.openapi.ui.Messages.showChooseDialog("Requires the saved root variable in this session", "Inspector Bookmarks", labels, labels[0], null)
            if (selected >= 0) { val b = saved[selected]; rootVariable = b.variable; navigate("inspector/restore-path", mapOf("var" to b.variable, "path" to b.path)) }
        }
        button("Remove bookmark") {
            val saved = bookmarks?.invoke() ?: return@button; if (saved.isEmpty()) return@button
            val labels = saved.map { it.label }.toTypedArray()
            val selected = com.intellij.openapi.ui.Messages.showChooseDialog("Remove a saved bookmark", "Inspector Bookmarks", labels, labels[0], null)
            if (selected >= 0) { saved.removeAt(selected); bookmarksChanged?.invoke() }
        }
        button("Previous") { navigate("inspector/page", mapOf("offset" to (offset - 50).coerceAtLeast(0).toString())) }
        next.addActionListener { navigate("inspector/page", mapOf("offset" to (offset + 50).toString())) }; navigation.add(next)
        next.isEnabled = false
        val actions = WorkbookToolbar()
        actions.add(JLabel("Variable:")); actions.add(variable); actions.add(JLabel("Optional Java type:")); actions.add(type)
        actions.add(JButton("Bind current").apply { addActionListener {
            service.request("inspector/bind", mapOf("var" to variable.text.trim(), "type" to type.text.trim()), { breadcrumb.text = it["value"]; changed() }, ::error)
        } })
        actions.add(JLabel("Snapshot:")); actions.add(snapshot)
        actions.add(JButton("Edit DATA copy…").apply { addActionListener {
            val name=snapshot.text.trim()+"-original-"+java.util.UUID.randomUUID().toString().take(8)
            service.request("snapshot/save",mapOf("inspected" to "true","name" to name,"type" to type.text.trim()),{
                changed();DataCopyEditor.open(service,name,changed,::error)
            },::error)
        } })
        for ((label, op) in listOf("Freeze DATA" to "snapshot/save", "Pin LIVE" to "snapshot/pin")) actions.add(JButton(label).apply { addActionListener {
            service.request(op, mapOf("inspected" to "true", "name" to snapshot.text.trim(), "type" to type.text.trim()), { breadcrumb.text = it["value"]; changed() }, ::error)
        } })
        table.addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2) openSelected() } })
        add(JPanel(BorderLayout()).apply { add(navigation, BorderLayout.NORTH); add(breadcrumb, BorderLayout.CENTER); add(actions, BorderLayout.SOUTH) }, BorderLayout.NORTH)
        add(JTabbedPane().apply { addTab("Value", structured); addTab("Fields", JScrollPane(table)) }, BorderLayout.CENTER)
        add(JScrollPane(summary), BorderLayout.SOUTH)
    }
    fun open(extra: Map<String, String>) { rootVariable = extra["var"].orEmpty(); navigate("inspector/start", extra) }
    private fun openSelected() {
        if (table.selectedRow < 0) return
        val row = table.convertRowIndexToModel(table.selectedRow)
        if (model.getValueAt(row, 3).toString().isNotEmpty()) { error(model.getValueAt(row, 3).toString()); return }
        navigate("inspector/push", mapOf("revision" to revision, "index" to row.toString()))
    }
    private fun navigate(op: String, extra: Map<String, String> = emptyMap()) {
        val request = ++generation
        service.request(op, extra, {
            if (disposed || request != generation) return@request
            revision = it["revision"].orEmpty(); offset = it["offset"]?.toIntOrNull() ?: 0
            model.rowCount = 0
            for (line in it["value"].orEmpty().lines().filter(String::isNotBlank)) {
                val fields = line.split('\t')
                if (fields.size >= 5) model.addRow(arrayOf(fields[1], fields[2], fields[3], fields[4]))
            }
            breadcrumb.text = it["path"]
            bookmarkPath = it["bookmark-path"].orEmpty()
            structured.showValue(it, it["preview"].orEmpty())
            summary.text = it["type"].orEmpty() + "\n" + it["preview"].orEmpty() + "\nLive object; getters are not invoked." +
                (if (it["scan-limited"] == "true") " First 10000 children only." else "") +
                it.entries.filter { entry -> entry.key.startsWith("hibernate-") }.joinToString("",prefix="") { entry -> "\n${entry.key.removePrefix("hibernate-")}: ${entry.value}" }
            next.isEnabled = it["has-more"] == "true" && offset < 9950
        }, { if (!disposed && request == generation) error(it) })
    }
    private fun error(message: String) { breadcrumb.text = message }
    private fun clear() { generation++; revision = ""; model.rowCount = 0; summary.text = ""; structured.clear(); breadcrumb.text = "Session changed; open a current value"; next.isEnabled = false }
    override fun dispose() { disposed = true; listener.dispose(); clear() }
}
