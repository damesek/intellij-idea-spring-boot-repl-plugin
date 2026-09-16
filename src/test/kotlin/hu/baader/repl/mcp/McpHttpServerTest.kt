package hu.baader.repl.mcp

import com.sun.net.httpserver.*
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.io.*
import java.net.*
import java.net.http.*
import java.time.Duration
import java.util.concurrent.CompletableFuture

class McpHttpServerTest {
    private class Exchange(private val bytes: ByteArray, private val auth: Boolean = true) : HttpExchange() {
        val output = ByteArrayOutputStream()
        private val input = ByteArrayInputStream(bytes)
        private val response = Headers()
        var status = 0
        var finished = false
        var bodyRead = false
        override fun getRequestHeaders() = Headers().apply {
            set("Host", "127.0.0.1:1234"); set("Content-Type", "application/json"); set("Accept", "application/json, text/event-stream")
            if (auth) set("Authorization", "Bearer secret")
        }
        override fun getResponseHeaders() = response
        override fun getRequestURI() = URI("/mcp")
        override fun getRequestMethod() = "POST"
        override fun getHttpContext(): HttpContext? = null
        override fun close() { finished = true }
        override fun getRequestBody(): InputStream { bodyRead = true; return input }
        override fun getResponseBody(): OutputStream = output
        override fun sendResponseHeaders(code: Int, length: Long) { status = code }
        override fun getRemoteAddress() = InetSocketAddress("127.0.0.1", 1235)
        override fun getResponseCode() = status
        override fun getLocalAddress() = InetSocketAddress("127.0.0.1", 1234)
        override fun getProtocol() = "HTTP/1.1"
        override fun getAttribute(name: String?): Any? = null
        override fun setAttribute(name: String?, value: Any?) {}
        override fun setStreams(i: InputStream?, o: OutputStream?) {}
        override fun getPrincipal(): HttpPrincipal? = null
    }
    private fun backend() = object : McpBackend {
        override fun request(operation: String, arguments: Map<String, String>) = CompletableFuture.completedFuture(mapOf("status" to "done", "value" to "42"))
        override fun close() {}
    }
    private fun initialization() = McpJson.objectOf("jsonrpc" to "2.0", "id" to 1, "method" to "initialize", "params" to mapOf(
        "protocolVersion" to "2025-11-25", "capabilities" to emptyMap<String, String>(), "clientInfo" to mapOf("name" to "test", "version" to "1"))).toString()
    @Test fun adapterEnforcesAuthBeforeBodyAndRejectsInvalidUtf8AndOversize() {
        McpRouter("secret", "127.0.0.1:1234", McpPermissions(), ::backend).use { router ->
            val unauth = Exchange(byteArrayOf(), false); McpHttpServer.serve(unauth, router)
            assertEquals(401, unauth.status); assertFalse(unauth.bodyRead); assertTrue(unauth.finished)
            val invalid = Exchange(byteArrayOf(0xC3.toByte(), 0x28)); McpHttpServer.serve(invalid, router)
            assertEquals(400, invalid.status)
            val huge = Exchange(ByteArray(McpJson.MAX_REQUEST_BYTES + 1)); McpHttpServer.serve(huge, router)
            assertEquals(413, huge.status)
            val valid = Exchange(initialization().toByteArray()); McpHttpServer.serve(valid, router)
            assertEquals(200, valid.status)
            assertNotNull(valid.responseHeaders.getFirst("MCP-Session-Id"))
            assertEquals("no-store", valid.responseHeaders.getFirst("Cache-Control"))
            assertEquals("spring-boot-repl", McpJson.parse(valid.output.toString("UTF-8")).asJsonObject.getAsJsonObject("result").getAsJsonObject("serverInfo")["name"].asString)
        }
    }
    @Test fun liveHttpStartInitializeToolsDeleteAndStop() {
        assumeFalse("Sandbox forbids OS sockets; run this test with normal Gradle/CI", java.lang.Boolean.getBoolean("sb.repl.tests.noSockets"))
        val server = McpHttpServer.start(0, McpPermissions(), ::backend) {}
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        val token = McpJson.parse(server.clientConfig()).asJsonObject.getAsJsonObject("mcpServers").getAsJsonObject("spring-boot-repl").getAsJsonObject("headers")["Authorization"].asString
        fun send(method: String, body: String = "", session: String? = null): HttpResponse<String> {
            val builder = HttpRequest.newBuilder(URI(server.url)).timeout(Duration.ofSeconds(5))
                .header("Authorization", token).header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .method(method, HttpRequest.BodyPublishers.ofString(body))
            if (session != null) builder.header("MCP-Session-Id", session)
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        }
        try {
            val initialized = send("POST", initialization()); assertEquals(initialized.body(), 200, initialized.statusCode())
            val session = initialized.headers().firstValue("MCP-Session-Id").orElseThrow()
            assertEquals(202, send("POST", McpJson.objectOf("jsonrpc" to "2.0", "method" to "notifications/initialized").toString(), session).statusCode())
            assertEquals(200, send("POST", McpJson.objectOf("jsonrpc" to "2.0", "id" to 2, "method" to "tools/list").toString(), session).statusCode())
            assertEquals(404, send("GET").statusCode())
            val eventRequest=HttpRequest.newBuilder(URI(server.url)).timeout(Duration.ofSeconds(5))
                .header("Authorization",token).header("MCP-Session-Id",session).header("Accept","text/event-stream").GET().build()
            val events=client.send(eventRequest,HttpResponse.BodyHandlers.ofInputStream())
            assertEquals(200,events.statusCode());assertTrue(events.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"))
            events.body().use { assertEquals(": connected",it.bufferedReader().readLine()) }
            assertEquals(200, send("DELETE", session = session).statusCode())
            assertEquals(0, server.router.clientCount)
        } finally { server.close() }
        try { send("POST", initialization()); fail("Stopped endpoint must be unreachable") } catch (_: IOException) {}
    }
}
