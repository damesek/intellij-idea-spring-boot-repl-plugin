package hu.baader.repl.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import hu.baader.repl.nrepl.NreplService
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * Panel that shows currently loaded variables from snapshots
 * Allows quick access without needing reflection code
 */
class LoadedVariablesPanel(
    private val connection: () -> NreplService?,
    private val insertSnippet: (String) -> Unit,
    private val executeCode: (String) -> Unit
) : JPanel(BorderLayout()) {

    private val listModel = DefaultListModel<LoadedVariable>()
    private val list = JBList(listModel)
    private val loadedVars = mutableMapOf<String, LoadedVariable>()

    data class LoadedVariable(
        val name: String,
        val type: String,
        val value: Any?,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        override fun toString(): String {
            val typeName = when {
                type.contains("HashMap") -> "HashMap"
                type.contains("ArrayList") -> "List"
                type.contains("LinkedHashMap") -> "LinkedHashMap"
                type.contains("String") -> "String"
                else -> type.substringAfterLast('.')
            }
            return "$name ($typeName)"
        }

        fun getSize(): String {
            return when (value) {
                is Map<*, *> -> "${value.size} items"
                is Collection<*> -> "${value.size} items"
                is Array<*> -> "${value.size} items"
                is String -> "${value.length} chars"
                else -> ""
            }
        }
    }

    init {
        setupUI()
        setupListeners()
    }

    private fun setupUI() {
        // Title panel
        val titlePanel = JPanel(BorderLayout())
        val titleLabel = JBLabel("Loaded Variables")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD)
        titlePanel.add(titleLabel, BorderLayout.WEST)

        // Refresh button
        val refreshButton = JButton(AllIcons.Actions.Refresh)
        refreshButton.toolTipText = "Refresh loaded variables"
        refreshButton.preferredSize = Dimension(24, 24)
        refreshButton.addActionListener { refreshVariables() }
        titlePanel.add(refreshButton, BorderLayout.EAST)

        add(titlePanel, BorderLayout.NORTH)

        // List setup
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = VariableListCellRenderer()
        add(JBScrollPane(list), BorderLayout.CENTER)

        // Info panel
        val infoPanel = JPanel(BorderLayout())
        val infoLabel = JBLabel("Double-click to use • Right-click for options")
        infoLabel.horizontalAlignment = SwingConstants.CENTER
        infoLabel.font = infoLabel.font.deriveFont(Font.ITALIC, 10f)
        infoPanel.add(infoLabel, BorderLayout.CENTER)
        add(infoPanel, BorderLayout.SOUTH)
    }

    private fun setupListeners() {
        // Double-click to insert usage
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val selected = list.selectedValue
                    if (selected != null) {
                        insertUsage(selected)
                    }
                }
            }

            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) showPopup(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) showPopup(e)
            }
        })

        // Selection change listener for showing details
        list.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val selected = list.selectedValue
                if (selected != null) {
                    // Could show details in a separate panel
                }
            }
        }
    }

    private fun showPopup(e: MouseEvent) {
        val index = list.locationToIndex(e.point)
        if (index >= 0) {
            list.selectedIndex = index
            val selected = list.selectedValue
            if (selected != null) {
                val popup = JPopupMenu()

                // Use variable
                val useAction = JMenuItem("Use Variable", AllIcons.Actions.Execute)
                useAction.addActionListener { insertUsage(selected) }
                popup.add(useAction)

                // Show content
                val showAction = JMenuItem("Show Content", AllIcons.Actions.Preview)
                showAction.addActionListener { showContent(selected) }
                popup.add(showAction)

                // Show type info
                val typeAction = JMenuItem("Show Type", AllIcons.Actions.ShowAsTree)
                typeAction.addActionListener { showType(selected) }
                popup.add(typeAction)

                popup.addSeparator()

                // Operations based on type
                when {
                    selected.type.contains("Map") -> {
                        val keysAction = JMenuItem("Get Keys", AllIcons.Nodes.Tag)
                        keysAction.addActionListener {
                            insertSnippet("${selected.name}.keySet()")
                        }
                        popup.add(keysAction)

                        val valuesAction = JMenuItem("Get Values", AllIcons.Nodes.Parameter)
                        valuesAction.addActionListener {
                            insertSnippet("${selected.name}.values()")
                        }
                        popup.add(valuesAction)
                    }
                    selected.type.contains("List") || selected.type.contains("Collection") -> {
                        val sizeAction = JMenuItem("Get Size", AllIcons.Actions.ShowCode)
                        sizeAction.addActionListener {
                            insertSnippet("${selected.name}.size()")
                        }
                        popup.add(sizeAction)
                    }
                }

                popup.addSeparator()

                // Remove from loaded
                val removeAction = JMenuItem("Remove", AllIcons.Actions.GC)
                removeAction.addActionListener { removeVariable(selected) }
                popup.add(removeAction)

                popup.show(list, e.x, e.y)
            }
        }
    }

    private fun insertUsage(variable: LoadedVariable) {
        // Just use the variable name - it's already loaded
        insertSnippet("return ${variable.name};")
    }

    private fun showContent(variable: LoadedVariable) {
        insertSnippet("""
// Show content of ${variable.name}
return ${variable.name};
""".trim())
    }

    private fun showType(variable: LoadedVariable) {
        insertSnippet("""
// Type information for ${variable.name}
System.out.println("Type: " + ${variable.name}.getClass().getName());
System.out.println("Interfaces: " + java.util.Arrays.toString(${variable.name}.getClass().getInterfaces()));
return ${variable.name}.getClass();
""".trim())
    }

    private fun removeVariable(variable: LoadedVariable) {
        loadedVars.remove(variable.name)
        listModel.removeElement(variable)
    }

    fun addLoadedVariable(name: String, value: Any?) {
        // Automatically inject as global variable
        val varType = value?.javaClass?.name ?: "Object"

        // Inject the variable into REPL context
        connection()?.let { nrepl ->
            // Create a global variable binding
            val injectCode = """
                // Auto-loaded from snapshot
                Object $name;
                try {
                    Class<?> ss = Class.forName("com.baader.devrt.SnapshotStore");
                    $name = ss.getMethod("get", String.class).invoke(null, "$name");
                } catch (Exception e) {
                    $name = null;
                }
                // Variable '$name' is now available
                """.trimIndent()

            // Execute silently to inject the variable
            executeCode(injectCode)
        }

        // Add to our list
        val loadedVar = LoadedVariable(name, varType, value)
        loadedVars[name] = loadedVar

        ApplicationManager.getApplication().invokeLater {
            // Remove old entry if exists
            for (i in 0 until listModel.size()) {
                if (listModel[i].name == name) {
                    listModel.remove(i)
                    break
                }
            }
            // Add new entry
            listModel.addElement(loadedVar)
        }
    }

    fun refreshVariables() {
        connection()?.let { nrepl ->
            // Get list of available snapshots
            nrepl.listAgentSnapshots(
                onResult = { tsv ->
                    if (tsv.isNotBlank()) {
                        val lines = tsv.split("\n")
                        lines.forEach { line ->
                            val parts = line.split("\t")
                            if (parts.isNotEmpty()) {
                                val name = parts[0]
                                // Check if already loaded
                                if (!loadedVars.containsKey(name)) {
                                    // Auto-load it
                                    loadSnapshot(name)
                                }
                            }
                        }
                    }
                },
                onError = { }
            )
        }
    }

    private fun loadSnapshot(name: String) {
        connection()?.let { nrepl ->
            nrepl.snapshotLoad(name, name,
                onResult = {
                    addLoadedVariable(name, null)
                },
                onError = { }
            )
        }
    }

    fun clear() {
        loadedVars.clear()
        ApplicationManager.getApplication().invokeLater {
            listModel.clear()
        }
    }

    // Custom cell renderer
    private inner class VariableListCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)

            if (value is LoadedVariable && component is JLabel) {
                component.text = value.toString()
                val size = value.getSize()
                if (size.isNotEmpty()) {
                    component.text = "${component.text} - $size"
                }

                // Icon based on type
                component.icon = when {
                    value.type.contains("Map") -> AllIcons.Nodes.EntryPoints
                    value.type.contains("List") || value.type.contains("Collection") -> AllIcons.Nodes.Lambda
                    value.type.contains("String") -> AllIcons.Nodes.Type
                    value.type.contains("Integer") || value.type.contains("Long") -> AllIcons.Nodes.Field
                    else -> AllIcons.Nodes.AnonymousClass
                }
            }

            return component
        }
    }
}