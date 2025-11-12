package hu.baader.repl.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.labels.LinkLabel
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.AbstractCellEditor
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer

class HttpQuickActionsPanel(
    project: Project,
    private val runner: HttpRequestRunner,
    private val onExpansionChanged: ((Boolean) -> Unit)? = null
) : JPanel(BorderLayout()), Disposable {

    private val service = HttpRequestService.getInstance(project)
    private val tableModel = HttpQuickCasesTableModel()
    private val table = object : JBTable(tableModel) {
        override fun prepareRenderer(renderer: TableCellRenderer, row: Int, column: Int): Component {
            val component = super.prepareRenderer(renderer, row, column)
            val isRunning = runningRequests.contains(tableModel.getCase(row)?.id)
            component.font = component.font.deriveFont(if (isRunning) Font.BOLD else Font.PLAIN)
            return component
        }
    }
    private val scrollPane = JBScrollPane(table).apply {
        minimumSize = Dimension(200, 160)
    }
    private val toggleLink = LinkLabel<String>("HTTP [+]", null).apply {
        setListener({ _, _ -> toggleExpanded() }, null)
    }
    private var expanded = false
    private val runningRequests = mutableSetOf<String>()
    private val detachListener: () -> Unit

    init {
        isOpaque = false
        val header = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(toggleLink)
        }
        add(header, BorderLayout.NORTH)
        configureTable()
        scrollPane.isVisible = expanded
        scrollPane.border = null
        add(scrollPane, BorderLayout.CENTER)

        detachListener = runner.addListener(object : HttpRequestRunnerListener {
            override fun onStarted(caseId: String) {
                runningRequests.add(caseId)
                table.repaint()
            }

            override fun onFinished(caseId: String, status: HttpRequestRunnerListener.Status) {
                runningRequests.remove(caseId)
                table.repaint()
            }
        })
        refreshCases()
        onExpansionChanged?.invoke(expanded)
    }

    private fun configureTable() {
        table.setShowGrid(false)
        table.rowSelectionAllowed = false
        table.tableHeader.reorderingAllowed = false
        table.emptyText.text = "Nincs elmentett HTTP eset"
        table.preferredScrollableViewportSize = Dimension(table.preferredScrollableViewportSize.width, 120)

        val runColumn = table.columnModel.getColumn(HttpQuickCasesTableModel.RUN_COL)
        val abortColumn = table.columnModel.getColumn(HttpQuickCasesTableModel.ABORT_COL)
        val runButton = TableButtonRendererEditor(
            icon = com.intellij.icons.AllIcons.Actions.Execute,
            tooltip = "HTTP futtatása",
            isEnabled = { case -> case != null && !runningRequests.contains(case.id) },
            action = { case -> runner.run(case) }
        )
        runColumn.cellRenderer = runButton
        runColumn.cellEditor = runButton
        runColumn.maxWidth = 60
        runColumn.minWidth = 50

        val abortButton = TableButtonRendererEditor(
            icon = com.intellij.icons.AllIcons.General.BalloonError,
            tooltip = "HTTP kérés megszakítása",
            isEnabled = { case -> case != null && runningRequests.contains(case.id) },
            action = { case -> runner.abort(case.id) }
        )
        abortColumn.cellRenderer = abortButton
        abortColumn.cellEditor = abortButton
        abortColumn.maxWidth = 60
        abortColumn.minWidth = 50
    }

    private fun toggleExpanded() {
        setExpandedState(!expanded, notifyParent = true)
    }

    private fun setExpandedState(newState: Boolean, notifyParent: Boolean) {
        if (expanded == newState) return
        expanded = newState
        scrollPane.isVisible = expanded
        toggleLink.text = if (expanded) "HTTP [-]" else "HTTP [+]"
        if (expanded) {
            refreshCases()
        }
        if (notifyParent) {
            onExpansionChanged?.invoke(expanded)
        }
        revalidate()
        repaint()
    }

    fun expandPanel() {
        setExpandedState(true, notifyParent = true)
    }

    fun collapsePanel() {
        setExpandedState(false, notifyParent = true)
    }

    fun refreshCases() {
        tableModel.setCases(service.getRequests())
    }

    override fun dispose() {
        detachListener()
    }

    private inner class TableButtonRendererEditor(
        private val icon: javax.swing.Icon,
        private val tooltip: String,
        private val isEnabled: (HttpRequestCase?) -> Boolean,
        private val action: (HttpRequestCase) -> Unit
    ) : AbstractCellEditor(), TableCellRenderer, TableCellEditor {

        private val button = JButton(icon).apply {
            toolTipText = tooltip
            isOpaque = false
            isContentAreaFilled = false
            border = null
            addActionListener {
                val row = this@HttpQuickActionsPanel.table.editingRow.takeIf { it >= 0 } ?: return@addActionListener
                val case = tableModel.getCase(row) ?: return@addActionListener
                if (isEnabled(case)) {
                    action(case)
                }
                fireEditingStopped()
            }
        }

        override fun getCellEditorValue(): Any = ""

        override fun getTableCellRendererComponent(
            table: JTable?,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component = configure(row)

        override fun getTableCellEditorComponent(
            table: JTable?,
            value: Any?,
            isSelected: Boolean,
            row: Int,
            column: Int
        ): Component = configure(row)

        private fun configure(row: Int): Component {
            val case = tableModel.getCase(row)
            button.isEnabled = isEnabled(case)
            return button
        }
    }
}

private class HttpQuickCasesTableModel : AbstractTableModel() {
    private val columns = listOf("ID", "Elnevezés", "Fő téma", "Verzió", "", "")
    private val cases = mutableListOf<HttpRequestCase>()

    override fun getRowCount(): Int = cases.size

    override fun getColumnCount(): Int = columns.size

    override fun getColumnName(column: Int): String = columns[column]

    override fun getColumnClass(columnIndex: Int): Class<*> =
        if (columnIndex >= RUN_COL) Any::class.java else String::class.java

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = columnIndex >= RUN_COL

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val case = cases.getOrNull(rowIndex) ?: return ""
        return when (columnIndex) {
            0 -> case.id
            1 -> case.name.ifBlank { case.id }
            2 -> case.topic
            3 -> case.version
            else -> ""
        }
    }

    fun setCases(newCases: List<HttpRequestCase>) {
        cases.clear()
        cases.addAll(newCases)
        fireTableDataChanged()
    }

    fun getCase(row: Int): HttpRequestCase? = cases.getOrNull(row)

    companion object {
        const val RUN_COL = 4
        const val ABORT_COL = 5
    }
}
