package hu.baader.repl.ui

import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBScrollPane
import hu.baader.repl.nrepl.NreplService
import java.awt.BorderLayout
import javax.swing.*

/** All modes use the runtime snapshot API; saving never evaluates an expression. */
class SimplifiedSnapshotsPanel(
    private val connection: () -> NreplService?,
    private val insertSnippet: (String) -> Unit,
    private val onVariableLoaded: ((String, Any?) -> Unit)? = null
) : JPanel(BorderLayout()), com.intellij.openapi.Disposable {
    private data class Row(val name: String, val type: String, val kind: String) {
        override fun toString() = name + "  [" + kind + "] " + type
    }
    private val model = DefaultListModel<Row>()
    private val list = JList(model)
    private val status = JLabel("LIVE lasts for this session. DATA persists. RECIPE loads code into the editor.")
    init {
        val pages = JTabbedPane()
        val versions = SnapshotVersionsPanel(connection, ::refresh)
        com.intellij.openapi.util.Disposer.register(this, versions)
        val compare = SnapshotDiffPanel(connection)
        com.intellij.openapi.util.Disposer.register(this, compare)
        val trigger = SnapshotTriggerPanel(connection, ::refresh)
        com.intellij.openapi.util.Disposer.register(this, trigger)
        val toolbar = JPanel(java.awt.GridLayout(0, 5, 4, 4))
        fun button(label: String, action: () -> Unit) { toolbar.add(JButton(label).apply { addActionListener { action() } }) }
        button("Refresh", ::refresh)
        button("Pin LIVE") { save("snapshot/pin") }
        button("Freeze DATA") { save("snapshot/save") }
        button("Load", ::load)
        button("Edit DATA copy") { list.selectedValue?.takeIf { it.kind=="DATA" }?.let { row -> connection()?.let { DataCopyEditor.open(it,row.name,::refresh,::error) } } }
        button("Info") { list.selectedValue?.let { row -> connection()?.snapshotInfo(row.name, { Messages.showInfoMessage(it, row.name) }, ::error) } }
        button("Versions") {
            list.selectedValue?.let { row ->
                if (row.kind == "LIVE") error("LIVE pins have no persistent history")
                else { versions.open(row.name); pages.selectedIndex = 3 }
            }
        }
        button("Paste JSON") {
            val name = Messages.showInputDialog("Snapshot name", "Import DATA", null) ?: return@button
            val json = Messages.showMultilineInputDialog(null, "JSON data (up to 2 MiB)", "Import DATA", "", null, null) ?: return@button
            connection()?.request("snapshot/import", mapOf("name" to name, "json" to json), { refresh() }, ::error)
        }
        button("Import file") {
            val service = connection() ?: return@button
            val chooser = JFileChooser().apply { dialogTitle = "Import DATA JSON (up to 200 MiB, local application)" }
            if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return@button
            val name = Messages.showInputDialog("Snapshot name", "Import DATA", null) ?: return@button
            status.text = "Importing " + chooser.selectedFile.name + "…"
            service.request("snapshot/import-file", mapOf("name" to name, "path" to chooser.selectedFile.absolutePath), {
                status.text = "Imported " + name; refresh()
            }, ::error)
        }
        button("Export file") {
            val row = list.selectedValue ?: return@button
            val service = connection() ?: return@button
            if (row.kind == "LIVE") { error("Freeze a LIVE value as DATA before exporting."); return@button }
            val chooser = JFileChooser().apply { selectedFile = java.io.File(row.name + ".json"); dialogTitle = "Export snapshot (local application)" }
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return@button
            val file = chooser.selectedFile
            if (file.exists() && Messages.showYesNoDialog("Replace " + file.name + "?", "Export Snapshot", null) != Messages.YES) return@button
            status.text = "Exporting " + file.name + "…"
            service.request("snapshot/export-file", mapOf("name" to row.name, "path" to file.absolutePath), {
                status.text = "Exported " + file.name
            }, ::error)
        }
        button("Compare") {
            val selected = list.selectedValuesList
            if (selected.size != 2 || selected.any { it.kind != "DATA" }) { error("Select two DATA snapshots to compare (Ctrl/Cmd-click)."); return@button }
            compare.compare(selected[0].name, selected[1].name)
            pages.selectedIndex = 2
        }
        button("Delete") {
            val row = list.selectedValue ?: return@button
            if (Messages.showYesNoDialog("Delete snapshot " + row.name + " and all its saved versions?", "Delete Snapshot", null) == Messages.YES)
                connection()?.deleteSnapshot(row.name, { refresh() }, ::error)
        }
        val saved = JPanel(BorderLayout()).apply {
            add(toolbar, BorderLayout.NORTH); add(JBScrollPane(list), BorderLayout.CENTER); add(status, BorderLayout.SOUTH)
        }
        pages.addTab("Saved", saved); pages.addTab("Capture next", trigger); pages.addTab("Compare", compare)
        pages.addTab("Versions", versions)
        add(pages, BorderLayout.CENTER)
    }
    private fun save(op: String) {
        val name = Messages.showInputDialog("Snapshot name", "Save Snapshot", null) ?: return
        val variable = Messages.showInputDialog("Existing Java variable (empty = last result). Expressions must be evaluated first.", "Save Snapshot", null) ?: return
        val extra = mutableMapOf("name" to name, "expr" to variable.trim())
        if (op == "snapshot/save") {
            val type = Messages.showInputDialog("Optional declared type, e.g. java.util.List<com.example.ItemDto>", "DATA Type", null) ?: return
            if (type.isNotBlank()) extra["type"] = type.trim()
        }
        connection()?.request(op, extra, { status.text = it["value"]; refresh() }, ::error)
    }
    private fun load() {
        val row = list.selectedValue ?: return
        val service = connection() ?: return
        if (row.kind == "RECIPE") {
            service.request("recipe/load", mapOf("name" to row.name), {
                insertSnippet(it["value"].orEmpty()); status.text = "Recipe inserted; use Run to execute."
            }, ::error)
            return
        }
        val variable = Messages.showInputDialog("Java variable name", "Load Snapshot", null, "restored", null) ?: return
        service.snapshotLoad(row.name, variable, {
            status.text = it; onVariableLoaded?.invoke(variable, null)
        }, ::error)
    }
    override fun dispose() {}
    private fun error(message: String) { status.text = message }
    fun refresh() {
        val service = connection() ?: return
        if (!service.isConnected()) { model.clear(); status.text = "Disconnected"; return }
        service.listAgentSnapshots({ text ->
            val selected = list.selectedValuesList.map { it.name }.toSet()
            model.clear()
            text.lines().filter(String::isNotBlank).forEach {
                val p = it.split('\t')
                model.addElement(Row(p[0], p.getOrElse(1) { "" }, p.getOrElse(2) { "" }))
                if (p[0] in selected) list.addSelectionInterval(model.size - 1, model.size - 1)
            }
        }, ::error)
    }
}
