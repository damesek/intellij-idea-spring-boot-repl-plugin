package hu.baader.repl.runner

import com.intellij.execution.RunConfigurationExtension
import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.SettingsEditorFragment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.util.Key
import com.intellij.ui.components.JBCheckBox
import hu.baader.repl.nrepl.NreplService
import org.jdom.Element
import java.nio.file.Path
import javax.swing.JComponent

/** Java's extension point requires this exact superclass and the Java plugin classloader. */
class SpringBootReplRunConfigurationExtension : RunConfigurationExtension() {
    companion object {
        private val ENABLED = Key.create<Boolean>("SPRING_BOOT_REPL_ENABLED")
        private val ENDPOINT = Key.create<Path>("SPRING_BOOT_REPL_ENDPOINT")
        private fun enabled(c: RunConfigurationBase<*>) = c.getCopyableUserData(ENABLED) == true
    }
    override fun isApplicableFor(configuration: RunConfigurationBase<*>): Boolean =
        configuration !is SpringBootReplRunConfiguration &&
            (configuration is ApplicationConfiguration || configuration.type.id == "SpringBootApplicationConfigurationType")

    override fun isEnabledFor(applicableConfiguration: RunConfigurationBase<*>, runnerSettings: RunnerSettings?) = enabled(applicableConfiguration)

    override fun <T : RunConfigurationBase<*>> updateJavaParameters(configuration: T, params: JavaParameters, runnerSettings: RunnerSettings?) {
        configuration.putUserData(ENDPOINT, null)
        if (isApplicableFor(configuration) && enabled(configuration))
            configuration.putUserData(ENDPOINT, ReplRunSupport.prepare(configuration.project, params))
    }

    override fun attachToProcess(configuration: RunConfigurationBase<*>, handler: ProcessHandler, settings: RunnerSettings?) {
        configuration.getUserData(ENDPOINT)?.let { NreplService.getInstance(configuration.project).connectProcess(it, handler) }
    }

    override fun readExternal(configuration: RunConfigurationBase<*>, element: Element) {
        configuration.putCopyableUserData(ENABLED, element.getChild("springBootRepl")?.getAttributeValue("enabled") == "true")
    }
    override fun writeExternal(configuration: RunConfigurationBase<*>, element: Element) {
        element.removeChildren("springBootRepl")
        if (enabled(configuration)) element.addContent(Element("springBootRepl").setAttribute("enabled", "true"))
    }
    override fun getEditorTitle() = "Spring Boot REPL"
    override fun <P : RunConfigurationBase<*>> createEditor(configuration: P): SettingsEditor<P> =
        object : SettingsEditor<P>() {
            private val checkbox = JBCheckBox("Enable Spring Boot REPL")
            override fun resetEditorFrom(s: P) { checkbox.isSelected = enabled(s) }
            override fun applyEditorTo(s: P) { s.putCopyableUserData(ENABLED, checkbox.isSelected) }
            override fun createEditor(): JComponent = checkbox
        }

    // Modern Spring Boot/Application editors use fragments; keep the checkbox visible by default.
    override fun <P : RunConfigurationBase<*>> createFragments(configuration: P): List<SettingsEditor<P>> {
        val fragment = SettingsEditorFragment<P, JBCheckBox>(
            "springBootRepl", "Enable Spring Boot REPL", "Java", JBCheckBox("Enable Spring Boot REPL"),
            { s, component -> component.isSelected = enabled(s) },
            { s, component -> s.putCopyableUserData(ENABLED, component.isSelected) },
            { true }
        )
        fragment.isCanBeHidden = false
        return listOf(fragment)
    }
}
