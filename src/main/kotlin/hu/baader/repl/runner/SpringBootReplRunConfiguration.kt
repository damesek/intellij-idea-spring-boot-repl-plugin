package hu.baader.repl.runner

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.JavaCommandLineState
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.ExecutionResult
import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ApplicationManager
import hu.baader.repl.nrepl.NreplService
import hu.baader.repl.settings.PluginSettingsState
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files

class SpringBootReplRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String
) : ApplicationConfiguration(name, project, factory) {

    override fun getState(executor: Executor, environment: ExecutionEnvironment): JavaCommandLineState {
        return SpringBootReplCommandLineState(this, environment)
    }
}

class SpringBootReplCommandLineState(
    configuration: SpringBootReplRunConfiguration,
    environment: ExecutionEnvironment
) : ApplicationConfiguration.JavaApplicationCommandLineState<SpringBootReplRunConfiguration>(configuration, environment) {

    override fun createJavaParameters(): JavaParameters {
        val params = super.createJavaParameters()
        val agentJar = findAgentJar()

        if (agentJar != null && agentJar.exists()) {
            val settings = PluginSettingsState.getInstance().state
            val port = settings.agentPort
            params.vmParametersList.add("-javaagent:${agentJar.absolutePath}=port=$port")
        } else {
            throw ExecutionException("Spring Boot REPL agent JAR not found. Please rebuild the plugin via './gradlew clean buildPlugin'.")
        }
        return params
    }

    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val result = super.execute(executor, runner)

        // After the Spring Boot app is started with the dev-runtime agent, auto-connect
        // to the nREPL server and bind Spring context so 'ctx' is ready without extra clicks.
        val project = environment.project
        val settings = PluginSettingsState.getInstance().state
        val service = NreplService.getInstance(project)

        // Align host/port with the agent arguments.
        settings.host = "127.0.0.1"
        val port = settings.agentPort
        settings.port = port

        ApplicationManager.getApplication().executeOnPooledThread {
            // Small delay to give the agent time to start nREPL.
            try {
                Thread.sleep(1500)
            } catch (_: InterruptedException) {
                return@executeOnPooledThread
            }
            if (service.isConnected()) return@executeOnPooledThread

            // Try to connect once; if it fails, the user can still click Connect manually.
            try {
                service.connectAsync { isJshell ->
                    ApplicationManager.getApplication().invokeLater {
                        // Best-effort auto-bind; if it fails, UI Bind action remains available.
                        service.bindSpring(
                            onResult = { v ->
                                if (v.equals("true", ignoreCase = true)) {
                                    try {
                                        service.eval(
                                            """
                                            try {
                                                Class<?> holder = Class.forName("com.baader.devrt.SpringContextHolder");
                                                java.lang.reflect.Method get = holder.getMethod("get");
                                                Object ctxObj = get.invoke(null);
                                                if (ctxObj != null) {
                                                    var ctx = (org.springframework.context.ApplicationContext) ctxObj;
                                                    System.out.println("Spring context variable 'ctx' initialized in JShell session.");
                                                }
                                            } catch (Throwable t) {
                                                System.out.println("ctx init skipped: " + t);
                                            }
                                            return null;
                                            """.trimIndent(),
                                            onResult = { /* silent */ },
                                            onError = { /* silent */ }
                                        )
                                    } catch (_: Exception) {
                                        // Silent: ctx init is best-effort
                                    }
                                }
                            },
                            onError = { /* silent */ }
                        )
                    }
                }
            } catch (_: Throwable) {
                // Silent: user can still press Connect manually if needed.
            }
        }

        return result
    }

    private fun findAgentJar(): File? {
        // The agent JAR is bundled as a resource within the plugin JAR.
        // We need to extract it to a temporary file to use with -javaagent.
        val resourcePath = "agent/dev-runtime-agent.jar"
        val agentUrl = SpringBootReplRunConfiguration::class.java.classLoader.getResource(resourcePath) ?: return null

        return try {
            val tempFile = Files.createTempFile("dev-runtime-agent", ".jar").toFile()
            tempFile.deleteOnExit() // Ensure the temporary file is cleaned up

            agentUrl.openStream().use { input: InputStream ->
                FileOutputStream(tempFile).use { output: FileOutputStream ->
                    input.copyTo(output)
                }
            }
            tempFile
        } catch (e: Exception) {
            null
        }
    }
}
