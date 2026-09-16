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
internal data class McpHttpResponse(val status: Int, val body: JsonObject? = null, val headers: Map<String, String> = emptyMap(), val stream: McpEvents.Stream? = null)

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
        val tasks=linkedMapOf<String,McpTask>()
        val events=McpEvents()
        val retrieving=AtomicBoolean()
        @Volatile var initialized = false
        @Volatile var touched = now
        @Volatile var closed = false
        @Volatile var unavailable = false
    }
    private val sessions = ConcurrentHashMap<String, Session>()
    private val capacity = Semaphore(4)
    private val taskExecutor=java.util.concurrent.Executors.newFixedThreadPool(4) { r->Thread(r,"sb-repl-mcp-task").apply { isDaemon=true } }
    private val closed = AtomicBoolean()
    val clientCount get() = sessions.size
    @Volatile var lastTool = ""
        private set

    fun handle(request: McpHttpRequest): McpHttpResponse {
        preflight(request)?.let { return it }
        if (request.method == "GET") return stream(request)
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
                        if (cancelled != null && validId(cancelled) && session.running == idKey(cancelled) && session.tasks.values.none { it.requestKey==idKey(cancelled) }) session.backend.request("interrupt")
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
                    success(id, McpJson.objectOf("tools" to McpTools.all.filter { it.enabled(permissions) }.map { tool->tool.descriptor().apply { if(session.version==VERSIONS.first() && tool.taskSupport)add("execution",McpJson.objectOf("taskSupport" to "optional")) } }))
                }
                "tools/call" -> if(params.has("task") && session.version==VERSIONS.first()) startTask(id,params,session) else call(id, params, session)
                "resources/list" -> {if(params.has("cursor"))throw McpError(-32602,"Resource list has no cursor");success(id,McpJson.objectOf("resources" to listOf(McpJson.objectOf("uri" to McpEvents.URI,"name" to "REPL session events","mimeType" to "application/json","description" to "Metadata-only capture, context, execution and recording notifications; session isolated."))))}
                "resources/templates/list" -> success(id,McpJson.objectOf("resourceTemplates" to emptyList<Any>()))
                "resources/read", "resources/subscribe", "resources/unsubscribe" -> {
                    if(McpJson.string(params,"uri")!=McpEvents.URI)throw McpError(-32002,"Unknown resource")
                    when(method){
                        "resources/read" -> success(id,McpJson.objectOf("contents" to listOf(McpJson.objectOf("uri" to McpEvents.URI,"mimeType" to "application/json","text" to session.events.resource.toString()))))
                        else -> {session.events.subscribed=method=="resources/subscribe";if(session.events.subscribed)session.events.emit("notifications/resources/updated",McpJson.objectOf("uri" to McpEvents.URI));success(id,JsonObject())}
                    }
                }
                "tasks/list", "tasks/get", "tasks/cancel", "tasks/result" -> tasks(id,method,params,session)
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
            "capabilities" to McpJson.objectOf("tools" to McpJson.objectOf("listChanged" to false),"resources" to McpJson.objectOf("subscribe" to true,"listChanged" to false)).apply {
                if(session.version==VERSIONS.first())add("tasks",McpJson.objectOf("list" to JsonObject(),"cancel" to JsonObject(),"requests" to McpJson.objectOf("tools" to McpJson.objectOf("call" to JsonObject()))))
            },
            "serverInfo" to McpJson.objectOf("name" to "spring-boot-repl", "version" to "0.23.0"),
            "instructions" to "Use repl_status, then repl_analyze before repl_eval. If Spring was still starting when connected, use repl_bind_spring once context-ready is true. Variables and handles belong to this MCP session; Spring beans, application effects and persistent DATA snapshots are shared. Never replay execution after a timeout or lost response. Use repl_interrupt for a running call. Reset explicitly after a Spring context change. Sessions expire after 30 idle minutes. Large results are bounded previews."),
            mapOf("MCP-Session-Id" to sessionId))
    }

    private fun call(id: JsonElement, params: JsonObject, session: Session, task: McpTask? = null): McpHttpResponse {
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
                if (session.closed || session.unavailable) { audit(session, tool.name, "CLOSED", mapOf("request" to auditId)); return success(id, McpTools.error("REPL session closed or unavailable; task results remain readable")) }
                if(task?.cancelled()==true)return success(id,McpTools.error("Task cancelled before dispatch"))
                if (!control && session.running != null && session.running != task?.requestKey) { audit(session, tool.name, "BUSY", mapOf("request" to auditId)); return success(id, McpTools.error("Session is busy. Wait for completion or use repl_interrupt.")) }
                if (!control && task==null && session.calls >= permissions.sessionQuota) {
                    audit(session, tool.name, "QUOTA_DENIED", mapOf("request" to auditId))
                    return success(id, McpTools.error("Session tool-call quota reached. Close this session; review usage before starting another."))
                }
                if (!control && task==null) session.calls++
                if (!control) session.running = idKey(id)
                val supplied = (if (tool.paged) emptyMap() else arguments).toMutableMap()
                supplied["audit-actor"] = "MCP ${session.client} (${session.auditOwner})"
                if (tool.operation in setOf("eval", "case/run", "case/run-batch", "case/export-junit", "watch/refresh")) {
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
            if(task!=null){session.unavailable=true;runCatching { session.backend.close() }}
            else sessions.entries.find { it.value === session }?.let { remove(it.key, session) }
            return success(id, McpTools.error("REPL request did not complete; this session was closed. Application effects may already have occurred. Check the app before creating a new session; do not automatically retry execution."))
        } finally {
            synchronized(session) { if (session.running == idKey(id)) session.running = null; session.touched = clock() }
        }
    }

    private fun stream(request:McpHttpRequest):McpHttpResponse {
        val sessionId=request.header("MCP-Session-Id") ?: return httpError(404,"Session missing or expired")
        val session=sessions[sessionId] ?: return httpError(404,"Session missing or expired")
        if(!session.initialized)return httpError(400,"Send notifications/initialized first")
        if(request.header("MCP-Protocol-Version")?.let { it!=session.version }==true)return httpError(400,"Protocol version does not match session")
        if(request.header("Accept").orEmpty().split(',').none { it.substringBefore(';').trim() in setOf("text/event-stream","*/*") })return httpError(406,"Accept text/event-stream required")
        return try {session.touched=clock();McpHttpResponse(200,headers=mapOf("Content-Type" to "text/event-stream"),stream=session.events.open(request.header("Last-Event-ID")))}
        catch(e:Exception){httpError(409,e.message ?: "Event stream unavailable")}
    }
    /** Called by the server timer. One outstanding metadata poll per client; never evaluates Java. */
    fun pollEvents(){
        sessions.values.filter { it.initialized&&!it.closed&&!it.unavailable }.forEach { session->
            if(session.events.polling.compareAndSet(false,true))try {
                session.backend.request("notifications/poll",mapOf("cursor" to session.events.cursor))
                    .orTimeout(5,TimeUnit.SECONDS).whenComplete { response,error->
                        try{if(error==null&&!session.closed)session.events.update(response)}finally{session.events.polling.set(false)}
                    }
            }catch(_:Exception){session.events.polling.set(false)}
        }
    }
    private fun startTask(id:JsonElement,params:JsonObject,session:Session):McpHttpResponse {
        val tool=McpTools.all.find { it.name==McpJson.string(params,"name") } ?: throw McpError(-32602,"Unknown tool")
        if(!tool.taskSupport)throw McpError(-32601,"This tool does not support tasks")
        if(!tool.enabled(permissions))return call(id,params,session)
        val input=params["arguments"]?.let { if(!it.isJsonObject)throw McpError(-32602,"Arguments must be an object");it.asJsonObject } ?: JsonObject()
        tool.arguments(input)
        val options=params["task"]?.takeIf { it.isJsonObject }?.asJsonObject ?: throw McpError(-32602,"task must be an object")
        val wanted=options["ttl"]?.let { value->if(!value.isJsonPrimitive||!value.asJsonPrimitive.isNumber)throw McpError(-32602,"Invalid task ttl");runCatching { value.asBigDecimal.longValueExact().also { require(it>0) } }.getOrElse { throw McpError(-32602,"Invalid task ttl") } } ?: 600000L
        val task=McpTask(idKey(id),wanted.coerceIn((permissions.timeoutMillis+60000).toLong(),1800000))
        val initial=synchronized(session){
            pruneTasks(session)
            if(session.closed||session.unavailable||session.running!=null)throw McpError(-32000,"Session closed or busy; execution was not started")
            if(session.tasks.size>=20)throw McpError(-32000,"Task limit reached (20); wait for retained results to expire")
            if(session.calls>=permissions.sessionQuota)throw McpError(-32000,"Session tool-call quota reached")
            session.calls++;session.running=task.requestKey;session.tasks[task.id]=task
            task.view()
        }
        try{taskExecutor.execute {
            val response=try {call(id,params,session,task).body!!}catch(_:Exception){rpcError(id,-32603,"Task could not complete; do not automatically retry").body!!}
            synchronized(session){
                task.complete(response)
                if(session.running==task.requestKey)session.running=null
                session.events.emit("notifications/tasks/status",task.view())
            }
        }}catch(_:java.util.concurrent.RejectedExecutionException){
            synchronized(session){session.running=null;session.tasks.remove(task.id);session.calls--}
            throw McpError(-32000,"Task executor closed; execution was not started")
        }
        return success(id,McpJson.objectOf("task" to initial))
    }
    private fun pruneTasks(session:Session){session.tasks.entries.removeIf { (_,task)->task.terminal()&&System.currentTimeMillis()-task.createdMillis>task.ttl }}
    private fun tasks(id:JsonElement,method:String,params:JsonObject,session:Session):McpHttpResponse {
        if(session.version!=VERSIONS.first())throw McpError(-32601,"Tasks require MCP 2025-11-25")
        val task=synchronized(session){
            pruneTasks(session)
            if(method=="tasks/list"){
                if(params.has("cursor"))throw McpError(-32602,"Task list has no cursor; at most 20 tasks")
                return success(id,McpJson.objectOf("tasks" to session.tasks.values.map { it.view() }))
            }
            session.tasks[McpJson.string(params,"taskId")] ?: throw McpError(-32602,"Unknown task in this session")
        }
        return when(method){
            "tasks/get" -> success(id,task.view())
            "tasks/cancel" -> synchronized(session){
                audit(session,"tasks/cancel","REQUESTED",mapOf("taskId" to task.id))
                task.cancel()
                if(session.running==task.requestKey)session.backend.request("interrupt")
                session.events.emit("notifications/tasks/status",task.view())
                success(id,task.view())
            }
            else -> {
                if(!session.retrieving.compareAndSet(false,true))throw McpError(-32000,"One pending tasks/result per session; use tasks/get")
                try {
                    val original=task.result.get((permissions.timeoutMillis+10000).toLong(),TimeUnit.MILLISECONDS).deepCopy()
                    original.add("id",id);original.addProperty("jsonrpc","2.0")
                    original["result"]?.asJsonObject?.add("_meta",McpJson.objectOf("io.modelcontextprotocol/related-task" to McpJson.objectOf("taskId" to task.id)))
                    McpHttpResponse(200,original)
                }finally{session.retrieving.set(false)}
            }
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
            synchronized(session) {
                session.closed = true
                session.events.close()
                session.tasks.values.filter { !it.terminal() }.forEach { it.cancel() }
            }
            runCatching { session.backend.close() }
            capacity.release(); changed()
        }
    }
    override fun close() {
        synchronized(sessions) { if (!closed.compareAndSet(false, true)) return }
        sessions.forEach { (id, session) -> remove(id, session) }
        taskExecutor.shutdownNow()
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
