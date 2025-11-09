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

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    fun entries(): MutableList<String> = state.entries

    fun add(entry: String) {
        if (entry.isBlank()) return
        state.entries.add(entry)
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

