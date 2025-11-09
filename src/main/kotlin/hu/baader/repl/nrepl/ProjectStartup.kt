package hu.baader.repl.nrepl

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import hu.baader.repl.settings.PluginSettingsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class ProjectStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        val settings = PluginSettingsState.getInstance().state
        if (settings.autoConnect) {
            val service = NreplService.getInstance(project)
            // Start connection in background
            project.getService(CoroutineScope::class.java)?.launch {
                try {
                    service.connectAsync()
                } catch (_: Throwable) {
                    // Ignore connection errors on startup
                }
            }
        }
    }
}