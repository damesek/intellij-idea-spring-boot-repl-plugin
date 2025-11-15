package hu.baader.repl.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.components.Service

@Service(Service.Level.APP)
@State(name = "JavaOverNreplSettings", storages = [Storage("java-over-nrepl.xml")])
class PluginSettingsState : PersistentStateComponent<PluginSettingsState.State> {

    data class State(
        var host: String = "127.0.0.1",
        var port: Int = 5557,
        var autoConnect: Boolean = true,
        var mode: String = "JAVA", // "JAVA" | "CLOJURE" (későbbre – most a JAVA kell)
        var agentJarPath: String = "",
        var agentPort: Int = 5557,
        var agentMavenVersion: String = DEFAULT_AGENT_VERSION,
        var importAliases: MutableList<ImportAlias> = mutableListOf()
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        const val DEFAULT_AGENT_VERSION = "0.7.1"
        @JvmStatic
        fun getInstance(): PluginSettingsState = service()
    }
}

data class ImportAlias(
    var alias: String = "",
    var fqn: String = "",
    var enabled: Boolean = true
)
