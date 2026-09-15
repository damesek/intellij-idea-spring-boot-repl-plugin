package hu.baader.repl.trace

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import hu.baader.repl.nrepl.NreplService
import hu.baader.repl.protocol.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/** Real service/transport/controller integration; the peer supplies recorded evidence without evaluating Java. */
class RecordingConnectionIdeTest : BasePlatformTestCase() {
    private class Peer : AutoCloseable {
        val id=UUID.randomUUID().toString()
        val call=RecordedCall(id,1,0,1,"example.Service","run","(I)I","amount",1,"request",1700000000000,1234,"SUCCESS","42",
            ValueTree.leaf("amount","NUMBER","int","41").encode(),ValueTree.leaf("result","NUMBER","int","42").encode(),"",7,1)
        val endpoint=Files.createTempFile("recording-peer-",".properties")
        private val listener=ServerSocket(0,1,InetAddress.getLoopbackAddress())
        private val session=UUID.randomUUID().toString()
        private val token=UUID.randomUUID().toString()+UUID.randomUUID()
        @Volatile private var socket: Socket?=null
        val ops=CopyOnWriteArrayList<String>()
        val pending=AtomicReference<Map<String,Any>?>()
        @Volatile var defer=false
        @Volatile private var active=false
        init {
            EndpointFile.write(endpoint,EndpointFile.Endpoint(listener.localPort,token,ProcessHandle.current().pid()))
            Thread({
                try {
                    listener.accept().use { client -> socket=client
                        while(!client.isClosed) {
                            val request=Bencode.read(client.getInputStream()) ?: break
                            check(request["token"]==token);val op=request["op"].toString();ops+=op
                            val response: Map<String,Any> = when(op) {
                                "clone" -> mapOf("new-session" to session)
                                "describe" -> mapOf("protocol-version" to ReplProtocol.VERSION,"pid" to ProcessHandle.current().pid(),"context-epoch" to 1L)
                                "bind-spring" -> mapOf("value" to "true","context-epoch" to 1L)
                                "execution/policy" -> mapOf("execution-mode" to "LIVE","transaction-manager" to "","timeout-ms" to "30000")
                                "trace/record" -> { active=true;history() }
                                "trace/stop" -> { active=false;history() }
                                "trace/history" -> history()
                                "trace/call" -> { check(request["call-id"]=="1");if(defer) { pending.set(request);continue };mapOf("value" to call.encode()) }
                                else -> error("Unexpected code-executing request: $op")
                            }
                            reply(request,response)
                        }
                    }
                } catch (_: java.net.SocketException) { }
            },"recording-test-peer").apply { isDaemon=true;start() }
        }
        private fun history(): Map<String,Any> = mapOf("recording" to id,"value" to call.header().encode(),"active" to active.toString(),"classes" to if(active) "example.Service" else "","context-epoch" to 1L)
        private fun reply(request: Map<String,Any>, response: Map<String,Any>) { Bencode.write(response+mapOf("op" to request["op"]!!,"id" to request["id"]!!,"session" to session,"status" to listOf("done")),socket!!.getOutputStream()) }
        fun finishPending() { pending.getAndSet(null)?.let { reply(it,mapOf("value" to call.encode())) } }
        override fun close() { socket?.close();listener.close();Files.deleteIfExists(endpoint) }
    }
    private fun waitFor(test: () -> Boolean) {
        val until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8)
        while(!test() && System.nanoTime()<until) { PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();Thread.sleep(10) }
        assertTrue("Timed out waiting for recording state",test())
    }
    private fun prepare(peer: Peer): Pair<NreplService,RecordingController> {
        myFixture.addFileToProject("example/Service.java","package example; public class Service { public int run(int amount) { return amount+1; } }")
        val service=NreplService.getInstance(project);val controller=RecordingController.get(project)
        service.connectEndpoint(peer.endpoint);waitFor { service.state==NreplService.State.READY }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        controller.start(listOf("example.Service"))
        return service to controller
    }
    fun testClassRecordingDownloadsValuesAndKeepsThemAfterDisconnect() {
        Peer().use { peer ->
            val (service,controller)=prepare(peer)
            try {
                waitFor { controller.recording?.id==peer.id && controller.complete() }
                assertEquals("42",ValueTree.decode(controller.recording!!.calls.single().output()).text())
                assertTrue(controller.live(controller.recording!!.calls.single()))
                controller.stop();waitFor { !controller.active }
                service.disconnect();waitFor { controller.offline }
                assertFalse(controller.live(controller.recording!!.calls.single()))
                assertEquals("42",ValueTree.decode(controller.recording!!.calls.single().output()).text())
                assertFalse(peer.ops.any { it in setOf("eval","java-eval","class-reload") })
            } finally { service.disconnect();PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue() }
        }
    }
    fun testMcpControlsWaitForRuntimeAcknowledgementInTheSharedIdeSession() {
        Peer().use { peer ->
            val (service,controller)=prepare(peer)
            val access=hu.baader.repl.mcp.IdeMcpRecordingAccess(project)
            try {
                waitFor { controller.recording?.id==peer.id && controller.complete() }
                val stopped=access.stop(peer.id)
                assertFalse(stopped.isDone)
                waitFor { stopped.isDone }
                assertFalse(stopped.get().active)
                val started=access.start(peer.id,listOf("example.Service"))
                assertFalse(started.isDone)
                waitFor { started.isDone }
                assertTrue(started.get().active);assertEquals(peer.id,started.get().recording!!.id)
                waitFor { controller.complete() }
                assertEquals(2,peer.ops.count { it=="trace/record" })
                assertTrue(peer.ops.contains("trace/stop"))
                assertFalse(peer.ops.any { it in setOf("eval","java-eval","class-reload") })
            } finally {
                access.close();service.disconnect();PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            }
        }
    }
    fun testLateValueResponseCannotReplaceAnOpenedRecording() {
        Peer().use { peer ->
            peer.defer=true;val (service,controller)=prepare(peer)
            try {
                waitFor { peer.pending.get()!=null }
                controller.stop();waitFor { !controller.active }
                val replacement=CallRecording(UUID.randomUUID().toString(),emptyList())
                controller.open(replacement);peer.finishPending()
                repeat(20) { PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();Thread.sleep(10) }
                assertEquals(replacement.id,controller.recording!!.id);assertTrue(controller.recording!!.calls.isEmpty());assertTrue(controller.offline)
            } finally { service.disconnect();PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue() }
        }
    }
    fun testDelayedValuesRefreshThePanelWithoutReopeningSourceWhenNotRequested() {
        Peer().use { peer ->
            peer.defer=true;val (service,controller)=prepare(peer)
            val panel=RecordingPanel(project) { fail("Not an inspect action") }
            val navigations=mutableListOf<Boolean>()
            controller.navigate={ _,open -> navigations+=open }
            fun components(root: java.awt.Container): List<java.awt.Component> = root.components.flatMap {
                listOf(it)+if(it is java.awt.Container) components(it) else emptyList()
            }
            try {
                waitFor { peer.pending.get()!=null }
                assertTrue(controller.followLatest)
                controller.select(1,false)
                assertFalse(controller.downloaded(controller.selectedCall()!!))
                peer.finishPending()
                waitFor { controller.complete() }
                assertTrue(controller.downloaded(controller.selectedCall()!!))
                assertEquals(listOf(false),navigations)
                assertTrue(components(panel).filterIsInstance<javax.swing.JTextArea>().any { it.text=="41" })
                assertTrue(components(panel).filterIsInstance<javax.swing.JTextArea>().any { it.text=="42" })
                assertFalse(peer.ops.any { it in setOf("eval","java-eval","class-reload") })
            } finally {
                controller.navigate=null;com.intellij.openapi.util.Disposer.dispose(panel)
                service.disconnect();PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            }
        }
    }
}
