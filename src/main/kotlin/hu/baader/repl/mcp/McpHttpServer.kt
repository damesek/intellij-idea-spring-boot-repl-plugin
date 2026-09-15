package hu.baader.repl.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.*

internal class McpHttpServer private constructor(
    private val server: HttpServer, private val executor: ExecutorService,
    private val timer: ScheduledExecutorService, val router: McpRouter, private val token: String
) : AutoCloseable {
    val url = "http://127.0.0.1:${server.address.port}/mcp"
    fun clientConfig(): String = McpJson.gson.newBuilder().setPrettyPrinting().create().toJson(McpJson.objectOf(
        "mcpServers" to mapOf("spring-boot-repl" to mapOf("url" to url, "headers" to mapOf("Authorization" to "Bearer $token")))))
    override fun close() { router.close(); server.stop(0); timer.shutdownNow(); executor.shutdownNow() }
    companion object {
        fun start(port: Int, permissions: McpPermissions, backend: () -> McpBackend, changed: () -> Unit): McpHttpServer {
            require(port in 0..65535) { "Port must be 0..65535 (0 selects a free port)" }
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 16)
            val executor = ThreadPoolExecutor(8, 8, 30, TimeUnit.SECONDS, ArrayBlockingQueue(32),
                { r -> Thread(r, "sb-repl-mcp-http").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
            val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "sb-repl-mcp-expiry").apply { isDaemon = true } }
            val token = McpRouter.secret()
            val router = McpRouter(token, "127.0.0.1:${server.address.port}", permissions, backend, changed)
            try {
                server.createContext("/mcp") { exchange -> serve(exchange, router) }
                server.executor = executor
                server.start()
                timer.scheduleAtFixedRate({ router.expireIdle() }, 1, 1, TimeUnit.MINUTES)
                return McpHttpServer(server, executor, timer, router, token)
            } catch (e: Exception) { router.close(); server.stop(0); timer.shutdownNow(); executor.shutdownNow(); throw e }
        }

        internal fun serve(exchange: HttpExchange, router: McpRouter) {
            exchange.use {
                val request = McpHttpRequest(exchange.requestMethod, exchange.requestURI.toString(),
                    exchange.requestHeaders.entries.associate { it.key to it.value.joinToString(",") })
                val response = router.preflight(request) ?: try {
                    if (request.method == "POST") {
                        val bytes = exchange.requestBody.readNBytes(McpJson.MAX_REQUEST_BYTES + 1)
                        if (bytes.size > McpJson.MAX_REQUEST_BYTES) McpHttpResponse(413)
                        else {
                            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                            router.handle(request.copy(body = text))
                        }
                    } else router.handle(request)
                } catch (_: java.nio.charset.CharacterCodingException) { McpHttpResponse(400) }
                catch (_: Exception) { McpHttpResponse(500) }
                exchange.responseHeaders.set("Cache-Control", "no-store")
                exchange.responseHeaders.set("X-Content-Type-Options", "nosniff")
                response.headers.forEach { (key, value) -> exchange.responseHeaders.set(key, value) }
                val body = response.body?.let { McpJson.gson.toJson(it).toByteArray(Charsets.UTF_8) }
                if (body == null) exchange.sendResponseHeaders(response.status, -1)
                else {
                    exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
                    exchange.sendResponseHeaders(response.status, body.size.toLong())
                    exchange.responseBody.write(body)
                }
            }
        }
    }
}
