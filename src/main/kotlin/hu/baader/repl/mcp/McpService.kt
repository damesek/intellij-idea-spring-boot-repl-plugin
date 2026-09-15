package hu.baader.repl.mcp

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import hu.baader.repl.nrepl.NreplService
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

internal data class McpState(val busy: Boolean = false, val url: String = "", val clients: Int = 0,
    val lastTool: String = "", val detail: String = "Stopped. Connect an application, then start MCP.")

@Service(Service.Level.PROJECT)
internal class McpService(private val project: Project) : Disposable {
    private val repl = NreplService.getInstance(project)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "sb-repl-mcp-lifecycle").apply { isDaemon = true } }
    private val listeners = CopyOnWriteArrayList<(McpState) -> Unit>()
    private val lock = Any()
    @Volatile private var server: McpHttpServer? = null
    @Volatile private var target: NreplService.McpTarget? = null
    @Volatile var state = McpState()
        private set
    private var generation = 0L
    @Volatile private var disposed = false
    private val connection = repl.onMessage { message ->
        if (message["op"] == "connection" && target != null && target != repl.mcpTarget())
            stop("REPL connection changed. Connect the application and start MCP again.")
    }

    fun listen(listener: (McpState) -> Unit): Disposable {
        listeners += listener; listener(state)
        return Disposable { listeners.remove(listener) }
    }
    private fun publish() {
        if (disposed) return
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed && !disposed) listeners.forEach { it(state) }
        }
    }
    private fun updated() {
        synchronized(lock) {
            val active = server ?: return
            state = state.copy(clients = active.router.clientCount, lastTool = active.router.lastTool)
        }
        publish()
    }

    fun start(port: Int, permissions: McpPermissions) {
        val run: Long
        val captured: NreplService.McpTarget
        synchronized(lock) {
            if (disposed || state.busy || server != null) return
            val current = repl.mcpTarget()
            if (current == null) { state = McpState(detail = "Connect a running application in Java REPL first."); publish(); return }
            captured = current; target = captured
            run = ++generation; state = McpState(busy = true, detail = "Starting local MCP server…")
            worker.execute {
                try {
                    val created = McpHttpServer.start(port, permissions, {
                        check(repl.mcpTarget() == captured) { "REPL target changed" }
                        McpRecordingBackend(NreplMcpBackend.connect(captured.endpoint, captured.pid),
                            IdeMcpRecordingAccess(project), permissions)
                    }, ::updated)
                    val accepted = synchronized(lock) {
                        if (disposed || generation != run || repl.mcpTarget() != captured) false
                        else {
                            server = created
                            state = McpState(url = created.url, detail = "Running · application PID ${captured.pid}")
                            true
                        }
                    }
                    if (!accepted) {
                        created.close()
                        synchronized(lock) { if (generation == run) { target = null; state = McpState(detail = "REPL connection changed. Start MCP again.") } }
                    }
                } catch (_: Exception) {
                    synchronized(lock) {
                        if (generation == run) { target = null; state = McpState(detail = "MCP could not start. Check the port (0 selects a free port) and the REPL connection.") }
                    }
                }
                publish()
            }
        }
        publish()
    }

    fun stop(detail: String = "Stopped. Client sessions and their LIVE references were closed.") {
        synchronized(lock) {
            if (disposed) return
            val run = ++generation
            val previous = server; server = null; target = null
            state = McpState(busy = true, detail = "Stopping MCP…")
            worker.execute {
                previous?.close()
                synchronized(lock) { if (generation == run) state = McpState(detail = detail) }
                publish()
            }
        }
        publish()
    }
    fun clientConfig(): String? = server?.clientConfig()
    override fun dispose() {
        val previous: McpHttpServer?
        synchronized(lock) {
            disposed = true; ++generation; previous = server; server = null; target = null
            connection.dispose(); listeners.clear()
        }
        previous?.close()
        worker.shutdownNow()
    }
    companion object { fun getInstance(project: Project): McpService = project.service() }
}
