package hu.baader.repl.nrepl

import hu.baader.repl.protocol.Bencode
import hu.baader.repl.protocol.ReplProtocol
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** Byte-oriented transport. A request completes at status=done, never at its first output frame. */
class NreplClient(private val host: String, private val port: Int, private val token: String, private val socketFactory: () -> Socket = { Socket() }) : AutoCloseable {
    private data class Pending(
        val op: String,
        val response: MutableMap<String, Any> = linkedMapOf(),
        val future: CompletableFuture<Map<String, String>> = CompletableFuture()
    )
    @Volatile private var socket: Socket? = null
    @Volatile private var session: String? = null
    @Volatile private var processId: Long? = null
    fun debuggerTarget(): Pair<String, Long>? = session?.let { id -> processId?.let { pid -> id to pid } }
    private val closed = AtomicBoolean(false)
    private val pending = ConcurrentHashMap<String, Pending>()
    private val handlers = CopyOnWriteArrayList<(Map<String, String>) -> Unit>()
    private val writer = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(64),
        { r -> Thread(r, "sb-repl-writer").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "sb-repl-timeouts").apply { isDaemon = true } }
    var onDisconnect: ((String) -> Unit)? = null

    fun connect(expectedPid: Long? = null): Map<String, String> {
        check(socket == null && !closed.get()) { "Client cannot be reused" }
        try {
            val s = socketFactory()
            socket = s
            s.connect(InetSocketAddress(host, port), 3000)
            s.tcpNoDelay = true
            Thread({ readLoop(s) }, "sb-repl-reader").apply { isDaemon = true; start() }
            val cloned = request("clone").get(15, TimeUnit.SECONDS)
            check(cloned["err"] == null) { cloned["err"].orEmpty() }
            session = cloned["new-session"]?.takeIf { it.isNotBlank() } ?: error("Server did not create a session")
            val capabilities = request("describe").get(10, TimeUnit.SECONDS)
            check(capabilities["err"] == null) { capabilities["err"].orEmpty() }
            check(capabilities["protocol-version"] == ReplProtocol.VERSION) { "Incompatible REPL agent; install the matching agent" }
            if (expectedPid != null) check(capabilities["pid"]?.toLongOrNull() == expectedPid) { "Endpoint belongs to a different process" }
            processId = capabilities["pid"]?.toLongOrNull()
            timer.scheduleAtFixedRate({ if (!closed.get()) request("describe") }, 60, 60, TimeUnit.SECONDS)
            return capabilities
        } catch (e: Exception) { fail(e.message ?: "Handshake failed"); throw e }
    }

    fun request(op: String, extra: Map<String, String> = emptyMap(), id: String = UUID.randomUUID().toString()): CompletableFuture<Map<String, String>> {
        if (closed.get() || socket == null) return CompletableFuture.failedFuture(IOException("REPL is disconnected"))
        if (pending.size >= 64) return CompletableFuture.failedFuture(IOException("REPL request queue is full"))
        val p = Pending(op)
        if (pending.putIfAbsent(id, p) != null) return CompletableFuture.failedFuture(IOException("Duplicate request id"))
        val payload = linkedMapOf<String, Any>("op" to op, "id" to id, "token" to token)
        session?.let { payload["session"] = it }
        extra.filterKeys { it !in setOf("id", "op", "token", "session") }.forEach { (k, v) -> payload[k] = v }
        try {
            val timeout = timer.schedule({
                if (pending.remove(id, p)) p.future.completeExceptionally(TimeoutException("Request timed out; execution may still be running. Use Interrupt."))
            }, 180, TimeUnit.SECONDS)
            p.future.whenComplete { _, _ -> timeout.cancel(false) }
            writer.execute {
                try { Bencode.write(payload, socket?.getOutputStream() ?: throw IOException("Disconnected")) }
                catch (e: Exception) { fail(e.message ?: "Write failed") }
            }
        } catch (e: RejectedExecutionException) {
            pending.remove(id, p)
            p.future.completeExceptionally(IOException("REPL request queue is full or closed", e))
        }
        return p.future
    }

    fun onMessage(handler: (Map<String, String>) -> Unit) { handlers.add(handler) }

    private fun readLoop(s: Socket) {
        try {
            while (!closed.get()) {
                val frame = Bencode.read(s.getInputStream()) ?: throw IOException("REPL process disconnected")
                val id = frame["id"] as? String ?: throw IOException("Response without request id")
                val p = pending[id] ?: continue
                frame.forEach { (key, value) ->
                    when (key) {
                        "out", "stderr", "err" -> {
                            val combined = p.response[key]?.toString().orEmpty() + value.toString()
                            if (combined.length > 262144) throw IOException("Response output limit exceeded")
                            p.response[key] = combined
                        }
                        "values" -> {
                            val values = (p.response[key] as? List<*>).orEmpty() + (value as? List<*>).orEmpty()
                            if (values.size > 1000) throw IOException("Response value limit exceeded")
                            p.response[key] = values
                        }
                        else -> p.response[key] = value
                    }
                }
                if (ReplProtocol.done(frame) && pending.remove(id, p)) {
                    p.response["op"] = p.op
                    val response = ReplProtocol.strings(p.response)
                    handlers.forEach { handler -> runCatching { handler(response) } }
                    p.future.complete(response)
                }
            }
        } catch (e: Exception) { fail(e.message ?: "Read failed") }
    }

    private fun fail(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket?.close() }; socket = null; session = null
        writer.shutdownNow(); timer.shutdownNow()
        pending.values.forEach { it.future.completeExceptionally(IOException(reason)) }
        pending.clear()
        onDisconnect?.invoke(reason)
    }

    override fun close() { fail("Disconnected") }
}
