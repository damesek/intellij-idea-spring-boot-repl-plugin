package hu.baader.repl.runner

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.project.Project

class SpringBootReplConfigurationFactory(type: ConfigurationType) : ConfigurationFactory(type) {
    override fun createTemplateConfiguration(project: Project): RunConfiguration {
        return SpringBootReplRunConfiguration(project, this, "Spring Boot Debug REPL and MCP")
    }

    override fun getId(): String = "SpringBootReplRunConfigurationFactory"
}
