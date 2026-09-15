package hu.baader.repl.nrepl

import hu.baader.repl.protocol.Bencode
import hu.baader.repl.protocol.ReplProtocol
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.*

class NreplClientTest {
    private class Peer(private val eval: (Map<String, Any>, OutputStream) -> Unit) : AutoCloseable {
        private val requests = PipedInputStream(65536)
        private val clientOutput = PipedOutputStream(requests)
        private val responses = PipedOutputStream()
        private val clientInput = PipedInputStream(responses, 65536)
        val socket = object : Socket() {
            override fun connect(endpoint: SocketAddress?, timeout: Int) {}
            override fun setTcpNoDelay(on: Boolean) {}
            override fun getInputStream(): InputStream = clientInput
            override fun getOutputStream(): OutputStream = clientOutput
            override fun close() { clientInput.close(); clientOutput.close() }
        }
        private val executor = Executors.newSingleThreadExecutor { Thread(it, "test-peer").apply { isDaemon = true } }
        val serving = executor.submit {
            try {
                while (true) {
                    val message = Bencode.read(requests) ?: break
                    check(message["token"] == "x".repeat(64))
                    val common = mapOf("id" to message["id"]!!, "status" to listOf("done"))
                    when (message["op"]) {
                        "clone" -> Bencode.write(common + ("new-session" to "test-session"), responses)
                        "describe" -> Bencode.write(common + mapOf("protocol-version" to ReplProtocol.VERSION, "pid" to 123L, "ops" to mapOf("eval" to emptyMap<String, String>())), responses)
                        else -> { check(message["session"] == "test-session"); eval(message, responses) }
                    }
                }
            } catch (_: IOException) {}
        }
        fun client() = NreplClient("loopback", 0, "x".repeat(64), { socket })
        override fun close() { socket.close(); responses.close(); requests.close(); executor.shutdownNow() }
    }
    @Test fun waitsForDoneAndPreservesLateErrorsAndOutput() {
        val first = CountDownLatch(1); val finish = CountDownLatch(1)
        Peer { message, output ->
            Bencode.write(mapOf("id" to message["id"]!!, "out" to "árvíz 🧪"), output)
            first.countDown(); check(finish.await(5, TimeUnit.SECONDS))
            Bencode.write(mapOf("id" to message["id"]!!, "out" to "\n", "err" to "late failure", "status" to listOf("error","done")), output)
        }.use { peer ->
            peer.client().use { client ->
                client.connect(123)
                val response = client.request("eval", mapOf("code" to "code"))
                assertTrue(first.await(5,TimeUnit.SECONDS))
                try { response.get(100,TimeUnit.MILLISECONDS); fail("Completed before done") } catch (_: TimeoutException) {}
                finish.countDown()
                val result=response.get(5,TimeUnit.SECONDS)
                assertEquals("árvíz 🧪\n",result["out"]);assertEquals("late failure",result["err"])
            }
        }
    }
    @Test fun multiplexedResponsesKeepTheirRequestIdsAndUnicode() {
        Peer { message, output ->
            val buffer=ByteArrayOutputStream()
            Bencode.write(mapOf("id" to message["id"]!!,"value" to message["code"]!!,"status" to listOf("done")),buffer)
            for (byte in buffer.toByteArray()) { output.write(byte.toInt()); output.flush() }
        }.use { peer ->
            peer.client().use { client ->
                client.connect(123)
                repeat(20) { batch ->
                    val requests=(0 until 20).map { i ->
                        val code="érték 🧪 " + (batch*20+i)
                        code to client.request("eval", mapOf("code" to code))
                    }
                    requests.forEach { (code,future) -> assertEquals(code,future.get(5,TimeUnit.SECONDS)["value"]) }
                }
            }
        }
    }
    @Test fun eofClosesClientAndFailsPendingRequests() {
        val disconnected=CountDownLatch(1)
        Peer { _, output -> output.close() }.use { peer ->
            peer.client().use { client ->
                client.connect(123)
                client.onDisconnect={ disconnected.countDown() }
                val pending=client.request("eval",mapOf("code" to "1"))
                try { pending.get(5,TimeUnit.SECONDS);fail("EOF must fail pending request") } catch (_: ExecutionException) {}
                assertTrue(disconnected.await(5,TimeUnit.SECONDS))
                assertTrue(client.request("eval").isCompletedExceptionally)
            }
        }
    }
    @Test fun rejectsEndpointFromDifferentProcess() {
        Peer { _, _ -> }.use { peer ->
            peer.client().use { client ->
                try { client.connect(999);fail("Wrong process accepted") } catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("different process")) }
            }
        }
    }
}
