package hu.baader.repl.ui

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import hu.baader.repl.nrepl.NreplService
import hu.baader.repl.settings.PluginSettingsState
import java.awt.*
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.io.File
import javax.swing.*

/**
 * Run Configuration Panel - manage saved REPL connection configs
 */
class RunConfigPanel(
    private val project: Project,
    private val connection: () -> NreplService?
) : JPanel(BorderLayout()) {

    // UI Components
    private val configNameField = JBTextField(20)
    private val appNameSelector = ComboBox<String>()
    private val bindContextField = JBTextField(40)
    private val configSelector = ComboBox<SavedConfig>()
    private val statusLabel = JBLabel("Not connected")
    private var isRunning = false

    // Configuration storage
    private val configService = RunConfigService.getInstance(project)

    data class SavedConfig(
        var name: String = "",
        var appName: String = "",
        var bindExpression: String = ""
    ) {
        override fun toString() = name
    }

    init {
        setupUI()
        loadConfigs()
        restoreLastState()
    }

    private fun setupUI() {
        // Title
        val titlePanel = JPanel(BorderLayout())
        val titleLabel = JBLabel("Run Configuration")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 14f)
        titlePanel.add(titleLabel, BorderLayout.WEST)
        add(titlePanel, BorderLayout.NORTH)

        // Main panel with form
        val formPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints()
        gbc.insets = Insets(5, 5, 5, 5)
        gbc.fill = GridBagConstraints.HORIZONTAL

        // Row 1: Config selector
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.gridwidth = 1
        formPanel.add(JBLabel("Configuration:"), gbc)

        gbc.gridx = 1
        gbc.weightx = 1.0
        configSelector.renderer = ConfigListCellRenderer()
        configSelector.addActionListener {
            val selected = configSelector.selectedItem as? SavedConfig
            if (selected != null) {
                loadConfig(selected)
                saveCurrentState()
            }
        }
        formPanel.add(configSelector, gbc)

        // Row 2: Config name
        gbc.gridx = 0
        gbc.gridy = 1
        gbc.weightx = 0.0
        formPanel.add(JBLabel("Name:"), gbc)

        gbc.gridx = 1
        gbc.weightx = 1.0
        configNameField.toolTipText = "Name for this configuration"
        configNameField.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) {
                saveCurrentState()
            }
        })
        formPanel.add(configNameField, gbc)

        // Row 3: Application name/pattern
        gbc.gridx = 0
        gbc.gridy = 2
        gbc.weightx = 0.0
        formPanel.add(JBLabel("App Name:"), gbc)

        gbc.gridx = 1
        gbc.weightx = 1.0
        appNameSelector.isEditable = true
        appNameSelector.toolTipText = "Application name or pattern (e.g., 'vernyomas-app' or 'VernyomasApplication')"
        // Add default app name patterns
        val defaultAppNames = listOf("vernyomas", "vernyomas-app", "VernyomasApplication")
        defaultAppNames.forEach { appNameSelector.addItem(it) }
        // Add saved custom app names
        configService.getCustomAppNames().forEach { name ->
            if (name !in defaultAppNames) {
                appNameSelector.addItem(name)
            }
        }
        appNameSelector.addActionListener {
            saveCurrentState()
        }
        formPanel.add(appNameSelector, gbc)

        // Row 4: Bind context expression
        gbc.gridx = 0
        gbc.gridy = 3
        gbc.weightx = 0.0
        formPanel.add(JBLabel("Bind Context:"), gbc)

        gbc.gridx = 1
        gbc.weightx = 1.0
        bindContextField.toolTipText = "Expression to get ApplicationContext (e.g., hu.vernyomas.app.AppCtxHolder.get())"
        // No default value - user should enter if needed
        bindContextField.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) {
                saveCurrentState()
            }
        })
        formPanel.add(bindContextField, gbc)

        // Button panel
        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT))

        // Start button with green play icon
        val startButton = JButton("Start", AllIcons.Actions.Execute)
        startButton.toolTipText = "Connect and bind context"
        startButton.addActionListener { onStart() }

        // Stop button
        val stopButton = JButton("Stop", AllIcons.Actions.Suspend)
        stopButton.toolTipText = "Disconnect"
        stopButton.addActionListener { onStop() }

        // Save button
        val saveButton = JButton("Save", AllIcons.Actions.MenuSaveall)
        saveButton.toolTipText = "Save current configuration"
        saveButton.addActionListener { onSave() }

        // Delete button
        val deleteButton = JButton("Delete", AllIcons.Actions.GC)
        deleteButton.toolTipText = "Delete selected configuration"
        deleteButton.addActionListener { onDelete() }

        // Refresh button (find running JVMs)
        val findButton = JButton("Find Apps", AllIcons.Actions.Find)
        findButton.toolTipText = "Find running Java applications"
        findButton.addActionListener { onFindApps() }

        buttonPanel.add(startButton)
        buttonPanel.add(stopButton)
        buttonPanel.add(Box.createHorizontalStrut(10))
        buttonPanel.add(saveButton)
        buttonPanel.add(deleteButton)
        buttonPanel.add(Box.createHorizontalStrut(10))
        buttonPanel.add(findButton)

        // Status panel
        val statusPanel = JPanel(BorderLayout())
        statusLabel.horizontalAlignment = SwingConstants.CENTER
        statusLabel.border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
        statusPanel.add(statusLabel, BorderLayout.CENTER)

        // Add all to main panel
        val centerPanel = JPanel(BorderLayout())
        centerPanel.add(formPanel, BorderLayout.NORTH)
        centerPanel.add(buttonPanel, BorderLayout.CENTER)
        centerPanel.add(statusPanel, BorderLayout.SOUTH)

        add(centerPanel, BorderLayout.CENTER)
    }

    fun startFromTemplate() {
        // Public method to trigger start from external action
        onStart()
    }

    private fun onStart() {
        val appName = (appNameSelector.selectedItem as? String)?.trim() ?: ""
        val bindExpr = bindContextField.text.trim()

        if (appName.isEmpty()) {
            showError("Please enter application name or pattern")
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                updateStatus("Finding application...")

                // Find JVM by name pattern
                val vms = com.sun.tools.attach.VirtualMachine.list()
                val targetVm = vms.find { vm ->
                    val displayName = vm.displayName()
                    displayName.contains(appName, ignoreCase = true) ||
                    displayName.contains(appName.replace("-", ""), ignoreCase = true)
                }

                if (targetVm == null) {
                    updateStatus("Application '$appName' not found")
                    showError("Could not find running application matching: $appName")
                    return@executeOnPooledThread
                }

                updateStatus("Attaching to ${targetVm.displayName()}...")

                // Attach and inject agent
                val settings = PluginSettingsState.getInstance().state
                var agentJar = settings.agentJarPath.trim()

                if (agentJar.isEmpty() || !File(agentJar).exists()) {
                    ApplicationManager.getApplication().invokeLater {
                        val path = Messages.showInputDialog(
                            project,
                            "Enter path to dev-runtime-agent JAR:",
                            "Agent JAR Path Required",
                            null
                        )
                        if (!path.isNullOrBlank() && File(path).exists()) {
                            settings.agentJarPath = path
                            agentJar = path
                        }
                    }
                }

                if (agentJar.isEmpty() || !File(agentJar).exists()) {
                    updateStatus("Agent JAR not configured")
                    return@executeOnPooledThread
                }

                // Inject agent
                val vm = com.sun.tools.attach.VirtualMachine.attach(targetVm.id())
                try {
                    val args = "port=${settings.agentPort}"
                    vm.loadAgent(agentJar, args)
                } finally {
                    vm.detach()
                }

                updateStatus("Agent injected, connecting...")

                // Connect to nREPL
                connection()?.let { nrepl ->
                    nrepl.disconnect()
                    settings.host = "127.0.0.1"
                    settings.port = settings.agentPort
                    nrepl.connectAsync()

                    // Wait a bit for connection
                    Thread.sleep(500)

                    // Bind Spring context if expression provided
                    if (bindExpr.isNotEmpty()) {
                        updateStatus("Binding context...")
                        nrepl.bindSpring(bindExpr,
                            onResult = { result ->
                                ApplicationManager.getApplication().invokeLater {
                                    updateStatus("Connected and ready!")
                                    isRunning = true
                                    showInfo("Successfully connected to ${targetVm.displayName()}")
                                }
                            },
                            onError = { error ->
                                ApplicationManager.getApplication().invokeLater {
                                    updateStatus("Context binding failed")
                                    showError("Failed to bind context: $error")
                                }
                            }
                        )
                    } else {
                        ApplicationManager.getApplication().invokeLater {
                            updateStatus("Connected (no context)")
                            isRunning = true
                        }
                    }
                }

            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    updateStatus("Connection failed")
                    showError("Failed to connect: ${e.message}")
                }
            }
        }
    }

    private fun onStop() {
        connection()?.let { nrepl ->
            nrepl.disconnect()
            updateStatus("Disconnected")
            isRunning = false
        }
    }

    private fun onSave() {
        val name = configNameField.text.trim()
        if (name.isEmpty()) {
            showError("Please enter a configuration name")
            return
        }

        val config = SavedConfig(
            name = name,
            appName = (appNameSelector.selectedItem as? String)?.trim() ?: "",
            bindExpression = bindContextField.text.trim()
        )

        configService.saveConfig(config)
        loadConfigs()
        saveCurrentState()
        showInfo("Configuration '$name' saved")
    }

    private fun onDelete() {
        val selected = configSelector.selectedItem as? SavedConfig
        if (selected != null) {
            val confirm = Messages.showYesNoDialog(
                project,
                "Delete configuration '${selected.name}'?",
                "Confirm Delete",
                Messages.getQuestionIcon()
            )
            if (confirm == Messages.YES) {
                configService.deleteConfig(selected.name)
                loadConfigs()
                clearForm()
            }
        }
    }

    private fun onFindApps() {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val vms = com.sun.tools.attach.VirtualMachine.list()
                val apps = vms.map { "${it.id()} - ${it.displayName()}" }.toTypedArray()

                ApplicationManager.getApplication().invokeLater {
                    if (apps.isEmpty()) {
                        showInfo("No Java applications found")
                    } else {
                        val selected = Messages.showChooseDialog(
                            "Select application to use:",
                            "Running Java Applications",
                            apps,
                            apps.firstOrNull(),
                            AllIcons.Actions.Find
                        )
                        if (selected >= 0) {
                            val vm = vms[selected]
                            // Extract app name from display name
                            val displayName = vm.displayName()
                            val appName = when {
                                displayName.contains("vernyomas", ignoreCase = true) -> "vernyomas"
                                displayName.contains(".jar") -> {
                                    displayName.substringAfter("/").substringBefore(".jar")
                                }
                                displayName.contains(" ") -> {
                                    displayName.substringBefore(" ")
                                }
                                else -> displayName
                            }

                            // Add to dropdown if not already present
                            var found = false
                            for (i in 0 until appNameSelector.itemCount) {
                                if (appNameSelector.getItemAt(i) == appName) {
                                    found = true
                                    break
                                }
                            }
                            if (!found) {
                                appNameSelector.addItem(appName)
                            }
                            appNameSelector.selectedItem = appName
                            saveCurrentState()
                        }
                    }
                }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    showError("Failed to list applications: ${e.message}")
                }
            }
        }
    }

    private fun loadConfigs() {
        configSelector.removeAllItems()
        configSelector.addItem(SavedConfig("-- New Configuration --", "", ""))
        configService.getConfigs().forEach { config ->
            configSelector.addItem(config)
        }
    }

    private fun loadConfig(config: SavedConfig) {
        if (config.name == "-- New Configuration --") {
            clearForm()
        } else {
            configNameField.text = config.name

            // Add to dropdown if not already present and select it
            var found = false
            for (i in 0 until appNameSelector.itemCount) {
                if (appNameSelector.getItemAt(i) == config.appName) {
                    found = true
                    break
                }
            }
            if (!found && config.appName.isNotEmpty()) {
                appNameSelector.addItem(config.appName)
            }
            appNameSelector.selectedItem = config.appName

            bindContextField.text = config.bindExpression
        }
    }

    private fun clearForm() {
        configNameField.text = ""
        appNameSelector.selectedItem = "vernyomas"
        bindContextField.text = ""  // No default value
    }

    private fun restoreLastState() {
        val (lastConfig, lastAppName, lastBindExpr) = configService.getCurrentState()

        // Restore last selected configuration
        if (lastConfig != null) {
            for (i in 0 until configSelector.itemCount) {
                val item = configSelector.getItemAt(i)
                if (item.name == lastConfig) {
                    configSelector.selectedItem = item
                    break
                }
            }
        }

        // If no config was selected, restore field values
        val selectedConfig = configSelector.selectedItem as? SavedConfig
        if (selectedConfig?.name == "-- New Configuration --" || lastConfig == null) {
            appNameSelector.selectedItem = lastAppName
            bindContextField.text = lastBindExpr
        }
    }

    private fun saveCurrentState() {
        val currentConfig = (configSelector.selectedItem as? SavedConfig)?.name
        val currentAppName = (appNameSelector.selectedItem as? String) ?: ""
        val currentBindExpr = bindContextField.text.trim()
        configService.saveCurrentState(currentConfig, currentAppName, currentBindExpr)

        // Save custom app names
        val customNames = mutableListOf<String>()
        for (i in 0 until appNameSelector.itemCount) {
            appNameSelector.getItemAt(i)?.let { customNames.add(it) }
        }
        configService.saveCustomAppNames(customNames)
    }

    private fun updateStatus(status: String) {
        ApplicationManager.getApplication().invokeLater {
            statusLabel.text = status
            statusLabel.foreground = when {
                status.contains("Connected") -> Color(0, 128, 0)
                status.contains("failed") || status.contains("not found") -> Color.RED
                else -> UIManager.getColor("Label.foreground")
            }
        }
    }

    private fun showInfo(message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Java REPL")
            .createNotification(message, NotificationType.INFORMATION)
            .notify(project)
    }

    private fun showError(message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Java REPL")
            .createNotification(message, NotificationType.ERROR)
            .notify(project)
    }

    // Custom renderer for config dropdown
    private class ConfigListCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (value is SavedConfig && component is JLabel) {
                component.text = value.name
                if (value.name == "-- New Configuration --") {
                    component.font = component.font.deriveFont(Font.ITALIC)
                }
            }
            return component
        }
    }
}

/**
 * Service for persisting run configurations
 */
@Service(Service.Level.PROJECT)
@State(
    name = "JavaReplRunConfigurations",
    storages = [Storage("javaReplRunConfigs.xml")]
)
class RunConfigService(private val project: Project) : PersistentStateComponent<RunConfigService.State> {

    data class State(
        var configurations: MutableList<RunConfigPanel.SavedConfig> = mutableListOf(),
        var customAppNames: MutableList<String> = mutableListOf(),
        var lastSelectedConfig: String? = null,
        var currentConfigName: String = "",
        var currentAppName: String = "vernyomas",
        var currentBindExpression: String = ""
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    fun saveConfig(config: RunConfigPanel.SavedConfig) {
        // Remove existing config with same name
        myState.configurations.removeIf { it.name == config.name }
        myState.configurations.add(config)
    }

    fun deleteConfig(name: String) {
        myState.configurations.removeIf { it.name == name }
    }

    fun getConfigs(): List<RunConfigPanel.SavedConfig> {
        return myState.configurations.toList()
    }

    fun saveCustomAppNames(names: List<String>) {
        myState.customAppNames.clear()
        myState.customAppNames.addAll(names)
    }

    fun getCustomAppNames(): List<String> {
        return myState.customAppNames.toList()
    }

    fun saveCurrentState(configName: String?, appName: String, bindExpression: String) {
        myState.lastSelectedConfig = configName
        myState.currentConfigName = configName ?: ""
        myState.currentAppName = appName
        myState.currentBindExpression = bindExpression
    }

    fun getCurrentState(): Triple<String?, String, String> {
        return Triple(
            myState.lastSelectedConfig,
            myState.currentAppName,
            myState.currentBindExpression
        )
    }

    companion object {
        @JvmStatic fun getInstance(project: Project): RunConfigService = project.service()
    }
}
