package hu.baader.repl.mcp

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import hu.baader.repl.protocol.AuditTrail
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

internal data class McpHttpRequest(val method: String, val path: String, val headers: Map<String, String>, val body: String = "") {
    fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
}
internal data class McpHttpResponse(val status: Int, val body: JsonObject? = null, val headers: Map<String, String> = emptyMap())

/** MCP 2025 Streamable HTTP, JSON responses, stateful sessions. Independent of IntelliJ and sockets for tests. */
internal class McpRouter(
    private val token: String, private val authority: String, private val permissions: McpPermissions,
    private val factory: () -> McpBackend, private val changed: () -> Unit = {},
    private val clock: () -> Long = System::nanoTime,
    private val idleNanos: Long = TimeUnit.MINUTES.toNanos(30)
) : AutoCloseable {
    private class Session(val backend: McpBackend, val version: String, now: Long, val client: String) {
        val auditOwner = java.util.UUID.randomUUID().toString()
        var calls = 0
        val seen = HashSet<String>()
        var running: String? = null
        @Volatile var initialized = false
        @Volatile var touched = now
        @Volatile var closed = false
    }
    private val sessions = ConcurrentHashMap<String, Session>()
    private val capacity = Semaphore(4)
    private val closed = AtomicBoolean()
    val clientCount get() = sessions.size
    @Volatile var lastTool = ""
        private set

    fun handle(request: McpHttpRequest): McpHttpResponse {
        preflight(request)?.let { return it }
        if (request.method == "GET") return McpHttpResponse(405, headers = mapOf("Allow" to "POST, DELETE"))
        val sessionId = request.header("MCP-Session-Id")
        if (request.method == "DELETE") {
            if (sessionId == null) return httpError(400, "MCP-Session-Id required")
            val session = sessions[sessionId] ?: return httpError(404, "Session expired; initialize a new session")
            request.header("MCP-Protocol-Version")?.let { if (it != session.version) return httpError(400, "Protocol version does not match the session") }
            remove(sessionId, session)
            return McpHttpResponse(200)
        }
        if (request.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase() != "application/json")
            return httpError(415, "Content-Type must be application/json")
        val accept = request.header("Accept").orEmpty().split(',').map { it.substringBefore(';').trim().lowercase() }
        if ("application/json" !in accept && "*/*" !in accept) return httpError(406, "Accept must include application/json and text/event-stream")
        if (request.body.toByteArray(Charsets.UTF_8).size > McpJson.MAX_REQUEST_BYTES) return httpError(413, "Request exceeds 256 KiB")
        val parsed = try { McpJson.parse(request.body) } catch (_: Exception) { return rpcError(null, -32700, "Invalid JSON", 400) }
        if (!parsed.isJsonObject) return rpcError(null, -32600, "Expected a single JSON-RPC object", 400)
        val message = parsed.asJsonObject
        val id = message["id"]
        if (McpJson.string(message, "jsonrpc") != "2.0" || (id != null && !validId(id)))
            return rpcError(null, -32600, "Invalid JSON-RPC request", 400)
        val method = McpJson.string(message, "method") ?: return rpcError(id, -32600, "Method required", 400)
        val params = message["params"]?.let {
            if (!it.isJsonObject) return rpcError(id, -32602, "Params must be an object")
            it.asJsonObject
        } ?: JsonObject()
        if (method == "initialize") {
            if (id == null || sessionId != null) return rpcError(id, -32600, "Initialize requires an id and no session header", 400)
            return initialize(id, params)
        }
        if (sessionId == null) return httpError(400, "MCP-Session-Id required; send initialize first")
        val session = sessions[sessionId] ?: return httpError(404, "Session expired; initialize a new session")
        request.header("MCP-Protocol-Version")?.let { if (it != session.version) return httpError(400, "Protocol version does not match the session") }
        synchronized(session) {
            if (session.closed) return httpError(404, "Session expired")
            session.touched = clock()
        }
        if (id == null) {
            when (method) {
                "notifications/initialized" -> session.initialized = true
                "notifications/cancelled" -> {
                    val cancelled = params["requestId"]
                    // Serialize dispatch with completion/start so cancellation cannot interrupt the next call.
                    synchronized(session) {
                        if (cancelled != null && validId(cancelled) && session.running == idKey(cancelled)) session.backend.request("interrupt")
                    }
                }
            }
            return McpHttpResponse(202)
        }
        synchronized(session) {
            if (session.seen.size >= 4096) return rpcError(id, -32000, "Session request limit reached; initialize a new session")
            if (!session.seen.add(idKey(id))) return rpcError(id, -32600, "Duplicate request id; requests are never replayed")
        }
        if (method == "ping") return success(id, JsonObject())
        if (!session.initialized) return rpcError(id, -32000, "Send notifications/initialized first")
        return try {
            when (method) {
                "tools/list" -> {
                    if (params.has("cursor")) throw McpError(-32602, "Tool list has no cursor")
                    success(id, McpJson.objectOf("tools" to McpTools.all.filter { it.enabled(permissions) }.map { it.descriptor() }))
                }
                "tools/call" -> call(id, params, session)
                else -> rpcError(id, -32601, "Unknown method: ${method.take(128)}")
            }
        } catch (e: McpError) { rpcError(id, e.code, e.message) }
        catch (_: Exception) { rpcError(id, -32603, "Request could not be completed or audited. Inspect local audit records before retrying a state-changing operation.") }
    }

    /** Also called before the HTTP adapter reads a body. No tokens, source code or results are logged. */
    fun preflight(request: McpHttpRequest): McpHttpResponse? {
        if (closed.get()) return httpError(503, "MCP server stopped")
        if (request.path != "/mcp") return httpError(404, "Unknown endpoint")
        if (request.header("Host") != authority) return httpError(403, "Invalid host")
        val origin = request.header("Origin")
        if (origin != null && origin != "http://$authority") return httpError(403, "Invalid origin")
        val supplied = request.header("Authorization").orEmpty().toByteArray(Charsets.UTF_8)
        if (!MessageDigest.isEqual(supplied, "Bearer $token".toByteArray(Charsets.UTF_8)))
            return McpHttpResponse(401, headers = mapOf("WWW-Authenticate" to "Bearer realm=\"Spring Boot REPL\""))
        if (request.method !in setOf("GET", "POST", "DELETE")) return McpHttpResponse(405, headers = mapOf("Allow" to "POST, GET, DELETE"))
        val version = request.header("MCP-Protocol-Version")
        if (version != null && version !in VERSIONS) return httpError(400, "Unsupported MCP version; supported: ${VERSIONS.joinToString()}")
        return null
    }

    private fun initialize(id: JsonElement, params: JsonObject): McpHttpResponse {
        val requested = McpJson.string(params, "protocolVersion") ?: return rpcError(id, -32602, "protocolVersion required")
        val info = params["clientInfo"]
        if (params["capabilities"]?.isJsonObject != true || info?.isJsonObject != true ||
            McpJson.string(info.asJsonObject, "name").isNullOrBlank() || McpJson.string(info.asJsonObject, "version") == null)
            return rpcError(id, -32602, "clientInfo and capabilities required")
        expireIdle()
        if (!capacity.tryAcquire()) return rpcError(id, -32000, "Maximum 4 MCP clients; close an existing session")
        val backend = try { factory() } catch (_: Exception) {
            capacity.release()
            return rpcError(id, -32000, "Cannot connect a new REPL session. Check the application and restart MCP.")
        }
        val session = Session(backend, if (requested in VERSIONS) requested else VERSIONS.first(), clock(), McpJson.string(info.asJsonObject, "name").orEmpty().take(128))
        session.seen += idKey(id)
        val sessionId = secret()
        synchronized(sessions) {
            if (closed.get()) { backend.close(); capacity.release(); return httpError(503, "MCP server stopped") }
            sessions[sessionId] = session
        }
        changed()
        return success(id, McpJson.objectOf("protocolVersion" to session.version,
            "capabilities" to McpJson.objectOf("tools" to McpJson.objectOf("listChanged" to false)),
            "serverInfo" to McpJson.objectOf("name" to "spring-boot-repl", "version" to "0.20.0"),
            "instructions" to "Use repl_status, then repl_analyze before repl_eval. If Spring was still starting when connected, use repl_bind_spring once context-ready is true. Variables and handles belong to this MCP session; Spring beans, application effects and persistent DATA snapshots are shared. Never replay execution after a timeout or lost response. Use repl_interrupt for a running call. Reset explicitly after a Spring context change. Sessions expire after 30 idle minutes. Large results are bounded previews."),
            mapOf("MCP-Session-Id" to sessionId))
    }

    private fun call(id: JsonElement, params: JsonObject, session: Session): McpHttpResponse {
        val name = McpJson.string(params, "name") ?: throw McpError(-32602, "Tool name required")
        val tool = McpTools.all.find { it.name == name } ?: throw McpError(-32602, "Unknown tool: ${name.take(128)}")
        if (!tool.enabled(permissions)) {
            audit(session, tool.name, "DENIED", emptyMap())
            return success(id, McpTools.error("This tool is disabled. Change permissions in the plugin's MCP tab and restart the server."))
        }
        val input = params["arguments"]?.let { if (!it.isJsonObject) throw McpError(-32602, "Arguments must be an object"); it.asJsonObject } ?: JsonObject()
        val arguments = tool.arguments(input)
        val control = tool.operation == "interrupt"
        val started = clock()
        val auditId = audit(session, tool.name, "STARTED", arguments)
        try {
            val pending = synchronized(session) {
                if (session.closed) { audit(session, tool.name, "CLOSED", mapOf("request" to auditId)); return success(id, McpTools.error("Session closed")) }
                if (!control && session.running != null) { audit(session, tool.name, "BUSY", mapOf("request" to auditId)); return success(id, McpTools.error("Session is busy. Wait for completion or use repl_interrupt.")) }
                if (!control && session.calls >= permissions.sessionQuota) {
                    audit(session, tool.name, "QUOTA_DENIED", mapOf("request" to auditId))
                    return success(id, McpTools.error("Session tool-call quota reached. Close this session; review usage before starting another."))
                }
                if (!control) session.calls++
                if (!control) session.running = idKey(id)
                val supplied = (if (tool.paged) emptyMap() else arguments).toMutableMap()
                supplied["audit-actor"] = "MCP ${session.client} (${session.auditOwner})"
                if (tool.operation in setOf("eval", "case/run", "case/run-batch", "case/export-junit")) {
                    supplied["execution-mode"] = permissions.executionMode
                    supplied["transaction-manager"] = permissions.transactionManager
                    supplied["timeout-ms"] = permissions.timeoutMillis.toString()
                }
                session.backend.request(tool.operation, supplied)
            }
            lastTool = tool.name; changed()
            val response = pending.get((permissions.timeoutMillis + 5000).toLong(), TimeUnit.MILLISECONDS).toMutableMap()
            if (tool.operation == "execution/policy") {
                response["mcp-execution-mode"] = permissions.executionMode
                response["mcp-transaction-manager"] = permissions.transactionManager
                response["mcp-remaining-calls"] = (permissions.sessionQuota - session.calls).toString()
            }
            audit(session, tool.name, if (response.containsKey("err")) "ERROR" else "COMPLETED", mapOf("request" to auditId,"runtime-audit-id" to response["audit-id"].orEmpty(), "duration-ms" to ((clock()-started)/1000000).toString()))
            return success(id, McpTools.sanitizeResult(McpTools.result(tool, arguments, response), permissions,
                redactedBeforePaging = tool.operation.startsWith("recording/") && response.containsKey("recording-json")))
        } catch (e: Exception) {
            runCatching { audit(session, tool.name, "UNCERTAIN", mapOf("request" to auditId,"duration-ms" to ((clock()-started)/1000000).toString())) }
            val cause = e.cause ?: e
            if (e is InterruptedException) Thread.currentThread().interrupt()
            // On an uncertain transport outcome close the session: do not let a second eval race the old one.
            if (cause is TimeoutException || e is InterruptedException) session.backend.request("interrupt")
            sessions.entries.find { it.value === session }?.let { remove(it.key, session) }
            return success(id, McpTools.error("REPL request did not complete; this session was closed. Application effects may already have occurred. Check the app before creating a new session; do not automatically retry execution."))
        } finally {
            synchronized(session) { if (session.running == idKey(id)) session.running = null; session.touched = clock() }
        }
    }

    private fun audit(session: Session, tool: String, phase: String, arguments: Map<String,String>): String {
        val code = arguments["code"].orEmpty()
        return AuditTrail.append("mcp-${ProcessHandle.current().pid()}", mapOf("session" to session.auditOwner,
            "client" to session.client, "tool" to tool, "phase" to phase, "code" to code,
            "codeSha256" to AuditTrail.hash(code), "details" to arguments.filterKeys { it != "code" }.toString()))
    }

    fun expireIdle() {
        val now = clock()
        sessions.forEach { (id, session) ->
            synchronized(session) { if (session.running == null && now - session.touched > idleNanos) remove(id, session) }
        }
    }
    private fun remove(id: String, session: Session) {
        if (sessions.remove(id, session)) {
            synchronized(session) { session.closed = true }
            runCatching { session.backend.close() }
            capacity.release(); changed()
        }
    }
    override fun close() {
        synchronized(sessions) { if (!closed.compareAndSet(false, true)) return }
        sessions.forEach { (id, session) -> remove(id, session) }
    }
    private fun validId(id: JsonElement) = id.isJsonPrimitive && (id.asJsonPrimitive.isString && id.asString.length in 1..256 ||
        id.asJsonPrimitive.isNumber && runCatching { id.asBigDecimal.longValueExact(); true }.getOrDefault(false))
    private fun idKey(id: JsonElement) = if (id.asJsonPrimitive.isString) "s:" + id.asString else "n:" + id.asBigDecimal.stripTrailingZeros().toPlainString()
    private fun success(id: JsonElement, result: JsonObject, headers: Map<String, String> = emptyMap()) =
        McpHttpResponse(200, McpJson.objectOf("jsonrpc" to "2.0", "id" to id, "result" to result), headers)
    private fun rpcError(id: JsonElement?, code: Int, message: String, status: Int = 200): McpHttpResponse {
        val body = McpJson.objectOf("jsonrpc" to "2.0", "error" to McpJson.objectOf("code" to code, "message" to message))
        body.add("id", id ?: JsonNull.INSTANCE)
        return McpHttpResponse(status, body)
    }
    private fun httpError(status: Int, message: String) = rpcError(null, -32000, message, status)
    companion object {
        val VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26")
        fun secret(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    }
}
