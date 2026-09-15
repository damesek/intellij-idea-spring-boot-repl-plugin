package hu.baader.repl.history

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(name = "JavaOverNreplHistory", storages = [Storage("java-over-nrepl-history.xml")])
class ReplHistoryService(private val project: Project) : PersistentStateComponent<ReplHistoryService.State> {

    data class State(
        var entries: MutableList<String> = mutableListOf()
    )

    private var state = State()
    private val maxEntries = 200

    override fun getState(): State = if (hu.baader.repl.settings.PluginSettingsState.getInstance().state.persistHistory) State(state.entries.toMutableList()) else State()

    override fun loadState(state: State) {
        this.state = State(state.entries.takeLast(maxEntries).map { hu.baader.repl.protocol.SensitiveValues.redact(it).take(16000) }.toMutableList())
    }

    fun entries(): List<String> = state.entries.toList()

    fun add(entry: String) {
        if (entry.isBlank()) return
        state.entries.add(hu.baader.repl.protocol.SensitiveValues.redact(entry).take(16000))
        if (state.entries.size > maxEntries) {
            state.entries.removeAt(0)
        }
    }

    fun clear() {
        state.entries.clear()
    }

    companion object {
        @JvmStatic fun getInstance(project: Project): ReplHistoryService = project.service()
    }
}
