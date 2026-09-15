package hu.baader.repl.ui

import hu.baader.repl.editor.NotebookState
import java.awt.BorderLayout
import javax.swing.*
import javax.swing.table.DefaultTableModel

class NotebookPanel(private val notebook: () -> NotebookState, private val navigate: (Int) -> Unit) : JPanel(BorderLayout()) {
    private val model = object : DefaultTableModel(arrayOf("Cell", "Run", "State", "Last run", "ms", "Depends on"), 0) { override fun isCellEditable(r: Int, c: Int) = false }
    private val table = JTable(model)
    private val output = JTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
    init {
        add(JLabel("Cell evidence · dependencies are conservative · application effects require explicit reruns"), BorderLayout.NORTH)
        add(JSplitPane(JSplitPane.VERTICAL_SPLIT, JScrollPane(table), JScrollPane(output)).apply { resizeWeight = 0.45 }, BorderLayout.CENTER)
        val bar = WorkbookToolbar()
        bar.add(JButton("Go to cell").apply { addActionListener { if (table.selectedRow >= 0) navigate(table.selectedRow) } })
        add(bar, BorderLayout.SOUTH)
        table.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) showSelection() }
    }
    fun refresh() {
        val selected = table.selectedRow; val state = notebook(); model.rowCount = 0
        state.cells.forEachIndexed { i, c -> model.addRow(arrayOf(i + 1, if (c.sequence == 0L) "—" else "[${c.sequence}]", c.label,
            c.startedAt, c.durationMs, state.dependencies(c.id).map { id -> state.cells.indexOfFirst { it.id == id } + 1 }.joinToString(", "))) }
        if (model.rowCount > 0) table.setRowSelectionInterval(selected.coerceIn(0, model.rowCount - 1), selected.coerceIn(0, model.rowCount - 1))
        showSelection()
    }
    fun selectCell(id: String) {
        val index = notebook().cells.indexOfFirst { it.id == id }
        if (index >= 0 && index < table.rowCount) table.setRowSelectionInterval(index, index)
    }
    private fun showSelection() {
        val c = notebook().cells.getOrNull(table.selectedRow)
        output.text = if (c == null) "" else "${c.stale}\nDeclared: ${c.declarations.joinToString()}\nCandidate inputs: ${c.inputs.take(80).joinToString()}\n\n${c.output}"
        output.caretPosition = 0
    }
}
