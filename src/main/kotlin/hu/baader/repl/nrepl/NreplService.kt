package hu.baader.repl.nrepl

import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import hu.baader.repl.protocol.EndpointFile
import hu.baader.repl.settings.PluginSettingsState
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Service(Service.Level.PROJECT)
class NreplService(private val project: Project) : Disposable {
    enum class State { DISCONNECTED, CONNECTING, SESSION_READY, WAITING_CONTEXT, READY, FAILED }
    @Volatile var state = State.DISCONNECTED
        private set
    @Volatile private var client: NreplClient? = null
    @Volatile private var endpoint: Path? = null
    @Volatile private var boundEpoch: String? = null
    private val generation = AtomicLong()
    private val listeners = CopyOnWriteArrayList<(Map<String, String>) -> Unit>()
    private val snippets = ConcurrentHashMap<String, String>()
    @Volatile private var debugSink: ((String) -> Unit)? = null
    val executionSelection = ExecutionSelection()
    private val executions = java.util.concurrent.atomic.AtomicInteger()
    fun isExecuting() = executions.get() > 0
    fun canExecute() = isConnected() && executionSelection.ready

    fun onMessage(listener: (Map<String, String>) -> Unit): Disposable {
        listeners.add(listener); return Disposable { listeners.remove(listener) }
    }
    private fun ui(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater { if (!project.isDisposed) action() }
    }
    private fun publish(message: Map<String, String>) = ui {
        listeners.forEach { listener ->
            try { listener(message) }
            catch (e: com.intellij.openapi.progress.ProcessCanceledException) { throw e }
            catch (e: Exception) { com.intellij.openapi.diagnostic.Logger.getInstance(NreplService::class.java).warn("REPL view update failed", e) }
        }
    }
    private fun change(next: State, detail: String = "") {
        state = next; publish(mapOf("op" to "connection", "state" to next.name, "detail" to detail))
    }
    fun isConnected() = client != null && state in setOf(State.SESSION_READY, State.WAITING_CONTEXT, State.READY)
    fun isSpringBound() = state == State.READY
    fun isJshellMode() = isConnected()

    fun connectAsync(onComplete: ((Boolean) -> Unit)? = null) {
        if (isConnected()) { ui { onComplete?.invoke(true) }; return }
        val configured = PluginSettingsState.getInstance().state.endpointFile
        val path = endpoint ?: configured.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        if (path == null) {
            change(State.FAILED, "Start an application with Enable Spring Boot Debug REPL and MCP, or select an agent endpoint file in Settings.")
            ui { onComplete?.invoke(false) }; return
        }
        connectEndpoint(path, { true }, onComplete)
    }

    fun connectProcess(path: Path, process: ProcessHandler) {
        connectEndpoint(path, { !process.isProcessTerminated && !process.isProcessTerminating })
        process.addProcessListener(object : ProcessAdapter() {
            override fun processTerminated(event: ProcessEvent) {
                if (endpoint == path) disconnect()
                runCatching { Files.deleteIfExists(path) }
            }
        })
        if (process.isProcessTerminated && endpoint == path) disconnect()
    }

    fun connectEndpoint(path: Path, alive: () -> Boolean = { true }, onComplete: ((Boolean) -> Unit)? = null) {
        disconnect()
        endpoint = path
        val run = generation.incrementAndGet()
        change(State.CONNECTING)
        ApplicationManager.getApplication().executeOnPooledThread {
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(90)
            var candidate: NreplClient? = null
            var connectedCallback = false
            try {
                while (!Files.exists(path) || Files.size(path) == 0L) {
                    check(generation.get() == run && alive()) { "Application stopped before its REPL became available" }
                    check(System.nanoTime() < deadline) { "Timed out waiting for the REPL endpoint file" }
                    Thread.sleep(150)
                }
                check(generation.get() == run && alive()) { "Connection cancelled" }
                val address = EndpointFile.read(path)
                candidate = NreplClient(java.net.InetAddress.getLoopbackAddress().hostAddress, address.port(), address.token())
                val c = candidate
                c.onMessage { message ->
                    if (generation.get() != run) return@onMessage
                    if (message["op"] == "describe" && state == State.READY && message["context-epoch"] != boundEpoch)
                        change(State.SESSION_READY, "Spring context changed; reset the session.")
                    if (message["op"] == "bind-spring" && message["value"] == "true") {
                        boundEpoch = message["context-epoch"]; change(State.READY)
                    }
                    val snippet = message["id"]?.let { snippets.remove(it) }
                    publish(if (snippet == null) message else message + ("snippet" to snippet))
                    debugLog(message["op"].orEmpty() + ": " + message["status"].orEmpty())
                }
                c.connect(address.pid())
                if (generation.get() != run || !alive()) { c.close(); return@executeOnPooledThread }
                client = c
                c.onDisconnect = { reason ->
                    if (client === c) {
                        client = null; snippets.clear(); executionSelection.reset(); executions.set(0)
                        change(State.DISCONNECTED, reason)
                    }
                }
                change(State.SESSION_READY)
                refreshExecutionPolicy()
                connectedCallback = true
                ui { onComplete?.invoke(true) }
                change(State.WAITING_CONTEXT)
                while (generation.get() == run && alive() && client === c && System.nanoTime() < deadline) {
                    val response = try {
                        c.request("bind-spring").get(10, java.util.concurrent.TimeUnit.SECONDS)
                    } catch (_: java.util.concurrent.TimeoutException) {
                        if (generation.get() == run && client === c) change(State.SESSION_READY, "Spring bind is queued behind another operation; the Java REPL remains connected.")
                        return@executeOnPooledThread
                    }
                    if (response["err"] == null && response["value"] == "true") {
                        boundEpoch = response["context-epoch"]
                        change(State.READY); return@executeOnPooledThread
                    }
                    Thread.sleep(500)
                }
                if (generation.get() == run && client === c) change(State.SESSION_READY, "Java REPL connected; Spring context is not ready. Use Bind after application startup.")
            } catch (e: Exception) {
                candidate?.close()
                if (generation.get() == run) {
                    client = null
                    change(State.FAILED, e.cause?.message ?: e.message ?: "Connection failed")
                    if (!connectedCallback) ui { onComplete?.invoke(false) }
                }
            }
        }
    }

    fun disconnect() {
        generation.incrementAndGet()
        executionSelection.reset(); executions.set(0)
        val c = client; client = null; boundEpoch = null; c?.close(); snippets.clear()
        change(State.DISCONNECTED)
    }
    fun debuggerTarget(): Pair<String, Long>? = client?.debuggerTarget()
    data class McpTarget(val endpoint: Path, val pid: Long, val connection: Long)
    fun mcpTarget(): McpTarget? {
        val run = generation.get()
        val active = client ?: return null
        val path = endpoint ?: return null
        val pid = active.debuggerTarget()?.second ?: return null
        return if (isConnected() && client === active && generation.get() == run) McpTarget(path, pid, run) else null
    }
    fun reconnect() { disconnect(); connectAsync() }
    override fun dispose() { disconnect(); listeners.clear(); debugSink = null }
    fun setDebugSink(sink: ((String) -> Unit)?) { debugSink = sink }
    fun debugLog(message: String) = ui { debugSink?.invoke(message) }

    fun request(op: String, extra: Map<String, String> = emptyMap(),
                onResult: ((Map<String, String>) -> Unit)? = null, onError: ((String) -> Unit)? = null,
                id: String = UUID.randomUUID().toString()) {
        val c = client
        if (c == null) {
            snippets.remove(id)
            ui { onError?.invoke("REPL is disconnected. Start or connect an application first.") }
            return
        }
        val executes = op in setOf("eval", "java-eval", "case/run", "case/run-batch", "watch/refresh")
        if (executes && !executionSelection.ready) {
            snippets.remove(id)
            ui { onError?.invoke("Execution settings are not confirmed. Wait for the mode change or use Session > Refresh execution settings.") }
            return
        }
        if (executes) { executions.incrementAndGet(); publish(mapOf("op" to "activity", "running" to "true")) }
        c.request(op, extra, id).whenComplete { response, error ->
            if (executes && client === c) {
                executions.updateAndGet { (it - 1).coerceAtLeast(0) }
                publish(mapOf("op" to "activity", "running" to isExecuting().toString()))
            }
            if (error != null) {
                snippets.remove(id)
                ui { onError?.invoke(error.cause?.message ?: error.message ?: "Request failed") }
            } else ui {
                if (client !== c) return@ui
                val failure = response["err"]
                if (failure != null || response["status"].orEmpty().lines().contains("error")) {
                    if (failure.orEmpty().contains("context changed", ignoreCase = true)) change(State.SESSION_READY, "Context changed: reset the session")
                    onError?.invoke(failure ?: "Request failed")
                } else onResult?.invoke(response)
            }
        }
    }
    fun refreshExecutionPolicy() = updateExecutionPolicy(null)
    fun configureExecution(policy: ExecutionSelection.Policy) {
        if (isExecuting() || executionSelection.pending) return
        updateExecutionPolicy(policy)
    }
    private fun updateExecutionPolicy(policy: ExecutionSelection.Policy?) {
        if (!isConnected()) return
        val ticket = executionSelection.begin(policy)
        publish(mapOf("op" to "execution/selection", "pending" to "true"))
        request(if (policy == null) "execution/policy" else "execution/configure", policy?.arguments().orEmpty(), { response ->
            try {
                if (executionSelection.accept(ticket, response)) publish(response + ("op" to "execution/selection"))
            } catch (e: Exception) {
                if (executionSelection.fail(ticket, "Invalid execution settings response: ${e.message}"))
                    publish(mapOf("op" to "execution/selection", "err" to executionSelection.error))
            }
        }, { message ->
            if (executionSelection.fail(ticket, message)) {
                publish(mapOf("op" to "execution/selection", "err" to message))
                // A rejected/timed-out change is uncertain. Read back the actual server policy.
                if (policy != null) refreshExecutionPolicy()
            }
        })
    }
    private fun value(op: String, extra: Map<String, String> = emptyMap(), ok: ((String) -> Unit)? = null, err: ((String) -> Unit)? = null) =
        request(op, extra, { ok?.invoke(it["value"].orEmpty()) }, err)

    fun eval(code: String, onResult: ((Map<String, String>) -> Unit)? = null, onError: ((String) -> Unit)? = null,
             id: String = UUID.randomUUID().toString()) {
        snippets[id] = code
        request("eval", mapOf("code" to code), onResult, onError, id)
    }
    fun interrupt(onResult: ((String) -> Unit)? = null, onError: ((String) -> Unit)? = null) = value("interrupt", ok = onResult, err = onError)
    fun resetSession(onResult: ((String) -> Unit)? = null, onError: ((String) -> Unit)? = null) =
        request("session/reset", onResult = {
            boundEpoch = it["context-epoch"]
            change(if (it["context-ready"] == "true") State.READY else State.SESSION_READY)
            onResult?.invoke(it["value"].orEmpty())
        }, onError = onError)
    fun bindSpring(expr: String? = null, onResult: ((String) -> Unit)? = null, onError: ((String) -> Unit)? = null) =
        request("bind-spring", if (expr.isNullOrBlank()) emptyMap() else mapOf("expr" to expr), {
            if (it["value"] == "true") { boundEpoch = it["context-epoch"]; change(State.READY) }
            onResult?.invoke(it["value"].orEmpty())
        }, onError)
    fun getImports(onResult: (List<String>) -> Unit, onError: ((String) -> Unit)? = null) =
        request("imports/get", onResult = { onResult(it["imports"].orEmpty().lines().filter(String::isNotBlank)) }, onError = onError)
    fun addImports(imports: List<String>, onResult: (List<String>) -> Unit, onError: ((String) -> Unit)? = null) =
        request("imports/add", mapOf("imports" to imports.joinToString("\n")), { onResult(it["imports"].orEmpty().lines().filter(String::isNotBlank)) }, onError)
    fun hotSwap(code: String, onResult: ((String) -> Unit)? = null, onError: ((String) -> Unit)? = null) = value("class-reload", mapOf("code" to code), onResult, onError)
    fun listSpringBeans(onResult: (List<BeanInfo>) -> Unit, onError: ((String) -> Unit)? = null) =
        value("list-beans", ok = { text -> onResult(text.lines().filter(String::isNotBlank).map { val p = it.split('\t'); BeanInfo(p[0], p.getOrElse(1) { "" }) }) }, err = onError)
    fun listAgentSnapshots(onResult: (String) -> Unit, onError: ((String) -> Unit)? = null) = value("snapshot/list", ok = onResult, err = onError)
    fun snapshotListSimple(onResult: (List<String>) -> Unit, onError: ((String) -> Unit)? = null) =
        listAgentSnapshots({ onResult(it.lines().filter(String::isNotBlank)) }, onError)
    fun deleteSnapshot(name: String, onResult: ((String) -> Unit)? = null, onError: ((String) -> Unit)? = null) = value("snapshot/delete", mapOf("name" to name), onResult, onError)
    fun snapshotInfo(name: String, onResult: (String) -> Unit, onError: ((String) -> Unit)? = null) = value("snapshot/info", mapOf("name" to name), onResult, onError)
    fun snapshotPin(name: String, expr: String, onResult: ((String) -> Unit)? = null, onError: ((String) -> Unit)? = null) = value("snapshot/pin", mapOf("name" to name, "expr" to expr), onResult, onError)
    fun snapshotSave(name: String, expr: String, onResult: ((String) -> Unit)? = null, onError: ((String) -> Unit)? = null) = value("snapshot/save", mapOf("name" to name, "expr" to expr), onResult, onError)
    fun snapshotSaveJson(name: String, expr: String, onResult: ((String) -> Unit)? = null, onError: ((String) -> Unit)? = null) = snapshotSave(name, expr, onResult, onError)
    fun snapshotLoad(name: String, varName: String? = null, onResult: ((String) -> Unit)? = null, onError: ((String) -> Unit)? = null) =
        value("snapshot/load", mapOf("name" to name, "var" to (varName ?: "restored")), onResult, onError)
    fun snapshotMaterialize(name: String, typeFqn: String, target: String? = null, onResult: ((String) -> Unit)? = null, onError: ((String) -> Unit)? = null) =
        value("snapshot/load", mapOf("name" to name, "type" to typeFqn, "var" to (target ?: "restored")), onResult, onError)
    data class BeanInfo(val name: String, val className: String)
    companion object { @JvmStatic fun getInstance(project: Project): NreplService = project.service() }
}
