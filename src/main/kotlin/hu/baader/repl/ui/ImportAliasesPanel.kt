package hu.baader.repl.ui

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import hu.baader.repl.settings.ImportAlias
import hu.baader.repl.settings.PluginSettingsState
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.util.*
import javax.swing.AbstractAction
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

class ImportAliasesPanel : JPanel(BorderLayout()) {

    private val settings = PluginSettingsState.getInstance().state
    private val model = ImportAliasTableModel(settings.importAliases)
    private val table = JBTable(model)

    init {
        table.setShowGrid(false)
        table.columnModel.getColumn(0).maxWidth = 70
        table.columnModel.getColumn(0).resizable = false
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION

        val decorator = ToolbarDecorator.createDecorator(table)
            .setAddAction { showDialog(null) }
            .setEditAction { val row = table.selectedRow; if (row >= 0) showDialog(model.getAliasAt(table.convertRowIndexToModel(row))) }
            .setRemoveAction {
                val row = table.selectedRow
                if (row >= 0) {
                    model.removeAt(table.convertRowIndexToModel(row))
                }
            }
            .disableUpDownActions()

        add(decorator.createPanel(), BorderLayout.CENTER)
        table.actionMap.put("toggle", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                val row = table.selectedRow
                if (row >= 0) model.toggle(table.convertRowIndexToModel(row))
            }
        })
    }

    private fun showDialog(existing: ImportAlias?) {
        val dialog = ImportAliasDialog(existing)
        if (dialog.showAndGet()) {
            val alias = dialog.getAlias().trim()
            val fqn = dialog.getFqn().trim()
            if (alias.isNotEmpty() && fqn.isNotEmpty()) {
                if (existing == null) {
                    model.add(ImportAlias(alias, fqn, true))
                } else {
                    existing.alias = alias
                    existing.fqn = fqn
                    existing.enabled = dialog.isEnabled()
                    model.fireTableDataChanged()
                }
            }
        }
    }
}

private class ImportAliasTableModel(private val aliases: MutableList<ImportAlias>) : AbstractTableModel() {
    private val columns = arrayOf("On", "Alias", "Fully Qualified Name")

    override fun getRowCount(): Int = aliases.size
    override fun getColumnCount(): Int = columns.size
    override fun getColumnName(column: Int): String = columns[column]
    override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
        0 -> Boolean::class.java
        else -> String::class.java
    }

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = columnIndex == 0

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = when (columnIndex) {
        0 -> aliases[rowIndex].enabled
        1 -> aliases[rowIndex].alias
        else -> aliases[rowIndex].fqn
    }

    override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
        if (columnIndex == 0) {
            aliases[rowIndex].enabled = (aValue as? Boolean) ?: false
            fireTableCellUpdated(rowIndex, columnIndex)
        }
    }

    fun add(alias: ImportAlias) {
        aliases.add(alias)
        fireTableRowsInserted(aliases.lastIndex, aliases.lastIndex)
    }

    fun removeAt(index: Int) {
        if (index in aliases.indices) {
            aliases.removeAt(index)
            fireTableDataChanged()
        }
    }

    fun toggle(index: Int) {
        if (index in aliases.indices) {
            aliases[index].enabled = !aliases[index].enabled
            fireTableRowsUpdated(index, index)
        }
    }

    fun getAliasAt(modelRow: Int): ImportAlias? = aliases.getOrNull(modelRow)
}

private class ImportAliasDialog(existing: ImportAlias?) : DialogWrapper(true) {
    private val aliasField = JBTextField(existing?.alias ?: "", 20)
    private val fqnField = JBTextField(existing?.fqn ?: "", 40)
    private val enabledBox = JBCheckBox("Enabled", existing?.enabled ?: true)

    init {
        title = if (existing == null) "Add Import" else "Edit Import"
        init()
    }

    override fun createCenterPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
        }
        panel.add(labeledField("Alias", aliasField), gbc)
        gbc.gridy++
        panel.add(labeledField("Fully qualified name", fqnField), gbc)
        gbc.gridy++
        panel.add(enabledBox, gbc)
        return panel
    }

    private fun labeledField(label: String, field: JBTextField): JPanel {
        val panel = JPanel(BorderLayout())
        panel.add(javax.swing.JLabel(label), BorderLayout.NORTH)
        panel.add(field, BorderLayout.CENTER)
        return panel
    }

    fun getAlias(): String = aliasField.text
    fun getFqn(): String = fqnField.text
    fun isEnabled(): Boolean = enabledBox.isSelected
}
