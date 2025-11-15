package hu.baader.repl.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import java.awt.GridBagLayout
import java.awt.GridBagConstraints

class PluginSettingsConfigurable : SearchableConfigurable {
    private val state = PluginSettingsState.getInstance()
    private lateinit var panel: JPanel
    private lateinit var hostField: JBTextField
    private lateinit var portSpinner: JSpinner
    private lateinit var autoConnect: JBCheckBox
    private lateinit var agentJarField: TextFieldWithBrowseButton
    private lateinit var agentPortSpinner: JSpinner
    private lateinit var agentVersionField: JBTextField
    private lateinit var caretInlineResult: JBCheckBox

    override fun getId(): String = "hu.baader.repl.settings"
    override fun getDisplayName(): String = "Spring Boot REPL"

    override fun createComponent(): JComponent {
        hostField = JBTextField(state.state.host)
        portSpinner = JSpinner(SpinnerNumberModel(state.state.port, 1, 65535, 1))
        autoConnect = JBCheckBox("Auto-connect on project open", state.state.autoConnect)
        agentJarField = TextFieldWithBrowseButton().apply {
            text = state.state.agentJarPath
            addBrowseFolderListener(
                "Select Dev Runtime Agent JAR",
                null,
                null,
                FileChooserDescriptorFactory.createSingleFileDescriptor("jar")
            )
        }
        agentPortSpinner = JSpinner(SpinnerNumberModel(state.state.agentPort, 1, 65535, 1))
        agentVersionField = JBTextField(state.state.agentMavenVersion)
        caretInlineResult = JBCheckBox(
            "Show inline result popup for 'Evaluate at Caret'",
            state.state.showInlineResultPopupForCaretEval
        )

        panel = JPanel(GridBagLayout())
        val c = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            gridx = 0
            gridy = 0
        }
        
        fun row(label: String, comp: JComponent) {
            val l = javax.swing.JLabel(label)
            val lc = c.clone() as GridBagConstraints
            val rc = c.clone() as GridBagConstraints
            lc.weightx = 0.0
            lc.gridx = 0
            lc.gridwidth = 1
            rc.weightx = 1.0
            rc.gridx = 1
            rc.gridwidth = 1
            lc.gridy = panel.componentCount / 2
            rc.gridy = lc.gridy
            panel.add(l, lc)
            panel.add(comp, rc)
        }

        row("Host:", hostField)
        row("Port:", portSpinner)
        row("Agent JAR:", agentJarField)
        row("Agent Port:", agentPortSpinner)
        row("Agent Maven version:", agentVersionField)
        val full = GridBagConstraints().apply {
            gridx = 0
            gridy = 99
            gridwidth = 2
            fill = GridBagConstraints.HORIZONTAL
        }
        panel.add(autoConnect, full)
        val full2 = GridBagConstraints().apply {
            gridx = 0
            gridy = 100
            gridwidth = 2
            fill = GridBagConstraints.HORIZONTAL
        }
        panel.add(caretInlineResult, full2)

        return panel
    }

    override fun isModified(): Boolean {
        val s = state.state
        return hostField.text != s.host ||
                (portSpinner.value as Int) != s.port ||
                autoConnect.isSelected != s.autoConnect ||
                agentJarField.text != s.agentJarPath ||
                (agentPortSpinner.value as Int) != s.agentPort ||
                agentVersionField.text != s.agentMavenVersion ||
                caretInlineResult.isSelected != s.showInlineResultPopupForCaretEval
    }

    override fun apply() {
        val s = state.state
        s.host = hostField.text.trim()
        s.port = (portSpinner.value as Int)
        s.autoConnect = autoConnect.isSelected
        s.agentJarPath = agentJarField.text.trim()
        s.agentPort = (agentPortSpinner.value as Int)
        s.agentMavenVersion = agentVersionField.text.trim()
        s.showInlineResultPopupForCaretEval = caretInlineResult.isSelected
    }

    override fun reset() {
        val s = state.state
        hostField.text = s.host
        portSpinner.value = s.port
        autoConnect.isSelected = s.autoConnect
        agentJarField.text = s.agentJarPath
        agentPortSpinner.value = s.agentPort
        agentVersionField.text = s.agentMavenVersion
        caretInlineResult.isSelected = s.showInlineResultPopupForCaretEval
    }
}
