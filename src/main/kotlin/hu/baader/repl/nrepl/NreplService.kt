package hu.baader.repl.nrepl

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.Disposable
import hu.baader.repl.settings.PluginSettingsState
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class NreplService(private val project: Project) {

    private val settings get() = PluginSettingsState.getInstance().state
    @Volatile private var client: NreplClient? = null
    private val connecting = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private val springBound = AtomicBoolean(false)

    private val listeners = mutableListOf<(Map<String, String>) -> Unit>()

    fun onMessage(listener: (Map<String, String>) -> Unit): Disposable {
        listeners += listener
        return Disposable { listeners.remove(listener) }
    }

    fun isConnected(): Boolean = connected.get()
    fun isSpringBound(): Boolean = springBound.get()

    fun connectAsync() {
        if (connected.get() || connecting.getAndSet(true)) return
        springBound.set(false)

        val c = NreplClient(settings.host, settings.port)
        c.onMessage { msg -> 
            listeners.forEach { it(msg) }
        }
        
        try {
            c.connect()
            client = c
            connected.set(true)
        } catch (t: Throwable) {
            client = null
            connected.set(false)
            throw t
        } finally {
            connecting.set(false)
        }
    }

    fun disconnect() {
        client?.close()
        client = null
        connected.set(false)
        springBound.set(false)
    }

    fun reconnect() {
        disconnect()
        connectAsync()
    }

    fun evalJava(code: String) {
        client?.evalJava(code)
            ?: throw IllegalStateException("Not connected to nREPL")
    }
    
    fun evalClojure(code: String) {
        client?.evalClojure(code)
            ?: throw IllegalStateException("Not connected to nREPL")
    }

    fun bindSpring(expr: String? = null, onResult: ((String)->Unit)? = null, onError: ((String)->Unit)? = null) {
        val extra = mutableMapOf<String,String>()
        if (!expr.isNullOrBlank()) extra["expr"] = expr
        val c = client ?: throw IllegalStateException("Not connected to nREPL")
        c.sendOp("bind-spring", extra) { m ->
            when {
                m["err"] != null -> onError?.invoke(m["err"]!!) ?: Unit
                m["value"] != null -> {
                    val v = m["value"]!!.trim()
                    if (v.equals("true", ignoreCase = true)) springBound.set(true)
                    onResult?.invoke(v)
                }
            }
        }
    }

    fun hotSwap(code: String, onResult: ((String)->Unit)? = null, onError: ((String)->Unit)? = null) {
        val c = client ?: throw IllegalStateException("Not connected to nREPL")
        c.sendOp("class-reload", mapOf("code" to code)) { m ->
            when {
                m["err"] != null -> onError?.invoke(m["err"]!!)
                m["value"] != null -> onResult?.invoke(m["value"]!!)
                else -> onResult?.invoke("HotSwap completed")
            }
        }
    }

    fun listSpringBeans(onResult: (List<BeanInfo>)->Unit, onError: ((String)->Unit)? = null) {
        val c = client ?: throw IllegalStateException("Not connected to nREPL")
        c.sendOp("list-beans", emptyMap()) { m ->
            when {
                m["err"] != null -> onError?.invoke(m["err"]!!)
                m["value"] != null -> {
                    val beans = m["value"]!!
                        .lines()
                        .mapNotNull { line ->
                            val trimmed = line.trim()
                            if (trimmed.isEmpty()) return@mapNotNull null
                            val parts = trimmed.split('\t')
                            BeanInfo(
                                name = parts.getOrNull(0)?.trim().orEmpty(),
                                className = parts.getOrNull(1)?.trim().orEmpty()
                            )
                        }
                    onResult(beans)
                }
                else -> onResult(emptyList())
            }
        }
    }

    fun listAgentSnapshots(onResult: (String)->Unit, onError: ((String)->Unit)? = null) {
        val c = client ?: throw IllegalStateException("Not connected to nREPL")
        c.sendOp("snapshots", emptyMap()) { m ->
            when {
                m["err"] != null -> onError?.invoke(m["err"]!!)
                m["value"] != null -> onResult(m["value"]!!)
            }
        }
    }

    fun deleteSnapshot(name: String, onResult: ((String)->Unit)? = null, onError: ((String)->Unit)? = null) {
        val c = client ?: throw IllegalStateException("Not connected to nREPL")
        c.sendOp("snapshot-delete", mapOf("name" to name)) { m ->
            when {
                m["err"] != null -> onError?.invoke(m["err"]!!)
                m["value"] != null -> onResult?.invoke(m["value"]!!)
            }
        }
    }

    fun snapshotPin(name: String, expr: String, onResult: ((String)->Unit)? = null, onError: ((String)->Unit)? = null) {
        val c = client ?: throw IllegalStateException("Not connected to nREPL")
        c.sendOp("snapshot-pin", mapOf("name" to name, "expr" to expr)) { m ->
            when {
                m["err"] != null -> onError?.invoke(m["err"]!!)
                m["value"] != null -> onResult?.invoke(m["value"]!!)
            }
        }
    }

    fun snapshotSaveJson(name: String, expr: String, onResult: ((String)->Unit)? = null, onError: ((String)->Unit)? = null) {
        val c = client ?: throw IllegalStateException("Not connected to nREPL")
        c.sendOp("snapshot-save-json", mapOf("name" to name, "expr" to expr)) { m ->
            when {
                m["err"] != null -> onError?.invoke(m["err"]!!)
                m["value"] != null -> onResult?.invoke(m["value"]!!)
            }
        }
    }

    fun snapshotMaterialize(name: String, typeFqn: String, target: String? = null, onResult: ((String)->Unit)? = null, onError: ((String)->Unit)? = null) {
        val c = client ?: throw IllegalStateException("Not connected to nREPL")
        val extra = mutableMapOf("name" to name, "type" to typeFqn)
        if (!target.isNullOrBlank()) extra["target"] = target
        c.sendOp("snapshot-materialize", extra) { m ->
            when {
                m["err"] != null -> onError?.invoke(m["err"]!!)
                m["value"] != null -> onResult?.invoke(m["value"]!!)
            }
        }
    }

    // Simplified snapshot operations
    fun snapshotSave(name: String, expr: String, onResult: ((String)->Unit)? = null, onError: ((String)->Unit)? = null) {
        val c = client ?: throw IllegalStateException("Not connected to nREPL")
        c.sendOp("snapshot-save", mapOf("name" to name, "expr" to expr)) { m ->
            when {
                m["err"] != null -> onError?.invoke(m["err"]!!)
                m["value"] != null -> onResult?.invoke(m["value"]!!)
            }
        }
    }

    fun snapshotLoad(name: String, varName: String? = null, onResult: ((String)->Unit)? = null, onError: ((String)->Unit)? = null) {
        val c = client ?: throw IllegalStateException("Not connected to nREPL")
        val extra = mutableMapOf("name" to name)
        if (!varName.isNullOrBlank()) extra["var"] = varName
        c.sendOp("snapshot-load", extra) { m ->
            when {
                m["err"] != null -> onError?.invoke(m["err"]!!)
                m["value"] != null -> onResult?.invoke(m["value"]!!)
            }
        }
    }

    fun snapshotListSimple(onResult: (List<String>)->Unit, onError: ((String)->Unit)? = null) {
        val c = client ?: throw IllegalStateException("Not connected to nREPL")
        c.sendOp("snapshot-list-simple", emptyMap()) { m ->
            when {
                m["err"] != null -> onError?.invoke(m["err"]!!)
                m["value"] != null -> {
                    val lines = m["value"]!!.lines().filter { it.isNotBlank() }
                    onResult(lines)
                }
            }
        }
    }

    fun snapshotInfo(name: String, onResult: (String)->Unit, onError: ((String)->Unit)? = null) {
        val c = client ?: throw IllegalStateException("Not connected to nREPL")
        c.sendOp("snapshot-info", mapOf("name" to name)) { m ->
            when {
                m["err"] != null -> onError?.invoke(m["err"]!!)
                m["value"] != null -> onResult(m["value"]!!)
            }
        }
    }

    data class BeanInfo(val name: String, val className: String)

    companion object {
        @JvmStatic
        fun getInstance(project: Project): NreplService = project.service()
    }
}
