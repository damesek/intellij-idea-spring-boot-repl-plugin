package hu.baader.repl.ui

import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import hu.baader.repl.settings.SecretStore

/** Stores request metadata in project state and request contents in PasswordSafe. */
@Service(Service.Level.PROJECT)
@State(name = "JavaReplHttpRequests", storages = [Storage("javaReplHttpRequests.xml")])
class HttpRequestService(private val project: Project) : PersistentStateComponent<HttpRequestService.State> {

    data class State(
        var requests: MutableList<HttpRequestCase> = mutableListOf(),
        var lastSelectedId: String? = null
    )

    private val gson = Gson()
    private val logger = Logger.getInstance(HttpRequestService::class.java)
    @Volatile private var myState = State()

    private fun secretKey(id: String) = "http:" + project.locationHash + ":" + id
    @Synchronized override fun getState(): State = State(
        myState.requests.map { it.deepCopy().apply { url = ""; body = ""; headers.clear() } }.toMutableList(),
        myState.lastSelectedId
    )

    @Synchronized override fun loadState(state: State) {
        myState = state
        val legacy = state.requests.filter { it.url.isNotBlank() }.toList()
        ApplicationManager.getApplication().executeOnPooledThread {
            legacy.forEach { request -> synchronized(this) {
                if (myState === state && state.requests.any { it === request }) {
                    runCatching {
                        if (SecretStore.read(secretKey(request.id)).isBlank())
                            SecretStore.write(secretKey(request.id), gson.toJson(request))
                    }.onFailure { logger.warn("HTTP credential migration failed") }
                }
            } }
        }
    }

    @Synchronized fun getRequests(): List<HttpRequestCase> = myState.requests.map { stored ->
        try {
            val protected = SecretStore.read(secretKey(stored.id))
            if (protected.isBlank()) stored.deepCopy() else gson.fromJson(protected, HttpRequestCase::class.java) ?: stored.deepCopy()
        } catch (e: Exception) {
            logger.warn("HTTP case could not be read from PasswordSafe")
            stored.deepCopy()
        }
    }

    @Synchronized fun saveCase(case: HttpRequestCase) {
        val deepCopy = case.deepCopy()
        SecretStore.write(secretKey(deepCopy.id), gson.toJson(deepCopy))
        val idx = myState.requests.indexOfFirst { it.id == deepCopy.id }
        if (idx >= 0) {
            myState.requests[idx] = deepCopy
        } else {
            myState.requests.add(deepCopy)
        }
    }

    @Synchronized fun deleteCase(id: String?) {
        if (id.isNullOrBlank()) return
        runCatching { SecretStore.write(secretKey(id), "") }.onFailure {
            logger.warn("Removed HTTP case credentials could not be cleared from PasswordSafe")
        }
        myState.requests.removeIf { it.id == id }
        if (myState.lastSelectedId == id) {
            myState.lastSelectedId = null
        }
    }

    @Synchronized fun rememberLastSelected(id: String?) {
        myState.lastSelectedId = id
    }

    @Synchronized fun getLastSelectedId(): String? = myState.lastSelectedId

    companion object {
        @JvmStatic
        fun getInstance(project: Project): HttpRequestService = project.service()
    }
}
