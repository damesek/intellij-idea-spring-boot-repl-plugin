package hu.baader.repl.mcp

import hu.baader.repl.nrepl.NreplClient
import hu.baader.repl.protocol.EndpointFile
import java.net.InetAddress
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

internal interface McpBackend : AutoCloseable {
    fun request(operation: String, arguments: Map<String, String> = emptyMap()): CompletableFuture<Map<String, String>>
}

/** A fresh socket/JShell session per MCP client. Never borrows the IDE's evaluator or replays code. */
internal class NreplMcpBackend private constructor(private val client: NreplClient) : McpBackend {
    override fun request(operation: String, arguments: Map<String, String>) = client.request(operation, arguments)
    override fun close() = client.close()
    companion object {
        fun connect(path: Path, expectedPid: Long): McpBackend {
            val endpoint = EndpointFile.read(path)
            check(endpoint.pid() == expectedPid) { "REPL target changed; restart the MCP server" }
            val client = NreplClient(InetAddress.getLoopbackAddress().hostAddress, endpoint.port(), endpoint.token())
            try { client.connect(expectedPid); return NreplMcpBackend(client) }
            catch (e: Exception) { client.close(); throw e }
        }
    }
}
