package hu.baader.repl.runner

import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.JavaCommandLineState
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.project.Project
import hu.baader.repl.nrepl.NreplService
import java.nio.file.Path

/** Retains existing saved configurations; new users can enable REPL in their normal Spring Boot config. */
class SpringBootReplRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    ApplicationConfiguration(name, project, factory) {
    override fun getState(executor: Executor, environment: ExecutionEnvironment): JavaCommandLineState =
        SpringBootReplCommandLineState(this, environment)
}
class SpringBootReplCommandLineState(configuration: SpringBootReplRunConfiguration, environment: ExecutionEnvironment) :
    ApplicationConfiguration.JavaApplicationCommandLineState<SpringBootReplRunConfiguration>(configuration, environment) {
    private var endpoint: Path? = null
    override fun createJavaParameters(): JavaParameters = super.createJavaParameters().also {
        endpoint = ReplRunSupport.prepare(environment.project, it)
    }
    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult =
        super.execute(executor, runner).also { result ->
            endpoint?.let { NreplService.getInstance(environment.project).connectProcess(it, result.processHandler) }
        }
}
