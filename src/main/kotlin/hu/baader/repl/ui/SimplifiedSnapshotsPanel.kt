package hu.baader.repl.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import hu.baader.repl.nrepl.NreplService
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.ListSelectionListener

/**
 * Simplified Snapshots Panel - just Save and Load, automatic JSON/Object detection
 */
class SimplifiedSnapshotsPanel(
    private val connection: () -> NreplService?,
    private val insertSnippet: (String) -> Unit,
    private val onVariableLoaded: ((String, Any?) -> Unit)? = null
) : JPanel(BorderLayout()) {

    private val listModel = DefaultListModel<SnapshotItem>()
    private val list = JBList(listModel)

    data class SnapshotItem(val name: String, val type: String = "") {
        override fun toString() = if (type.isNotEmpty()) "$name ($type)" else name
    }

    init {
        setupUI()
        setupListeners()
    }

    private fun setupUI() {
        // Toolbar with Save and Load buttons
        val toolbar = JPanel()
        toolbar.layout = BoxLayout(toolbar, BoxLayout.X_AXIS)

        val saveButton = JButton("Save", AllIcons.Actions.MenuSaveall)
        saveButton.toolTipText = "Save current result as snapshot"
        saveButton.addActionListener { onSave() }

        val loadButton = JButton("Load", AllIcons.Actions.Download)
        loadButton.toolTipText = "Load selected snapshot into variable"
        loadButton.addActionListener { onLoad() }

        val importButton = JButton("Import JSON", AllIcons.ToolbarDecorator.Import)
        importButton.toolTipText = "Import JSON data as snapshot"
        importButton.addActionListener { onImportJson() }

        val springButton = JButton("Spring", AllIcons.Nodes.Services)
        springButton.toolTipText = "Save Spring context beans"
        springButton.addActionListener { onSaveSpringBeans() }

        val deleteButton = JButton("Delete", AllIcons.Actions.GC)
        deleteButton.toolTipText = "Delete selected snapshot"
        deleteButton.addActionListener { onDelete() }

        val refreshButton = JButton("Refresh", AllIcons.Actions.Refresh)
        refreshButton.toolTipText = "Refresh snapshot list"
        refreshButton.addActionListener { refresh() }

        toolbar.add(saveButton)
        toolbar.add(loadButton)
        toolbar.add(importButton)
        toolbar.add(springButton)
        toolbar.add(Box.createHorizontalStrut(10))
        toolbar.add(deleteButton)
        toolbar.add(Box.createHorizontalGlue())
        toolbar.add(refreshButton)

        add(toolbar, BorderLayout.NORTH)

        // List of snapshots
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        add(JBScrollPane(list), BorderLayout.CENTER)

        // Info panel
        val infoLabel = JBLabel("Double-click to load, or select and click Load")
        infoLabel.horizontalAlignment = SwingConstants.CENTER
        add(infoLabel, BorderLayout.SOUTH)
    }

    private fun setupListeners() {
        // Double-click to load
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    onLoad()
                }
            }
        })
    }

    private fun onSave() {
        val dialog = SaveDialog()
        if (dialog.showAndGet()) {
            val name = dialog.getName()
            val expr = dialog.getExpression()
            if (name.isNotEmpty() && expr.isNotEmpty()) {
                connection()?.let { nrepl ->
                    nrepl.snapshotSave(name, expr,
                        onResult = { result ->
                            ApplicationManager.getApplication().invokeLater {
                                Messages.showMessageDialog(
                                    result,
                                    "Save Snapshot",
                                    Messages.getInformationIcon()
                                )
                                refresh()
                            }
                        },
                        onError = { error ->
                            ApplicationManager.getApplication().invokeLater {
                                Messages.showErrorDialog(
                                    "Failed to save: $error",
                                    "Save Error"
                                )
                            }
                        }
                    )
                } ?: run {
                    Messages.showErrorDialog("Not connected to REPL", "Connection Error")
                }
            }
        }
    }

    private fun onLoad() {
        val selected = list.selectedValue
        if (selected != null) {
            val dialog = LoadDialog(selected.name)
            if (dialog.showAndGet()) {
                val varName = dialog.getVariableName()
                connection()?.let { nrepl ->
                    nrepl.snapshotLoad(selected.name, varName,
                        onResult = { result ->
                            // Notify the variables panel
                            onVariableLoaded?.invoke(varName, null)

                            // Insert working reflection code to access the loaded variable
                            val snippet = """
// Variable '$varName' loaded from snapshot '${selected.name}'
// The snapshot is now stored in SnapshotStore as '$varName'
// Access it using reflection:
Object $varName;
try {
  Class<?> ss = Class.forName("com.baader.devrt.SnapshotStore");
  java.lang.reflect.Method get = ss.getMethod("get", String.class);
  $varName = get.invoke(null, "$varName");  // Load from SnapshotStore using the variable name
} catch (Exception e) {
  $varName = null;
  System.err.println("Failed to load: " + e);
}

// Now use it:
return $varName;

// Or cast to specific type:
// var myMap = (java.util.Map) $varName;
// return myMap.size();
""".trim()
                            ApplicationManager.getApplication().invokeLater {
                                insertSnippet(snippet)
                                Messages.showMessageDialog(
                                    result,
                                    "Load Snapshot",
                                    Messages.getInformationIcon()
                                )
                            }
                        },
                        onError = { error ->
                            ApplicationManager.getApplication().invokeLater {
                                Messages.showErrorDialog(
                                    "Failed to load: $error",
                                    "Load Error"
                                )
                            }
                        }
                    )
                } ?: run {
                    Messages.showErrorDialog("Not connected to REPL", "Connection Error")
                }
            }
        } else {
            Messages.showWarningDialog("Please select a snapshot to load", "No Selection")
        }
    }

    private fun onSaveSpringBeans() {
        val options = arrayOf(
            "All Beans",
            "Repositories Only",
            "Services Only",
            "Controllers Only",
            "Selected Bean..."
        )

        val choice = Messages.showChooseDialog(
            "What do you want to save?",
            "Save Spring Beans",
            options,
            options[0],
            AllIcons.Nodes.Services
        )

        if (choice >= 0) {
            when(choice) {
                0 -> saveAllBeans()
                1 -> saveBeansByType("Repository")
                2 -> saveBeansByType("Service")
                3 -> saveBeansByType("Controller")
                4 -> saveSelectedBean()
            }
        }
    }

    private fun saveAllBeans() {
        connection()?.let { nrepl ->
            val expr = """
                org.springframework.context.ApplicationContext applicationContext = (org.springframework.context.ApplicationContext) com.baader.devrt.ReplBindings.applicationContext();
                var beans = applicationContext.getBeanDefinitionNames();
                var result = new java.util.HashMap();
                for (String name : beans) {
                    try {
                        Object bean = applicationContext.getBean(name);
                        result.put(name, bean.toString());
                    } catch (Exception e) {
                        // Skip beans that can't be accessed
                    }
                }
                return result;
            """.trimIndent()

            nrepl.snapshotSave("allBeans", expr,
                onResult = { result ->
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showMessageDialog(
                            "All Spring beans saved to snapshot 'allBeans'",
                            "Save Successful",
                            Messages.getInformationIcon()
                        )
                        refresh()
                    }
                },
                onError = { error ->
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(
                            "Failed to save beans: $error",
                            "Save Error"
                        )
                    }
                }
            )
        }
    }

    private fun saveBeansByType(type: String) {
        connection()?.let { nrepl ->
            val expr = """
                org.springframework.context.ApplicationContext applicationContext = (org.springframework.context.ApplicationContext) com.baader.devrt.ReplBindings.applicationContext();
                var beans = applicationContext.getBeanDefinitionNames();
                var result = new java.util.HashMap();
                for (String name : beans) {
                    if (name.toLowerCase().contains("${type.lowercase()}")) {
                        try {
                            Object bean = applicationContext.getBean(name);
                            result.put(name, bean.toString());
                        } catch (Exception e) {
                            // Skip
                        }
                    }
                }
                return result;
            """.trimIndent()

            nrepl.snapshotSave("${type.lowercase()}Beans", expr,
                onResult = { result ->
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showMessageDialog(
                            "$type beans saved to snapshot '${type.lowercase()}Beans'",
                            "Save Successful",
                            Messages.getInformationIcon()
                        )
                        refresh()
                    }
                },
                onError = { error ->
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(
                            "Failed to save $type beans: $error",
                            "Save Error"
                        )
                    }
                }
            )
        }
    }

    private fun saveSelectedBean() {
        val beanName = Messages.showInputDialog(
            "Enter bean name:",
            "Save Specific Bean",
            AllIcons.Nodes.Services
        )

        if (!beanName.isNullOrBlank()) {
            connection()?.let { nrepl ->
                val expr = """
                    org.springframework.context.ApplicationContext applicationContext = (org.springframework.context.ApplicationContext) com.baader.devrt.ReplBindings.applicationContext();
                    return applicationContext.getBean(\"$beanName\");
                """.trimIndent()

                nrepl.snapshotSave(beanName, expr,
                    onResult = { result ->
                        ApplicationManager.getApplication().invokeLater {
                            Messages.showMessageDialog(
                                "Bean '$beanName' saved to snapshot",
                                "Save Successful",
                                Messages.getInformationIcon()
                            )
                            refresh()
                        }
                    },
                    onError = { error ->
                        ApplicationManager.getApplication().invokeLater {
                            Messages.showErrorDialog(
                                "Failed to save bean: $error",
                                "Save Error"
                            )
                        }
                    }
                )
            }
        }
    }

    private fun onImportJson() {
        val dialog = ImportJsonDialog()
        if (dialog.showAndGet()) {
            val name = dialog.getName()
            val json = dialog.getJson()
            val type = dialog.getType()

            if (name.isNotEmpty() && json.isNotEmpty()) {
                connection()?.let { nrepl ->
                    // Create expression that saves the JSON
                    val expr = if (type.isNotEmpty()) {
                        // With type
                        """
                        String json = ${'"'}${'"'}${'"'}$json${'"'}${'"'}${'"'};
                        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        return mapper.readValue(json, $type.class);
                        """.trimIndent()
                    } else {
                        // Without type - generic Object
                        """
                        String json = ${'"'}${'"'}${'"'}$json${'"'}${'"'}${'"'};
                        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        return mapper.readValue(json, Object.class);
                        """.trimIndent()
                    }

                    nrepl.snapshotSave(name, expr,
                        onResult = { result ->
                            ApplicationManager.getApplication().invokeLater {
                                Messages.showMessageDialog(
                                    "JSON imported as snapshot: $name",
                                    "Import Successful",
                                    Messages.getInformationIcon()
                                )
                                refresh()
                            }
                        },
                        onError = { error ->
                            ApplicationManager.getApplication().invokeLater {
                                Messages.showErrorDialog(
                                    "Failed to import: $error",
                                    "Import Error"
                                )
                            }
                        }
                    )
                } ?: run {
                    Messages.showErrorDialog("Not connected to REPL", "Connection Error")
                }
            }
        }
    }

    private fun onDelete() {
        val selected = list.selectedValue
        if (selected != null) {
            val confirm = Messages.showYesNoDialog(
                "Delete snapshot '${selected.name}'?",
                "Confirm Delete",
                Messages.getQuestionIcon()
            )
            if (confirm == Messages.YES) {
                connection()?.let { nrepl ->
                    nrepl.deleteSnapshot(selected.name,
                        onResult = {
                            refresh()
                        },
                        onError = { error ->
                            ApplicationManager.getApplication().invokeLater {
                                Messages.showErrorDialog(
                                    "Failed to delete: $error",
                                    "Delete Error"
                                )
                            }
                        }
                    )
                }
            }
        } else {
            Messages.showWarningDialog("Please select a snapshot to delete", "No Selection")
        }
    }

    fun refresh() {
        connection()?.let { nrepl ->
            nrepl.snapshotListSimple(
                onResult = { snapshots ->
                    ApplicationManager.getApplication().invokeLater {
                        listModel.clear()
                        snapshots.forEach { name ->
                            listModel.addElement(SnapshotItem(name))
                        }
                    }
                },
                onError = { error ->
                    ApplicationManager.getApplication().invokeLater {
                        listModel.clear()
                        Messages.showErrorDialog(
                            "Failed to refresh: $error",
                            "Refresh Error"
                        )
                    }
                }
            )
        } ?: run {
            ApplicationManager.getApplication().invokeLater {
                listModel.clear()
            }
        }
    }

    /**
     * Save Dialog - simple name and expression input
     */
    private inner class SaveDialog : DialogWrapper(true) {
        private val nameField = JBTextField(20)
        private val exprArea = JTextArea(5, 40)

        init {
            title = "Save Snapshot"
            init()

            // Pre-fill with common Spring Boot expressions
            exprArea.text = "// Examples:\n" +
                    "// return applicationContext.getBean(\"myBean\");\n" +
                    "// return repository.findAll();\n" +
                    "// return \"Hello, World!\";"
        }

        override fun createCenterPanel(): JComponent {
            val panel = JPanel(GridBagLayout())
            val gbc = GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                anchor = GridBagConstraints.WEST
                fill = GridBagConstraints.HORIZONTAL
                weightx = 1.0
            }

            // Name
            panel.add(JBLabel("Snapshot name:"), gbc)
            gbc.gridy++
            panel.add(nameField, gbc)

            // Expression
            gbc.gridy++
            panel.add(JBLabel("Expression (must return a value):"), gbc)
            gbc.gridy++
            gbc.fill = GridBagConstraints.BOTH
            gbc.weighty = 1.0
            panel.add(JBScrollPane(exprArea), gbc)

            panel.preferredSize = Dimension(500, 300)
            return panel
        }

        fun getName() = nameField.text.trim()
        fun getExpression() = exprArea.text.trim()
    }

    /**
     * Load Dialog - simple variable name input
     */
    private inner class LoadDialog(private val snapshotName: String) : DialogWrapper(true) {
        private val varField = JBTextField(snapshotName, 20)

        init {
            title = "Load Snapshot"
            init()
        }

        override fun createCenterPanel(): JComponent {
            val panel = JPanel(GridBagLayout())
            val gbc = GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                anchor = GridBagConstraints.WEST
                fill = GridBagConstraints.HORIZONTAL
                weightx = 1.0
            }

            panel.add(JBLabel("Loading snapshot: $snapshotName"), gbc)
            gbc.gridy++
            panel.add(JBLabel("Variable name:"), gbc)
            gbc.gridy++
            panel.add(varField, gbc)

            panel.preferredSize = Dimension(400, 150)
            return panel
        }

        fun getVariableName() = varField.text.trim()
    }

    /**
     * Import JSON Dialog - paste or load JSON data
     */
    private inner class ImportJsonDialog : DialogWrapper(true) {
        private val nameField = JBTextField(20)
        private val jsonArea = JTextArea(10, 50)
        private val typeField = JBTextField(30)

        init {
            title = "Import JSON"
            init()

            // Check clipboard for JSON
            try {
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                val contents = clipboard.getData(DataFlavor.stringFlavor) as? String
                if (contents != null && (contents.trim().startsWith("{") || contents.trim().startsWith("["))) {
                    jsonArea.text = contents
                }
            } catch (e: Exception) {
                // Ignore clipboard errors
            }
        }

        override fun createCenterPanel(): JComponent {
            val panel = JPanel(GridBagLayout())
            val gbc = GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                anchor = GridBagConstraints.WEST
                fill = GridBagConstraints.HORIZONTAL
                weightx = 1.0
            }

            // Name
            panel.add(JBLabel("Snapshot name:"), gbc)
            gbc.gridy++
            panel.add(nameField, gbc)

            // Type (optional)
            gbc.gridy++
            panel.add(JBLabel("Type (optional, e.g. java.util.Map or com.example.MyClass):"), gbc)
            gbc.gridy++
            panel.add(typeField, gbc)

            // JSON
            gbc.gridy++
            panel.add(JBLabel("JSON data (paste here):"), gbc)
            gbc.gridy++
            gbc.fill = GridBagConstraints.BOTH
            gbc.weighty = 1.0
            jsonArea.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            panel.add(JBScrollPane(jsonArea), gbc)

            panel.preferredSize = Dimension(600, 400)
            return panel
        }

        fun getName() = nameField.text.trim()
        fun getJson() = jsonArea.text.trim()
        fun getType() = typeField.text.trim()
    }
}
