package hu.baader.repl.mcp

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class McpRouterTest {
    private class Backend : McpBackend {
        var closed = false
        val operations = CopyOnWriteArrayList<String>()
        var answer: (String, Map<String, String>) -> CompletableFuture<Map<String, String>> = { _, _ ->
            CompletableFuture.completedFuture(mapOf("status" to "done", "value" to "42"))
        }
        override fun request(operation: String, arguments: Map<String, String>): CompletableFuture<Map<String, String>> {
            operations += operation; return answer(operation, arguments)
        }
        override fun close() { closed = true }
    }
    private val backend = Backend()
    private val headers = mapOf("Host" to "127.0.0.1:1234", "Authorization" to "Bearer secret",
        "Content-Type" to "application/json", "Accept" to "application/json, text/event-stream")
    private val ids = AtomicInteger()
    private fun router(permissions: McpPermissions = McpPermissions(execution = true, snapshotWrites = true, snapshotDelete = true, caseRuns = true, captureChanges = true, executionMode = "LIVE")) = McpRouter("secret", "127.0.0.1:1234", permissions, { backend })
    private fun request(method: String, params: JsonObject = JsonObject(), session: String? = null, id: Int? = ids.incrementAndGet()): McpHttpRequest {
        val message = McpJson.objectOf("jsonrpc" to "2.0", "method" to method, "params" to params)
        if (id != null) message.addProperty("id", id)
        return McpHttpRequest("POST", "/mcp", headers + if (session == null) emptyMap() else mapOf("MCP-Session-Id" to session), message.toString())
    }
    private fun init(router: McpRouter, version: String = "2025-11-25", ready: Boolean = true): String {
        val result = router.handle(request("initialize", McpJson.objectOf("protocolVersion" to version,
            "capabilities" to JsonObject(), "clientInfo" to mapOf("name" to "test", "version" to "1"))))
        assertEquals(result.body.toString(), 200, result.status)
        val session = result.headers.getValue("MCP-Session-Id")
        if (ready) assertEquals(202, router.handle(request("notifications/initialized", session = session, id = null)).status)
        return session
    }
    private fun call(router: McpRouter, session: String, name: String, vararg arguments: Pair<String, Any?>) =
        router.handle(request("tools/call", McpJson.objectOf("name" to name, "arguments" to McpJson.objectOf(*arguments)), session))
    private fun result(response: McpHttpResponse) = response.body!!.getAsJsonObject("result")
    private fun error(response: McpHttpResponse) = response.body!!.getAsJsonObject("error")["code"].asInt

    @Test fun caseBatchRequiresCasePermissionAndCannotOverrideExecutionPolicy() {
        val supplied = mutableListOf<Map<String,String>>()
        backend.answer = { _, args -> supplied += args; CompletableFuture.completedFuture(mapOf("outcome" to "PASSED")) }
        router(McpPermissions(execution=true)).use { router ->
            val session=init(router)
            assertTrue(result(call(router,session,"repl_case_run_batch","names" to "case"))["isError"].asBoolean)
            assertTrue(backend.operations.isEmpty())
        }
        router(McpPermissions(execution=true,caseRuns=true,executionMode="READ_ONLY",transactionManager="orders",timeoutMillis=1234)).use { router ->
            val session=init(router)
            assertFalse(result(call(router,session,"repl_case_run_batch","names" to "one\ntwo"))["isError"].asBoolean)
            assertEquals("READ_ONLY",supplied.last()["execution-mode"])
            assertEquals("orders",supplied.last()["transaction-manager"])
            assertEquals("1234",supplied.last()["timeout-ms"])
            assertEquals(-32602,error(call(router,session,"repl_case_run_batch","names" to "one","execution-mode" to "LIVE")))
        }
    }

    @Test fun initializationNegotiatesVersionsAndRequiresReadyNotification() {
        router().use { router ->
            val session = init(router, "2099-01-01", false)
            assertEquals(-32000, error(router.handle(request("tools/list", session = session))))
            assertEquals(200, router.handle(request("ping", session = session)).status)
            assertEquals(202, router.handle(request("notifications/initialized", session = session, id = null)).status)
            assertTrue(result(router.handle(request("tools/list", session = session))).getAsJsonArray("tools").size() > 20)
            assertEquals(404, router.handle(McpHttpRequest("GET", "/mcp", headers)).status)
            assertEquals(1, router.clientCount)
            assertEquals(200, router.handle(McpHttpRequest("DELETE", "/mcp", headers + ("MCP-Session-Id" to session))).status)
            assertTrue(backend.closed)
            assertEquals(404, router.handle(request("ping", session = session)).status)
        }
    }
    @Test fun rejectsUnauthorizedOriginsHostsAndProtocolVersionsBeforeBackendCreation() {
        val count = AtomicInteger()
        McpRouter("secret", "127.0.0.1:1234", McpPermissions(), { count.incrementAndGet(); backend }).use { router ->
            val base = request("initialize")
            for ((replacement, expected) in listOf(
                mapOf("Authorization" to "Bearer wrong") to 401,
                mapOf("Origin" to "https://evil.example") to 403,
                mapOf("Origin" to "null") to 403,
                mapOf("Host" to "evil.example:1234") to 403,
                mapOf("MCP-Protocol-Version" to "invalid") to 400)) {
                assertEquals(expected, router.handle(base.copy(headers = headers + replacement)).status)
            }
            assertEquals(0, count.get())
            assertEquals(404, router.handle(base.copy(path = "/mcp/extra")).status)
            assertEquals(401, router.handle(base.copy(headers = headers - "Authorization")).status)
        }
    }
    @Test fun unknownAndMissingSessionsNeverExecuteTools() {
        router().use { router ->
            assertEquals(400, router.handle(request("tools/list")).status)
            assertEquals(404, call(router, "guessed", "repl_eval", "code" to "1").status)
            val session = init(router)
            val req = request("tools/list", session = session)
            assertEquals(400, router.handle(req.copy(headers = req.headers + ("MCP-Protocol-Version" to "2025-06-18"))).status)
            assertTrue(backend.operations.isEmpty())
        }
    }
    @Test fun permissionsFilterToolListAndEnforceDirectCalls() {
        router(McpPermissions(false, true)).use { router ->
            val session = init(router)
            val names = result(router.handle(request("tools/list", session = session))).getAsJsonArray("tools").map { it.asJsonObject["name"].asString }
            assertTrue("repl_analyze" in names)
            for (name in listOf("repl_eval", "repl_reload", "repl_capture_arm", "repl_snapshot_load", "repl_case_run", "repl_inspect")) {
                assertFalse(name in names)
                assertTrue(result(call(router, session, name))["isError"].asBoolean)
            }
            assertTrue(backend.operations.isEmpty())
            assertFalse(result(call(router, session, "repl_analyze", "code" to "int x=1;"))["isError"].asBoolean)
            assertEquals(listOf("analyze"), backend.operations)
        }
    }
    @Test fun hotSwapRequiresItsOwnPermission() {
        router().use { router ->
            assertTrue(result(call(router, init(router), "repl_reload", "code" to "class A {}"))["isError"].asBoolean)
            assertTrue(backend.operations.isEmpty())
        }
        router(McpPermissions(true, true)).use { router ->
            assertFalse(result(call(router, init(router), "repl_reload", "code" to "class A {}"))["isError"].asBoolean)
            assertEquals(listOf("class-reload"), backend.operations)
        }
    }
    @Test fun granularPermissionsDenyChangesBeforeTheBackendAndQuotaKeepsInterruptAvailable() {
        router(McpPermissions(execution=true, executionMode="ROLLBACK", transactionManager="tx", sessionQuota=1)).use { router ->
            val session=init(router)
            assertTrue(result(call(router,session,"repl_snapshot_delete","name" to "data"))["isError"].asBoolean)
            assertTrue(result(call(router,session,"repl_case_run","name" to "case"))["isError"].asBoolean)
            assertTrue(backend.operations.isEmpty())
            backend.answer={ _, args ->
                if (args.containsKey("code")) { assertEquals("ROLLBACK",args["execution-mode"]); assertEquals("tx",args["transaction-manager"]) }
                CompletableFuture.completedFuture(mapOf("value" to "42","status" to "done"))
            }
            assertFalse(result(call(router,session,"repl_eval","code" to "1+1"))["isError"].asBoolean)
            assertTrue(result(call(router,session,"repl_eval","code" to "2+2"))["isError"].asBoolean)
            assertFalse(result(call(router,session,"repl_interrupt"))["isError"].asBoolean)
            assertEquals(listOf("eval","interrupt"),backend.operations)
        }
    }
    @Test fun toolAllowlistAndRedactionApplyToRealResponses() {
        router(McpPermissions(execution=true,allowedTools=setOf("repl_eval"),executionMode="LIVE")).use { router ->
            val session=init(router)
            assertTrue(result(call(router,session,"repl_analyze","code" to "1"))["isError"].asBoolean)
            backend.answer={ _, _ -> CompletableFuture.completedFuture(mapOf("value" to "{\"password\":\"private-secret\",\"ok\":1}","status" to "done")) }
            val response=result(call(router,session,"repl_eval","code" to "1"))
            assertFalse(response.toString().contains("private-secret"))
            assertTrue(response.getAsJsonObject("structuredContent")["redacted"].asBoolean)
        }
    }
    @Test fun validatesSchemaAndForbidsTransportOverrides() {
        router().use { router ->
            val session = init(router)
            for (bad in listOf("token", "session", "op", "id", "unexpected"))
                assertEquals(-32602, error(call(router, session, "repl_eval", "code" to "1", bad to "x")))
            assertEquals(-32602, error(call(router, session, "repl_eval", "code" to 123)))
            assertEquals(-32602, error(call(router, session, "repl_eval")))
            assertEquals(-32602, error(call(router, session, "repl_variables", "offset" to -1)))
            assertEquals(-32602, error(call(router, session, "repl_variables", "offset" to "1")))
            assertEquals(-32602, error(call(router, session, "repl_variables", "limit" to 0)))
            assertEquals(-32602, error(call(router, session, "repl_complete", "code" to "x", "cursor" to 2)))
            assertEquals(-32602, error(call(router, session, "repl_inspect", "var" to "x", "handle" to "y")))
            assertTrue(backend.operations.isEmpty())
        }
    }
    @Test fun strictParsingRejectsMalformedBatchesDuplicatesAndOversizedBodies() {
        router().use { router ->
            val base = request("ping")
            for (json in listOf("{", "{\"jsonrpc\":\"2.0\",\"jsonrpc\":\"2.0\"}", "[".repeat(40) + "0" + "]".repeat(40), "{}{}", "{x:1}"))
                assertEquals(-32700, error(router.handle(base.copy(body = json))))
            assertEquals(-32600, error(router.handle(base.copy(body = "[]"))))
            assertEquals(413, router.handle(base.copy(body = "x".repeat(McpJson.MAX_REQUEST_BYTES + 1))).status)
            assertEquals(415, router.handle(base.copy(headers = headers + ("Content-Type" to "text/plain"))).status)
            assertEquals(406, router.handle(base.copy(headers = headers + ("Accept" to "text/html"))).status)
        }
    }
    @Test fun duplicateExecutionIsRejectedAfterCompletion() {
        router().use { router ->
            val session = init(router)
            val req = request("tools/call", McpJson.objectOf("name" to "repl_eval", "arguments" to mapOf("code" to "1")), session)
            assertFalse(result(router.handle(req))["isError"].asBoolean)
            assertEquals(-32600, error(router.handle(req)))
            assertEquals(listOf("eval"), backend.operations)
        }
    }
    @Test fun initializeIdAndEquivalentNumericIdsCannotBeReused() {
        router().use { router ->
            val session = init(router)
            val req = request("ping", session = session, id = 900)
            assertEquals(200, router.handle(req).status)
            assertEquals(-32600, error(router.handle(req.copy(body = req.body.replace("900", "900.0")))))
            val originalInitId = ids.get()
            assertEquals(-32600, error(router.handle(request("ping", session = session, id = originalInitId))))
        }
    }
    @Test fun errorsKeepLateOutputAndStripTransportSecrets() {
        backend.answer = { _, _ -> CompletableFuture.completedFuture(mapOf("err" to "late failure", "out" to "árvíz 🧪", "id" to "private", "session" to "private", "token" to "private", "status" to "error\ndone")) }
        router().use { router ->
            val result = result(call(router, init(router), "repl_eval", "code" to "1"))
            assertTrue(result["isError"].asBoolean)
            assertEquals("árvíz 🧪", result.getAsJsonObject("structuredContent")["out"].asString)
            assertFalse(result.toString().contains("private"))
        }
    }
    @Test fun listsArePagedAndLargeValuesExplicitlyTruncated() {
        backend.answer = { op, _ -> CompletableFuture.completedFuture(mapOf("value" to if (op == "vars/list")
            (0..120).joinToString("\n") { "v$it\tString\tárvíz" } else "x".repeat(100_000))) }
        router().use { router ->
            val session = init(router)
            val page = result(call(router, session, "repl_variables", "offset" to 50.0, "limit" to 20)).getAsJsonObject("structuredContent")
            assertEquals(121, page["totalRows"].asInt); assertEquals(70, page["nextOffset"].asInt)
            assertEquals(20, page.getAsJsonArray("rows").size())
            assertEquals("v50", page.getAsJsonArray("rows")[0].asJsonArray[0].asString)
            val exported = result(call(router, session, "repl_snapshot_export", "name" to "big"))
            assertEquals("value", exported.getAsJsonObject("structuredContent").getAsJsonArray("truncatedFields")[0].asString)
            assertTrue(exported.toString().toByteArray().size < McpJson.MAX_RESPONSE_BYTES)
        }
    }
    @Test fun busySessionCanBeCancelledWithoutInterruptingOtherIds() {
        val running = CompletableFuture<Map<String, String>>()
        val entered = CountDownLatch(1)
        backend.answer = { op, _ ->
            if (op == "eval") { entered.countDown(); running }
            else { running.complete(mapOf("err" to "Interrupted", "status" to "error\ndone")); CompletableFuture.completedFuture(mapOf("status" to "done")) }
        }
        val worker = Executors.newSingleThreadExecutor()
        try { router().use { router ->
            val session = init(router)
            val eval = request("tools/call", McpJson.objectOf("name" to "repl_eval", "arguments" to mapOf("code" to "while(true){}")), session, 1000)
            val result = worker.submit<McpHttpResponse> { router.handle(eval) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertTrue(result(call(router, session, "repl_eval", "code" to "2"))["isError"].asBoolean)
            router.handle(request("notifications/cancelled", McpJson.objectOf("requestId" to 999), session, null))
            assertFalse(running.isDone)
            router.handle(request("notifications/cancelled", McpJson.objectOf("requestId" to 1000), session, null))
            assertTrue(result(result.get(3, TimeUnit.SECONDS))["isError"].asBoolean)
            assertEquals(listOf("eval", "interrupt"), backend.operations)
        } } finally { worker.shutdownNow() }
    }
    @Test fun escapedUnicodeResponseRemainsBoundedAndRetainsItsHandle() {
        backend.answer = { _, _ -> CompletableFuture.completedFuture(mapOf("value" to "\u0000".repeat(100_000), "handle" to "h1", "status" to "done")) }
        router().use { router ->
            val session = init(router)
            val request = request("tools/call", McpJson.objectOf("name" to "repl_eval", "arguments" to mapOf("code" to "large()")), session)
            val body = McpJson.parse(request.body).asJsonObject.apply { addProperty("id", "\u0000".repeat(256)) }
            val response = router.handle(request.copy(body = body.toString()))
            assertTrue(response.body.toString().toByteArray().size <= McpJson.MAX_RESPONSE_BYTES)
            val data = result(response).getAsJsonObject("structuredContent")
            assertTrue(data["truncated"].asBoolean); assertEquals("h1", data["handle"].asString)
        }
    }
    @Test fun transportFailureClosesSessionWithoutReplay() {
        backend.answer = { _, _ -> CompletableFuture.failedFuture(IOException("socket closed")) }
        router().use { router ->
            val session = init(router)
            assertTrue(result(call(router, session, "repl_eval", "code" to "sideEffect()"))["isError"].asBoolean)
            assertTrue(backend.closed)
            assertEquals(404, call(router, session, "repl_eval", "code" to "sideEffect()").status)
            assertEquals(listOf("eval"), backend.operations)
        }
    }
    @Test fun enforcesClientQuotaAndReclaimsIdleSessions() {
        var now = 0L
        val backends = mutableListOf<Backend>()
        McpRouter("secret", "127.0.0.1:1234", McpPermissions(), { Backend().also { backends += it } }, clock = { now }, idleNanos = 100).use { router ->
            repeat(4) { init(router) }
            assertEquals(-32000, error(router.handle(request("initialize", McpJson.objectOf("protocolVersion" to "2025-11-25", "capabilities" to JsonObject(), "clientInfo" to mapOf("name" to "test", "version" to "1"))))))
            now = 101; router.expireIdle()
            assertTrue(backends.all { it.closed }); assertEquals(0, router.clientCount)
            init(router); assertEquals(1, router.clientCount)
        }
        assertTrue(backends.all { it.closed })
    }
    @Test fun stopDuringHandshakeClosesTheLateBackend() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val router = McpRouter("secret", "127.0.0.1:1234", McpPermissions(), { entered.countDown(); release.await(3, TimeUnit.SECONDS); backend })
        val worker = Executors.newSingleThreadExecutor()
        try {
            val future = worker.submit<McpHttpResponse> { router.handle(request("initialize", McpJson.objectOf("protocolVersion" to "2025-11-25", "capabilities" to JsonObject(), "clientInfo" to mapOf("name" to "test", "version" to "1")))) }
            assertTrue(entered.await(3, TimeUnit.SECONDS)); router.close(); release.countDown()
            assertEquals(503, future.get(3, TimeUnit.SECONDS).status)
            assertTrue(backend.closed); assertEquals(0, router.clientCount)
        } finally { release.countDown(); router.close(); worker.shutdownNow() }
    }
    @Test fun versionAndWorkspaceWritesRequireExplicitSnapshotPermission() {
        val permissions = McpPermissions(execution=true)
        for (name in listOf("repl_snapshot_restore_version", "repl_workspace_export", "repl_workspace_import")) {
            val tool = McpTools.all.single { it.name == name }
            assertFalse(tool.enabled(permissions))
            assertTrue(tool.enabled(permissions.copy(snapshotWrites=true)))
            assertFalse(tool.enabled(permissions.copy(snapshotWrites=true, execution=false)))
        }
        for (name in listOf("repl_snapshot_versions", "repl_snapshot_provenance", "repl_notebook_symbols"))
            assertTrue(McpTools.all.single { it.name == name }.enabled(McpPermissions()))
    }
}
