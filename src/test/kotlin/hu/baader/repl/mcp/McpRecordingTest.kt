package hu.baader.repl.mcp

import com.google.gson.JsonObject
import hu.baader.repl.protocol.RecordedCall
import hu.baader.repl.protocol.ValueTree
import hu.baader.repl.trace.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal class RecordingAccessFixture : McpRecordingAccess {
    var state = withRecord(CallRecording(BrowserFixture.id, BrowserFixture.calls()), offline = true)
    val mutations = mutableListOf<String>()
    var closed = false
    override fun read() = CompletableFuture.completedFuture(state)
    override fun start(expected: String, classes: List<String>): CompletableFuture<McpRecordingState> {
        mutations += "start"; require(expected == (state.recording?.id ?: "none"))
        state = state.copy(active = true, offline = false)
        return CompletableFuture.completedFuture(state)
    }
    override fun stop(recording: String): CompletableFuture<McpRecordingState> {
        require(state.recording?.id == recording); mutations += "stop"; state = state.copy(active = false)
        return CompletableFuture.completedFuture(state)
    }
    override fun select(recording: String, call: Long): CompletableFuture<McpRecordingState> {
        require(state.recording?.id == recording); mutations += "select"; state = state.copy(selected = call)
        return CompletableFuture.completedFuture(state)
    }
    override fun close() { closed = true }
    companion object {
        fun withRecord(record: CallRecording, offline: Boolean = false) = McpRecordingState(record,
            offline = offline, downloaded = record.calls.map { it.id() }.toSet())
    }
}

internal class RecordingMcpHarness(
    val access: RecordingAccessFixture = RecordingAccessFixture(),
    val permissions: McpPermissions = McpPermissions(recordingAccess = true)
) : AutoCloseable {
    val runtimeOps = mutableListOf<String>()
    val backends = mutableListOf<McpRecordingBackend>()
    private var id = 0
    val router = McpRouter("secret", "127.0.0.1:1234", permissions, {
        McpRecordingBackend(object : McpBackend {
            override fun request(operation: String, arguments: Map<String, String>): CompletableFuture<Map<String, String>> {
                runtimeOps += operation
                return CompletableFuture.completedFuture(mapOf("value" to "independent-session"))
            }
            override fun close() {}
        }, access, permissions).also { backends += it }
    })
    private val headers = mapOf("Host" to "127.0.0.1:1234", "Authorization" to "Bearer secret",
        "Content-Type" to "application/json", "Accept" to "application/json, text/event-stream")
    fun rpc(method: String, params: JsonObject = JsonObject(), session: String? = null, notification: Boolean = false): McpHttpResponse {
        val json = McpJson.objectOf("jsonrpc" to "2.0", "method" to method, "params" to params)
        if (!notification) json.addProperty("id", ++id)
        return router.handle(McpHttpRequest("POST", "/mcp", headers + if (session != null) mapOf("MCP-Session-Id" to session) else emptyMap(), json.toString()))
    }
    fun connect(): String {
        val response = rpc("initialize", McpJson.objectOf("protocolVersion" to "2025-11-25",
            "capabilities" to emptyMap<String, String>(), "clientInfo" to mapOf("name" to "recording-test", "version" to "1")))
        val session = response.headers.getValue("MCP-Session-Id")
        rpc("notifications/initialized", session = session, notification = true)
        return session
    }
    fun call(session: String, name: String, vararg args: Pair<String, Any?>) = rpc("tools/call",
        McpJson.objectOf("name" to name, "arguments" to McpJson.objectOf(*args)), session)
    fun ok(session: String, name: String, vararg args: Pair<String, Any?>): JsonObject {
        val response = call(session, name, *args)
        assertFalse(response.body.toString(), response.body!!.has("error"))
        val result = response.body!!.getAsJsonObject("result")
        assertFalse(result.toString(), result["isError"].asBoolean)
        assertEquals(result["structuredContent"], McpJson.parse(result.getAsJsonArray("content")[0].asJsonObject["text"].asString))
        return result.getAsJsonObject("structuredContent")
    }
    fun failed(session: String, name: String, vararg args: Pair<String, Any?>) {
        val response = call(session, name, *args)
        assertTrue(response.body.toString(), response.body!!.getAsJsonObject("result")["isError"].asBoolean)
    }
    override fun close() = router.close()
}

class McpRecordingTest {
    @Test fun queuedAsyncWorkInvalidatesTheEventResourceBeforeAnyCallCompletes() {
        RecordingMcpHarness().use { h ->
            val session = h.connect()
            val before = h.ok(session, "repl_recording_status")["view"].asString
            val backend = h.backends.single()
            val first = backend.request("notifications/poll", emptyMap()).get().getValue("ide-recording-revision")
            val record = h.access.state.recording!!
            h.access.state = h.access.state.copy(recording = record.copy(async = AsyncEvidence(true, true, 1, 0)))
            val second = backend.request("notifications/poll", emptyMap()).get().getValue("ide-recording-revision")
            assertNotEquals(first, second)
            val status = h.ok(session, "repl_recording_status")
            assertNotEquals(before, status["view"].asString)
            assertFalse(status["complete"].asBoolean)
            h.failed(session, "repl_recording_calls", "recording" to record.id, "view" to before)
        }
    }

    @Test fun permissionsGateSharingAndMutationsWithoutBorrowingTheIdeEvaluator() {
        RecordingMcpHarness(permissions = McpPermissions(execution = true, captureChanges = true)).use { h ->
            val session = h.connect()
            val listed = h.rpc("tools/list", session = session).body.toString()
            McpRecordingTools.all.forEach { t ->
                assertFalse(listed.contains(t.name)); h.failed(session, t.name)
            }
            assertTrue(h.runtimeOps.isEmpty()); assertTrue(h.access.mutations.isEmpty())
        }
        RecordingMcpHarness().use { h ->
            val session = h.connect()
            val listed = h.rpc("tools/list", session = session).body.toString()
            assertTrue(listed.contains("repl_recording_values"))
            for (name in listOf("repl_recording_start", "repl_recording_stop", "repl_recording_select")) {
                assertFalse(listed.contains(name)); h.failed(session, name)
            }
            h.ok(session, "repl_recording_status")
            assertTrue(h.runtimeOps.isEmpty()); assertTrue(h.access.mutations.isEmpty())
        }
        assertFalse(McpRecordingTools.all.single { it.operation == "recording/start" }.enabled(McpPermissions(recordingAccess=true, execution=true)))
        assertFalse(McpRecordingTools.all.first().enabled(McpPermissions(recordingAccess=true, allowedTools=emptySet())))
        assertEquals(82, McpTools.all.size); assertEquals(82, McpTools.all.map { it.name }.toSet().size)
    }

    @Test fun filteredGraphPagesKeepCallerPathsAndTimelineUsesRecordedThreads() {
        RecordingMcpHarness().use { h ->
            val s = h.connect(); val id = BrowserFixture.id
            val status = h.ok(s, "repl_recording_status")
            assertTrue(status["offline"].asBoolean)
            val first = h.ok(s, "repl_recording_calls", "recording" to id, "query" to "ÁRVÍZ", "collapsed" to "1,2", "limit" to 1)
            assertEquals(3, first["totalRows"].asInt)
            assertTrue(first.getAsJsonArray("nodes")[0].asJsonObject["contextOnly"].asBoolean)
            val last = h.ok(s, "repl_recording_calls", "recording" to id, "query" to "ÁRVÍZ", "offset" to 2, "view" to first["view"].asString)
            assertEquals(3, last.getAsJsonArray("nodes")[0].asJsonObject["id"].asInt)
            assertFalse(last.getAsJsonArray("nodes")[0].asJsonObject["contextOnly"].asBoolean)
            val folded = h.ok(s, "repl_recording_calls", "recording" to id, "collapsed" to "1")
            assertEquals(3, folded.getAsJsonArray("nodes")[0].asJsonObject["hiddenCalls"].asInt)
            val timeline = h.ok(s, "repl_recording_timeline", "recording" to id, "thread" to 2, "from-ms" to 40, "to-ms" to 70)
            assertFalse(timeline["asyncLinksInferred"].asBoolean)
            assertEquals(listOf(5L, 6L), timeline.getAsJsonArray("spans").map { it.asJsonObject["id"].asLong })
            val detail = h.ok(s, "repl_recording_call", "recording" to id, "call" to 3)
            assertEquals(listOf(1, 2, 3), detail.getAsJsonArray("breadcrumb").map { it.asInt })
            assertEquals(2, detail["previous"].asInt); assertEquals(4, detail["next"].asInt)
            assertTrue(h.runtimeOps.isEmpty())
        }
    }

    @Test fun valuesPreserveDuplicateNamesAndSupportLongUnicodeTextAndJsonChildren() {
        RecordingMcpHarness().use { h ->
            val value = "Árvíz ☃ ".repeat(600)
            val call = BrowserFixture.call(input=BrowserFixture.obj("input",
                BrowserFixture.number("same", 1), ValueTree.leaf("same", "STRING", "String", value)),
                output=ValueTree.leaf("result", "STRING", "String", """{"nested":{"ok":true}}"""))
            h.access.state = RecordingAccessFixture.withRecord(CallRecording(call.recording(), listOf(call)))
            val s = h.connect()
            val root = h.ok(s, "repl_recording_values", "recording" to call.recording(), "call" to 1, "part" to "input", "limit" to 1)
            assertEquals(1, root["nextOffset"].asInt)
            assertEquals("/0", root.getAsJsonArray("children")[0].asJsonObject["path"].asString)
            val child = h.ok(s, "repl_recording_values", "recording" to call.recording(), "call" to 1, "part" to "input", "path" to "/1",
                "text-offset" to 8, "text-limit" to 50).getAsJsonObject("node")
            assertEquals(value.drop(8).take(50), child["text"].asString); assertEquals(58, child["nextTextOffset"].asInt)
            val json = h.ok(s, "repl_recording_values", "recording" to call.recording(), "call" to 1, "part" to "result", "path" to "/0/0")
            assertEquals("BOOLEAN", json.getAsJsonObject("node")["kind"].asString)
            h.failed(s, "repl_recording_values", "recording" to call.recording(), "call" to 1, "part" to "input", "path" to "/99")
        }
    }

    @Test fun staleIdsAndViewsCannotMixNewRecordingsOrNewlyDownloadedPayloads() {
        RecordingMcpHarness().use { h ->
            val full = BrowserFixture.call()
            h.access.state = McpRecordingState(CallRecording(full.recording(), listOf(full.header())))
            val s = h.connect(); val old = h.ok(s, "repl_recording_status")
            val absent = h.ok(s, "repl_recording_values", "recording" to full.recording(), "call" to 1, "part" to "input")
            assertFalse(absent["downloaded"].asBoolean); assertFalse(absent["valueAvailable"].asBoolean)
            h.failed(s, "repl_recording_pin", "recording" to full.recording(), "call" to 1)
            h.access.state = RecordingAccessFixture.withRecord(CallRecording(full.recording(), listOf(full)))
            h.failed(s, "repl_recording_calls", "recording" to full.recording(), "view" to old["view"].asString, "offset" to 1)
            h.failed(s, "repl_recording_call", "recording" to UUID.randomUUID().toString(), "call" to 1)
            h.ok(s, "repl_recording_call", "recording" to full.recording(), "call" to 1)
            assertEquals(1, h.router.clientCount)
        }
    }

    @Test fun referencePinsSurviveNewRecordingsAndAreIsolatedBetweenClients() {
        RecordingMcpHarness().use { h ->
            val first = BrowserFixture.call(input=BrowserFixture.obj("input", BrowserFixture.number("amount", 1)))
            h.access.state = RecordingAccessFixture.withRecord(CallRecording(first.recording(), listOf(first)))
            val a = h.connect(); val b = h.connect()
            val pin = h.ok(a, "repl_recording_pin", "recording" to first.recording(), "call" to 1)["reference"].asString
            val current = BrowserFixture.call(input=BrowserFixture.obj("input", BrowserFixture.number("amount", 2)))
            val second = RecordedCall(UUID.randomUUID().toString(), 1, 0, 1, current.className(), current.method(), current.descriptor(), current.parameterNames(),
                current.threadId(), current.threadName(), current.startedAt(), current.durationNanos(), current.status(), current.summary(),
                current.input(), current.output(), current.exception(), -1, current.revision())
            h.access.state = RecordingAccessFixture.withRecord(CallRecording(second.recording(), listOf(second)))
            val diff = h.ok(a, "repl_recording_compare", "recording" to second.recording(), "reference" to pin, "after" to 1)
            assertEquals("input['amount']", diff.getAsJsonArray("rows")[0].asJsonObject["path"].asString)
            assertFalse(diff["partial"].asBoolean)
            h.failed(b, "repl_recording_compare", "recording" to second.recording(), "reference" to pin, "after" to 1)
        }
    }

    @Test fun secretsAreRedactedBeforeSearchSummariesComparisonAndSourcePaging() {
        RecordingMcpHarness().use { h ->
            val password = "private-unlabelled-value-123"
            val c = BrowserFixture.call(input=BrowserFixture.obj("input", ValueTree.leaf("password", "STRING", "String", password)),
                output=ValueTree.leaf("result", "STRING", "String", """{"apiKey":"$password","ok":42}"""))
            val sourceText = """class Service { String password = "$password"; }"""
            val source = CapturedSource(c.className(), "Service.java", sourceText, CapturedSource.hash(sourceText), mapOf("run(I)I" to 0))
            h.access.state = RecordingAccessFixture.withRecord(CallRecording(c.recording(), listOf(c), listOf(source)))
            val s = h.connect()
            for (tool in listOf("repl_recording_calls", "repl_recording_call", "repl_recording_timeline", "repl_recording_pin"))
                assertFalse(h.ok(s, tool, *if(tool in listOf("repl_recording_calls", "repl_recording_timeline")) arrayOf("recording" to c.recording())
                    else arrayOf("recording" to c.recording(), "call" to 1)).toString().contains(password))
            val hidden = h.ok(s, "repl_recording_calls", "recording" to c.recording(), "query" to password)
            assertEquals(0, hidden["totalRows"].asInt)
            val values = h.ok(s, "repl_recording_values", "recording" to c.recording(), "call" to 1, "part" to "input", "path" to "/0")
            assertFalse(values.toString().contains(password)); assertTrue(values["redacted"].asBoolean)
            var offset = 0; val collected = StringBuilder()
            do {
                val page = h.ok(s, "repl_recording_source", "recording" to c.recording(), "call" to 1, "offset" to offset, "limit" to 7)
                collected.append(page["text"].asString)
                offset = page["nextOffset"]?.asInt ?: -1
            } while (offset >= 0)
            assertFalse(collected.toString().contains(password)); assertTrue(collected.toString().contains("[REDACTED]"))
            assertEquals(hu.baader.repl.protocol.SensitiveValues.redact(sourceText), collected.toString())
            val diff = h.ok(s, "repl_recording_compare", "recording" to c.recording(), "before" to 1, "after" to 1)
            assertTrue(diff["partial"].asBoolean); assertTrue(diff["redactedComparison"].asBoolean)
        }
    }

    @Test fun permissionsAllowOnlyExplicitControlsAndRuntimeRequestsStaySeparate() {
        RecordingMcpHarness(permissions=McpPermissions(recordingAccess=true, execution=true, captureChanges=true)).use { h ->
            val s = h.connect(); val id = BrowserFixture.id
            h.ok(s, "repl_recording_start", "expected" to id, "classes" to "example.Service")
            h.ok(s, "repl_recording_select", "recording" to id, "call" to 3)
            assertEquals(3L, h.access.state.selected)
            h.failed(s, "repl_recording_stop", "recording" to UUID.randomUUID().toString())
            h.ok(s, "repl_recording_stop", "recording" to id)
            assertEquals(listOf("start", "select", "stop"), h.access.mutations)
            assertTrue(h.runtimeOps.isEmpty())
            h.ok(s, "repl_status"); assertEquals(listOf("describe"), h.runtimeOps)
            h.failed(s, "repl_recording_start", "expected" to id, "classes" to "example.Service\nexample.Service")
            h.ok(s, "repl_recording_status")
        }
    }

    @Test fun invalidArgumentsAreRejectedAndLargeGraphsRemainPageable() {
        RecordingMcpHarness().use { h ->
            h.access.state = RecordingAccessFixture.withRecord(CallRecording(BrowserFixture.id, (1L..200L).map { BrowserFixture.call(it) }))
            val s = h.connect(); val id = BrowserFixture.id
            val collected = mutableListOf<Int>(); var offset = 0
            do {
                val response = h.ok(s, "repl_recording_calls", "recording" to id, "offset" to offset, "limit" to 50)
                assertFalse(response.toString(), response.has("truncated"))
                assertTrue(response.toString().length < h.permissions.maxResultChars)
                collected += response.getAsJsonArray("nodes").map { it.asJsonObject["id"].asInt }
                offset = response["nextOffset"]?.asInt ?: -1
            } while (offset >= 0)
            assertEquals((1..200).toList(), collected)
            for (args in listOf(arrayOf("from-ms" to 1), arrayOf("from-ms" to 5, "to-ms" to 2),
                arrayOf("errors-only" to "yes"), arrayOf("limit" to 0), arrayOf("limit" to 51), arrayOf("code" to "sideEffect()"))) {
                val result = h.call(s, "repl_recording_calls", "recording" to id, *args).body!!
                assertEquals(result.toString(), -32602, result.getAsJsonObject("error")["code"].asInt)
            }
            assertTrue(h.runtimeOps.isEmpty())
        }
    }

    @Test fun smallResponseBudgetIsExplicitAndNeverReexecutes() {
        RecordingMcpHarness(permissions=McpPermissions(recordingAccess=true, maxResultChars=1024)).use { h ->
            val s = h.connect()
            val data = h.ok(s, "repl_recording_call", "recording" to BrowserFixture.id, "call" to 1)
            assertTrue(data.toString(), data["truncated"].asBoolean)
            assertEquals(BrowserFixture.id, data["recording"].asString)
            assertTrue(data.toString().length < 512)
            assertTrue(h.runtimeOps.isEmpty())
        }
    }
}
