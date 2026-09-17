package hu.baader.repl.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import java.awt.GridLayout
import javax.swing.*

class PluginSettingsConfigurable : SearchableConfigurable {
    private val settings get() = PluginSettingsState.getInstance().state
    private lateinit var endpoint: TextFieldWithBrowseButton
    private lateinit var agent: TextFieldWithBrowseButton
    private lateinit var port: JSpinner
    private lateinit var auto: JBCheckBox
    private lateinit var history: JBCheckBox
    private lateinit var caret: JBCheckBox
    private lateinit var provider: JComboBox<String>
    private lateinit var key: JBPasswordField
    private lateinit var model: JBTextField
    private lateinit var base: JBTextField
    private var initialKey = ""
    override fun getId() = "hu.baader.repl.settings"
    override fun getDisplayName() = "Spring Boot Debug REPL and MCP"
    override fun createComponent(): JComponent {
        endpoint = browse("properties", "Select REPL Endpoint")
        agent = browse("jar", "Select Agent JAR")
        port = JSpinner(SpinnerNumberModel(0, 0, 65535, 1))
        auto = JBCheckBox("Connect the selected endpoint when the project opens")
        history = JBCheckBox("Persist REPL history (may contain application data)")
        caret = JBCheckBox("Show results beside source for Evaluate at Caret")
        provider = JComboBox(arrayOf(PluginSettingsState.AI_PROVIDER_MCP_OFFLINE, PluginSettingsState.AI_PROVIDER_OPENAI))
        key = JBPasswordField(); model = JBTextField(); base = JBTextField()
        val panel = JPanel(GridLayout(0, 2, 8, 8))
        fun row(label: String, component: JComponent) { panel.add(JLabel(label)); panel.add(component) }
        row("Endpoint (optional; run configs discover their own):", endpoint)
        row("Agent JAR (empty = bundled):", agent)
        row("Agent port (0 = allocated by the OS):", port)
        row("", auto); row("", history); row("", caret)
        row("AI (mcp-offline = disabled):", provider)
        row("API key (IDE PasswordSafe; OPENAI_API_KEY fallback):", key)
        row("Model:", model); row("Base URL:", base)
        reset()
        return panel
    }
    private fun browse(extension: String, title: String) = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(TextBrowseFolderListener(
            FileChooserDescriptorFactory.createSingleFileDescriptor(extension).withTitle(title)
        ))
    }
    override fun isModified(): Boolean {
        val s = settings
        return endpoint.text != s.endpointFile || agent.text != s.agentJarPath || port.value != s.agentPort ||
            auto.isSelected != s.autoConnect || history.isSelected != s.persistHistory || caret.isSelected != s.showInlineResultPopupForCaretEval ||
            provider.selectedItem != s.aiProvider || String(key.password) != initialKey || model.text != s.openAiModel || base.text != s.openAiBaseUrl
    }
    override fun apply() {
        val s = settings
        SecretStore.setAiKey(String(key.password).trim())
        initialKey = String(key.password).trim()
        s.openAiApiKey = ""
        s.endpointFile = endpoint.text.trim(); s.agentJarPath = agent.text.trim(); s.agentPort = port.value as Int
        s.autoConnect = auto.isSelected; s.persistHistory = history.isSelected; s.showInlineResultPopupForCaretEval = caret.isSelected
        s.aiProvider = provider.selectedItem as String; s.openAiModel = model.text.trim(); s.openAiBaseUrl = base.text.trim()
    }
    override fun reset() {
        val s = settings
        endpoint.text = s.endpointFile; agent.text = s.agentJarPath; port.value = s.agentPort
        auto.isSelected = s.autoConnect; history.isSelected = s.persistHistory; caret.isSelected = s.showInlineResultPopupForCaretEval
        provider.selectedItem = s.aiProvider; initialKey = SecretStore.aiKey(); key.text = initialKey
        model.text = s.openAiModel; base.text = s.openAiBaseUrl
    }
    override fun disposeUIResources() { if (::key.isInitialized) key.text = ""; initialKey = "" }
}
