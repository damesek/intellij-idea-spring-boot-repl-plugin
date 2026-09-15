package hu.baader.repl.mcp

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import hu.baader.repl.trace.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Actual project service and EDT bridge, with independent evaluator requests forbidden. */
class McpRecordingIdeTest : BasePlatformTestCase() {
    private fun <T> await(future: CompletableFuture<T>): T {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!future.isDone && System.nanoTime() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue(); Thread.sleep(10)
        }
        assertTrue("Timed out waiting for MCP recording bridge", future.isDone)
        return future.get(1, TimeUnit.SECONDS)
    }
    private fun backend(permissions: McpPermissions = McpPermissions(recordingAccess=true, execution=true)) =
        McpRecordingBackend(object : McpBackend {
            override fun request(operation: String, arguments: Map<String, String>): CompletableFuture<Map<String, String>> {
                fail("A recording request must not use the evaluator: $operation")
                return CompletableFuture.completedFuture(emptyMap())
            }
            override fun close() {}
        }, IdeMcpRecordingAccess(project), permissions)

    fun testReadsOpenedRecordingAndSelectsTheExactCapturedSourceWithoutRunningJava() {
        val controller = RecordingController.get(project)
        myFixture.addFileToProject("example/Service.java", "package example; public class Service { public int run(int amount) { return amount + 1; } }")
        val sources = RecordingSource.capture(project, listOf("example.Service"))
        controller.open(CallRecording(BrowserFixture.id, BrowserFixture.calls(), sources, 9))
        var selectedSource = ""
        controller.navigate = { call, open ->
            assertTrue(open)
            selectedSource = controller.recording!!.source(call)!!.text
        }
        try {
            backend().use { backend ->
                fun request(op: String, vararg args: Pair<String, String>) =
                    McpJson.parse(await(backend.request("recording/$op", args.toMap())).getValue("recording-json")).asJsonObject
                assertTrue(request("status")["offline"].asBoolean)
                assertEquals(9L, request("status")["contextEpoch"].asLong)
                val source = request("source", "recording" to BrowserFixture.id, "call" to "1")
                assertTrue(source["text"].asString.contains("return amount + 1"))
                assertEquals(1, source["methodLine"].asInt)
                request("select", "recording" to BrowserFixture.id, "call" to "3")
                assertEquals(3L, controller.selected); assertFalse(controller.followLatest)
                assertEquals(sources.single().text, selectedSource)
                assertEquals(BrowserFixture.id, controller.recording!!.id)
            }
            assertEquals(6, controller.recording!!.calls.size) // Closing MCP retains the shared recording.
        } finally { controller.navigate = null }
    }

    fun testClosingBeforeEdtDispatchPreventsQueuedSelection() {
        val controller = RecordingController.get(project)
        controller.open(CallRecording(BrowserFixture.id, BrowserFixture.calls()))
        controller.select(1, false)
        val backend = backend()
        val pending = backend.request("recording/select", mapOf("recording" to BrowserFixture.id, "call" to "3"))
        backend.close() // EDT has not dispatched the queued request.
        assertTrue(await(pending).containsKey("err"))
        assertEquals(1L, controller.selected)
    }

    fun testDisconnectedStartReportsFailureAndStaleStopDoesNotChangeTheRecording() {
        val controller = RecordingController.get(project)
        controller.open(CallRecording(BrowserFixture.id, BrowserFixture.calls()))
        backend(McpPermissions(recordingAccess=true, execution=true, captureChanges=true)).use { backend ->
            val failed = await(backend.request("recording/start", mapOf("expected" to BrowserFixture.id, "classes" to "example.Service")))
            assertTrue(failed.toString(), failed["err"]!!.contains("Connect"))
            val stale = await(backend.request("recording/stop", mapOf("recording" to "stale")))
            assertTrue(stale.toString(), stale["err"]!!.contains("changed"))
            assertEquals(BrowserFixture.id, controller.recording!!.id)
            assertFalse(controller.active); assertFalse(controller.starting)
        }
    }
}
