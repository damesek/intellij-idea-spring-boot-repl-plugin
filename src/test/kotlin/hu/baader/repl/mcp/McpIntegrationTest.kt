package hu.baader.repl.mcp

import com.baader.devrt.ReplHandler
import com.baader.devrt.SpringContextHolder
import hu.baader.repl.nrepl.NreplClient
import hu.baader.repl.protocol.Bencode
import hu.baader.repl.protocol.ReplProtocol
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.springframework.context.support.GenericApplicationContext
import java.io.*
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/** Real MCP -> NreplClient -> bencode -> runtime -> JShell, using pipes instead of OS sockets. */
class McpIntegrationTest {
    @get:Rule val folder = TemporaryFolder()
    private class Peer : McpBackend {
        private val requests = PipedInputStream(65536)
        private val clientOutput = PipedOutputStream(requests)
        private val responses = PipedOutputStream()
        private val clientInput = PipedInputStream(responses, 65536)
        private val handler = ReplHandler()
        private val socket = object : Socket() {
            override fun connect(endpoint: SocketAddress?, timeout: Int) {}
            override fun setTcpNoDelay(on: Boolean) {}
            override fun getInputStream(): InputStream = clientInput
            override fun getOutputStream(): OutputStream = clientOutput
            override fun close() { clientInput.close(); clientOutput.close() }
        }
        private val worker = Executors.newSingleThreadExecutor { Thread(it, "mcp-test-peer").apply { isDaemon = true } }
        private val client = NreplClient("loopback", 0, "test-token", { socket })
        var closed = false
        init {
            worker.submit {
                try {
                    while (true) {
                        val input = Bencode.read(requests) ?: break
                        check(input["token"] == "test-token")
                        val reply = handler.handle(input.getValue("op").toString(), ReplProtocol.strings(input))
                        Bencode.write(reply + ("id" to input.getValue("id")), responses)
                    }
                } catch (_: IOException) { } finally { handler.close() }
            }
            client.connect(ProcessHandle.current().pid())
        }
        override fun request(operation: String, arguments: Map<String, String>) = client.request(operation, arguments)
        override fun close() {
            closed = true; client.close(); requests.close(); responses.close(); handler.close(); worker.shutdownNow()
        }
    }
    private val sequence = AtomicInteger()
    private val headers = mapOf("Host" to "127.0.0.1:1234", "Authorization" to "Bearer secret",
        "Content-Type" to "application/json", "Accept" to "application/json, text/event-stream")
    private fun init(router: McpRouter): String {
        val body = McpJson.objectOf("jsonrpc" to "2.0", "id" to sequence.incrementAndGet(), "method" to "initialize", "params" to mapOf(
            "protocolVersion" to "2025-11-25", "capabilities" to emptyMap<String, String>(), "clientInfo" to mapOf("name" to "integration", "version" to "1")))
        val result = router.handle(McpHttpRequest("POST", "/mcp", headers, body.toString()))
        val id = result.headers.getValue("MCP-Session-Id")
        router.handle(McpHttpRequest("POST", "/mcp", headers + ("MCP-Session-Id" to id),
            McpJson.objectOf("jsonrpc" to "2.0", "method" to "notifications/initialized").toString()))
        return id
    }
    private fun call(router: McpRouter, session: String, name: String, vararg arguments: Pair<String, Any?>) =
        router.handle(McpHttpRequest("POST", "/mcp", headers + ("MCP-Session-Id" to session), McpJson.objectOf("jsonrpc" to "2.0",
            "id" to sequence.incrementAndGet(), "method" to "tools/call", "params" to McpJson.objectOf("name" to name,
                "arguments" to McpJson.objectOf(*arguments))).toString())).body!!.getAsJsonObject("result")

    @Test fun caseAssertionsParametersAndExportAreAvailableOverMcp() {
        val previous=System.getProperty("user.home"); System.setProperty("user.home",folder.root.absolutePath)
        try { McpRouter("secret","127.0.0.1:1234",McpPermissions(execution=true,snapshotWrites=true,caseRuns=true,executionMode="LIVE"),{Peer()}).use { router ->
            val session=init(router)
            fun ok(tool:String,vararg args:Pair<String,Any?>):com.google.gson.JsonObject {
                val response=call(router,session,tool,*args);assertFalse(response.toString(),response["isError"].asBoolean);return response.getAsJsonObject("structuredContent")
            }
            ok("repl_snapshot_import","name" to "input","json" to "1")
            ok("repl_snapshot_import","name" to "expected","json" to "2")
            ok("repl_case_save","name" to "plus","input" to "input","expected" to "expected","type" to "java.lang.Integer",
                "result-expression" to "input + 1","assertions-json" to "{\"numericTolerance\":{\"\":0.01}}",
                "parameters-json" to "[{\"id\":\"first\",\"input\":\"input\",\"expected\":\"expected\"},{\"id\":\"second\",\"input\":\"input\",\"expected\":\"expected\"}]")
            assertEquals(1,ok("repl_case_list").getAsJsonArray("rows").size())
            assertEquals("PASSED",ok("repl_case_run_batch","names" to "plus")["outcome"].asString)
            assertTrue(ok("repl_case_result","name" to "plus","row" to 1)["value"].asString.contains("second"))
            val manifest=ok("repl_case_export_junit","name" to "plus")
            assertTrue(manifest["value"].asString.contains("ReproductionTest.java"))
            assertTrue(ok("repl_case_export_junit","name" to "plus","file" to "src/test/java/reproduction/ReproductionTest.java")["value"].asString.contains("ParameterizedTest"))
        } } finally {System.setProperty("user.home",previous)}
    }

    @Test fun independentClientsKeepVariablesAndAnalysisDoesNotExecute() {
        val peers = mutableListOf<Peer>()
        SpringContextHolder.set(null)
        McpRouter("secret", "127.0.0.1:1234", McpPermissions(execution = true, snapshotWrites = true, snapshotDelete = true, caseRuns = true, captureChanges = true, executionMode = "LIVE"), { Peer().also { peers += it } }).use { router ->
            val a = init(router); val b = init(router)
            val declared = call(router, a, "repl_eval", "code" to "int counter = 41;")
            assertFalse(declared.toString(), declared["isError"].asBoolean)
            assertTrue(call(router, b, "repl_eval", "code" to "counter")["isError"].asBoolean)
            val analysis = call(router, a, "repl_analyze", "code" to "counter = 999;")
            assertFalse(analysis["isError"].asBoolean)
            assertEquals("false", analysis.getAsJsonObject("structuredContent")["analysis-executed"].asString)
            assertEquals("42", call(router, a, "repl_eval", "code" to "++counter").getAsJsonObject("structuredContent")["value"].asString)
            val json = call(router, a, "repl_eval", "code" to "\"{\\\"árvíz\\\":[1,2]}\"")
            val data = json.getAsJsonObject("structuredContent")
            assertTrue(data.toString(), data["jsonPreview"].asBoolean)
            assertEquals("OBJECT", data.getAsJsonObject("preview")["kind"].asString)
            assertTrue(call(router, b, "repl_inspect", "handle" to data["handle"].asString)["isError"].asBoolean)
            val inspected = call(router, a, "repl_inspect", "handle" to data["handle"].asString)
            assertFalse(inspected.toString(), inspected["isError"].asBoolean)
            assertFalse(call(router, a, "repl_reset")["isError"].asBoolean)
            assertTrue(call(router, a, "repl_eval", "code" to "counter")["isError"].asBoolean)
        }
        assertTrue(peers.all { it.closed })
    }
    @Test fun dataSnapshotRoundTripAndCaptureLifecycleUseExistingRuntime() {
        val previous = System.getProperty("user.home")
        System.setProperty("user.home", folder.root.absolutePath)
        try {
            McpRouter("secret", "127.0.0.1:1234", McpPermissions(execution = true, snapshotWrites = true, snapshotDelete = true, caseRuns = true, captureChanges = true, executionMode = "LIVE"), { Peer() }).use { router ->
                val session = init(router)
                fun ok(tool: String, vararg args: Pair<String, Any?>): com.google.gson.JsonObject {
                    val result = call(router, session, tool, *args)
                    assertFalse(result.toString(), result["isError"].asBoolean)
                    return result.getAsJsonObject("structuredContent")
                }
                ok("repl_eval", "code" to "int answer = 42;")
                ok("repl_snapshot_save", "name" to "mcp-test", "var" to "answer")
                ok("repl_eval", "code" to "answer = 99;")
                ok("repl_snapshot_load", "name" to "mcp-test", "var" to "restored")
                assertEquals("42", ok("repl_eval", "code" to "restored")["value"].asString)
                assertTrue(ok("repl_snapshot_list").getAsJsonArray("rows").size() > 0)
                ok("repl_capture_arm", "point" to "mcp-test-point", "name" to "captured", "ttl-ms" to 5000)
                ok("repl_capture_status"); ok("repl_capture_disarm")
                ok("repl_snapshot_delete", "name" to "mcp-test")
            }
        } finally { System.setProperty("user.home", previous) }
    }
    @Test fun springCanBeBoundAfterStartupWithoutDiscardingVariables() {
        SpringContextHolder.set(null)
        try {
            McpRouter("secret", "127.0.0.1:1234", McpPermissions(execution = true, snapshotWrites = true, snapshotDelete = true, caseRuns = true, captureChanges = true, executionMode = "LIVE"), { Peer() }).use { router ->
                val session = init(router)
                assertFalse(call(router, session, "repl_eval", "code" to "int preserved = 7;")["isError"].asBoolean)
                GenericApplicationContext().use { context ->
                    context.refresh(); SpringContextHolder.set(context)
                    val bound = call(router, session, "repl_bind_spring")
                    assertFalse(bound.toString(), bound["isError"].asBoolean)
                    assertEquals("true", bound.getAsJsonObject("structuredContent")["value"].asString)
                    assertEquals("7", call(router, session, "repl_eval", "code" to "preserved").getAsJsonObject("structuredContent")["value"].asString)
                    val beans = call(router, session, "repl_eval", "code" to "ctx.getBeanDefinitionCount()")
                    assertFalse(beans.toString(), beans["isError"].asBoolean)
                }
            }
        } finally { SpringContextHolder.set(null) }
    }
    @Test fun immutableVersionsAndWorkspaceRoundTripThroughMcpAndBencode() {
        val oldHome = System.getProperty("user.home")
        System.setProperty("user.home", folder.root.absolutePath)
        try {
            McpRouter("secret", "127.0.0.1:1234", McpPermissions(execution=true, snapshotWrites=true, executionMode="LIVE"), { Peer() }).use { router ->
                val session = init(router)
                fun ok(tool: String, vararg args: Pair<String, Any?>): com.google.gson.JsonObject {
                    val result = call(router, session, tool, *args)
                    assertFalse(result.toString(), result["isError"].asBoolean)
                    return result.getAsJsonObject("structuredContent")
                }
                ok("repl_snapshot_import", "name" to "saved", "json" to "1")
                val first = com.google.gson.JsonParser.parseString(ok("repl_snapshot_versions", "name" to "saved")["versions-json"].asString).asJsonArray[0].asJsonObject["version"].asString
                ok("repl_snapshot_import", "name" to "saved", "json" to "2")
                ok("repl_snapshot_load", "name" to "saved", "version" to first, "var" to "historical")
                assertEquals("1", ok("repl_eval", "code" to "historical")["value"].asString)
                ok("repl_snapshot_restore_version", "name" to "saved", "version" to first)
                val provenance = com.google.gson.JsonParser.parseString(ok("repl_snapshot_provenance", "name" to "saved")["value"].asString).asJsonObject
                assertEquals(first, provenance["restoredFrom"].asString)
                val symbols = ok("repl_notebook_symbols", "code" to "int unevaluated = 100;")
                assertEquals("false", symbols["analysis-executed"].asString)
                assertFalse(ok("repl_variables").toString().contains("unevaluated"))
                val metadata = folder.newFile("workspace-state.json").toPath()
                java.nio.file.Files.writeString(metadata, hu.baader.repl.workspace.WorkspaceDocument().encode())
                val zip = folder.root.toPath().resolve("workspace.sbrepl-workspace")
                ok("repl_workspace_export", "path" to zip.toString(), "state-path" to metadata.toString())
                assertTrue(java.nio.file.Files.size(zip) > 100)
                val imported = ok("repl_workspace_import", "path" to zip.toString(), "prefix" to "roundtrip")
                val names = com.google.gson.JsonParser.parseString(imported["names-json"].asString).asJsonObject
                ok("repl_snapshot_load", "name" to names["saved"].asString, "var" to "imported")
                assertEquals("1", ok("repl_eval", "code" to "imported")["value"].asString)
            }
        } finally { System.setProperty("user.home", oldHome) }
    }
}
