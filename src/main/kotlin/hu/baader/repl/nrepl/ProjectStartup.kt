package hu.baader.repl.nrepl

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import hu.baader.repl.settings.PluginSettingsState

class ProjectStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        val settings = PluginSettingsState.getInstance().state
        if (settings.autoConnect && settings.endpointFile.isNotBlank()) NreplService.getInstance(project).connectAsync()
    }
}
