package hu.baader.repl.runner

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.icons.AllIcons
import javax.swing.Icon

class SpringBootReplConfigurationType : ConfigurationType {
    override fun getDisplayName(): String = "Spring Boot REPL"

    override fun getConfigurationTypeDescription(): String = "Run a Spring Boot application with the REPL agent attached."

    override fun getIcon(): Icon = AllIcons.RunConfigurations.Application

    override fun getId(): String = "SpringBootReplRunConfiguration"

    override fun getConfigurationFactories(): Array<ConfigurationFactory> {
        return arrayOf(SpringBootReplConfigurationFactory(this))
    }
}
