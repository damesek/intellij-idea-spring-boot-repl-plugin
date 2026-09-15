package hu.baader.repl.ui

import com.google.gson.JsonParser
import com.intellij.openapi.ui.Messages
import hu.baader.repl.nrepl.NreplService
import java.awt.BorderLayout
import javax.swing.*
import javax.swing.table.DefaultTableModel

class SnapshotVersionsPanel(private val service: () -> NreplService?, private val changed: () -> Unit) : JPanel(BorderLayout()), com.intellij.openapi.Disposable {
    private var name = ""
    private var generation = 0
    private var disposed = false
    private val model = object : DefaultTableModel(arrayOf("SHA-256", "Current", "Recorded", "Kind", "Bytes"), 0) { override fun isCellEditable(r: Int, c: Int) = false }
    private val table = JTable(model)
    private val details = JTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
    private val status = JLabel("Choose a persistent snapshot on Saved, then Versions")
    init {
        val bar = WorkbookToolbar()
        fun button(label: String, action: () -> Unit) { bar.add(JButton(label).apply { addActionListener { action() } }) }
        button("Refresh") { if (name.isNotBlank()) open(name) }
        button("Provenance") {
            selected()?.let { version -> service()?.request("snapshot/provenance", mapOf("name" to name, "version" to version), {
                details.text = runCatching { com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(JsonParser.parseString(it["value"].orEmpty())) }.getOrDefault(it["value"].orEmpty()); details.caretPosition = 0
            }, { status.text = it }) }
        }
        button("Load version") {
            val version = selected() ?: return@button
            if (model.getValueAt(table.selectedRow, 3) != "DATA") { status.text = "Use Restore as latest for CASE/RECIPE; source is never run automatically"; return@button }
            val variable = Messages.showInputDialog("Java variable name (DATA constructors may run)", "Load Snapshot Version", null, "restored", null) ?: return@button
            service()?.request("snapshot/load", mapOf("name" to name, "version" to version, "var" to variable), { status.text = it["value"]; changed() }, { status.text = it })
        }
        button("Restore as latest") {
            val version = selected() ?: return@button
            if (Messages.showYesNoDialog("Make this version of $name current? The present value stays in history. No Java is executed.", "Restore Snapshot Version", null) != Messages.YES) return@button
            service()?.request("snapshot/restore-version", mapOf("name" to name, "version" to version), { status.text = it["value"]; changed(); open(name) }, { status.text = it })
        }
        add(JPanel(BorderLayout()).apply { add(bar, BorderLayout.NORTH); add(status, BorderLayout.SOUTH) }, BorderLayout.NORTH)
        add(JSplitPane(JSplitPane.VERTICAL_SPLIT, JScrollPane(table), JScrollPane(details)).apply { resizeWeight = 0.45 }, BorderLayout.CENTER)
    }
    private fun selected(): String? = if (table.selectedRow < 0) null else model.getValueAt(table.selectedRow, 0).toString()
    fun open(snapshot: String) {
        name = snapshot; val gen = ++generation; status.text = "Versions of $name · maximum 100 · SHA verified when opened/restored"
        service()?.request("snapshot/versions", mapOf("name" to name, "limit" to "100"), {
            if (disposed || gen != generation) return@request
            model.rowCount = 0
            for (entry in JsonParser.parseString(it["versions-json"].orEmpty()).asJsonArray) {
                val row = entry.asJsonObject
                model.addRow(arrayOf(row["version"].asString, row["current"].asBoolean, (row["recordedAt"] ?: row["capturedAt"])?.asString, row["kind"].asString, row["sizeBytes"].asString))
            }
        }, { if (!disposed && gen == generation) status.text = it })
    }
    override fun dispose() { disposed = true; generation++ }
}
