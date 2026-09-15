package hu.baader.repl.runner

import com.intellij.execution.configurations.JavaParameters
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import hu.baader.repl.agent.AgentJarResolver
import hu.baader.repl.settings.PluginSettingsState
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

object ReplRunSupport {
    fun prepare(project: Project, parameters: JavaParameters): Path? {
        return try {
            val jar = AgentJarResolver.resolve() ?: error("Agent JAR not found; rebuild the plugin or configure its path in Settings.")
            val endpoint = Files.createTempFile("sb-repl-endpoint-", ".properties")
            endpoint.toFile().deleteOnExit()
            val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(endpoint.toString().toByteArray(Charsets.UTF_8))
            val port = PluginSettingsState.getInstance().state.agentPort.coerceIn(0, 65535)
            parameters.vmParametersList.add("-javaagent:" + jar.absolutePath + "=port=" + port + ",endpoint64=" + encoded)
            endpoint
        } catch (e: ProcessCanceledException) { throw e }
        catch (e: Exception) {
            NotificationGroupManager.getInstance().getNotificationGroup("Spring Boot REPL")
                .createNotification("Application starts without REPL: " + e.message, NotificationType.WARNING).notify(project)
            null
        }
    }
}
