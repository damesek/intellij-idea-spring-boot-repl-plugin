package hu.baader.repl.mcp

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import hu.baader.repl.settings.SecretStore

/** Only the successful listening port is stored in the private project workspace. */
@Service(Service.Level.PROJECT)
@State(name = "JavaReplMcpEndpoint", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
internal class McpEndpointSettings(project: Project) : PersistentStateComponent<McpEndpointSettings.State> {
    data class State(var port: Int = 0)

    @Volatile private var myState = State()
    private val credentials = McpEndpointCredentials("mcp:" + project.locationHash)
    val port: Int get() = myState.port

    override fun getState(): State = myState.copy()
    override fun loadState(state: State) {
        myState = State(state.port.takeIf { it in 0..65535 } ?: 0)
    }
    fun rememberPort(port: Int) {
        require(port in 1..65535) { "Remember the actual listening port" }
        myState = State(port)
    }

    /** PasswordSafe access is blocking; the MCP lifecycle worker calls this off the EDT. */
    fun token(): String = credentials.token()

    companion object { fun getInstance(project: Project): McpEndpointSettings = project.service() }
}

internal class McpEndpointCredentials(
    private val key: String,
    private val read: (String) -> String = SecretStore::read,
    private val write: (String, String) -> Unit = SecretStore::write
) {
    @Synchronized fun token(): String = read(key).ifBlank {
        McpRouter.secret().also { write(key, it) }
    }
}
