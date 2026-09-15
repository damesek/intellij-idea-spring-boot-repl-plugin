package hu.baader.repl.ui

import hu.baader.repl.nrepl.NreplService
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.*
import javax.swing.table.DefaultTableModel

class SnapshotDiffPanel(private val connection: () -> NreplService?) : JPanel(BorderLayout()), com.intellij.openapi.Disposable {
    private val model = object : DefaultTableModel(arrayOf("Field / array index", "Change", "Before", "After"), 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val title = JLabel("Select two DATA snapshots and click Compare.")
    private val status = JLabel("Values are compared without restoring DTOs or executing code.")
    private val previous = JButton("Previous").apply { isEnabled = false }
    private val next = JButton("Next").apply { isEnabled = false }
    private val swap = JButton("Swap before / after").apply { isEnabled = false }
    private var before = ""
    private var after = ""
    private var offset = 0
    private var generation = 0
    init {
        previous.addActionListener { offset = (offset - 100).coerceAtLeast(0); load() }
        next.addActionListener { offset += 100; load() }
        swap.addActionListener { val old = before; before = after; after = old; offset = 0; load() }
        val table = JTable(model).apply { autoResizeMode = JTable.AUTO_RESIZE_OFF }
        listOf(300, 100, 320, 320).forEachIndexed { index, width -> table.columnModel.getColumn(index).preferredWidth = width }
        add(JPanel(BorderLayout()).apply {
            add(title, BorderLayout.NORTH)
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply { add(previous); add(next); add(swap) }, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        add(JScrollPane(table), BorderLayout.CENTER); add(status, BorderLayout.SOUTH)
    }
    fun compare(before: String, after: String) { this.before = before; this.after = after; offset = 0; load() }
    private fun load() {
        val service = connection() ?: return
        val expected = ++generation
        title.text = "Before: $before    →    After: $after"
        previous.isEnabled = false; next.isEnabled = false; swap.isEnabled = false
        status.text = "Comparing saved DATA…"; model.rowCount = 0
        service.request("snapshot/diff", mapOf("before" to before, "after" to after, "offset" to offset.toString(), "limit" to "100"), {
            if (expected != generation) return@request
            rows(it["value"].orEmpty()).forEach { row -> model.addRow(row.toTypedArray()) }
            previous.isEnabled = offset > 0; next.isEnabled = it["has-more"] == "true" && offset < 9900; swap.isEnabled = true
            status.text = when {
                it["scan-limited"] == "true" -> "Partial comparison: scan limit or interruption reached. Results do not prove equality."
                it["has-more"] == "true" -> "Changes ${offset + 1}–${offset + model.rowCount}; more differences exist. Previews are shortened."
                model.rowCount == 0 && offset == 0 -> "No DATA differences. Capture timestamps and envelope metadata are ignored."
                else -> "${it["changes-found"]} DATA differences; showing ${offset + 1}–${offset + model.rowCount}. Previews are shortened."
            }
        }, { if (expected == generation) { status.text = it; previous.isEnabled = offset > 0; swap.isEnabled = true } })
    }
    override fun dispose() { generation++ }
    companion object {
        fun rows(text: String) = text.lineSequence().filter(String::isNotBlank).map { it.split('\t', limit = 4) }
            .filter { it.size == 4 && it[1] in setOf("ADDED", "REMOVED", "CHANGED") }.toList()
    }
}
