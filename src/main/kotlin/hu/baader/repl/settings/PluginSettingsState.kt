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
        var endpointFile: String = "",
        var host: String = "127.0.0.1",
        var port: Int = 5557,
        var autoConnect: Boolean = false,
        var persistHistory: Boolean = false,
        var mode: String = "JAVA", // "JAVA" | "CLOJURE" (későbbre – most a JAVA kell)
        var agentJarPath: String = "",
        var agentPort: Int = 0,
        var agentMavenVersion: String = DEFAULT_AGENT_VERSION,
        var importAliases: MutableList<ImportAlias> = mutableListOf(),
        var showInlineResultPopupForCaretEval: Boolean = true,
        // AI / LLM settings
        var aiProvider: String = AI_PROVIDER_OPENAI, // "openai" | "mcp-offline" (placeholder)
        var openAiApiKey: String = "",
        var openAiModel: String = "gpt-4o",
        var openAiBaseUrl: String = "https://api.openai.com/v1"
    )

    @Volatile internal var legacyApiKey: String = ""
    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        legacyApiKey = state.openAiApiKey
        state.openAiApiKey = ""
        myState = state
        if (legacyApiKey.isNotBlank()) com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { SecretStore.aiKey() }.onFailure {
                com.intellij.openapi.diagnostic.Logger.getInstance(PluginSettingsState::class.java).warn("API key migration to PasswordSafe failed; open REPL Settings to retry")
            }
        }
    }

    companion object {
        const val DEFAULT_AGENT_VERSION = "0.10.0"
        const val AI_PROVIDER_OPENAI = "openai"
        const val AI_PROVIDER_MCP_OFFLINE = "mcp-offline"
        @JvmStatic
        fun getInstance(): PluginSettingsState = service()
    }
}

data class ImportAlias(
    var alias: String = "",
    var fqn: String = "",
    var enabled: Boolean = true
)
