package hu.baader.repl.mcp

import org.junit.Assert.*
import org.junit.Test

class McpResponsesTest {
    @Test fun redactionRebuildsBothRepresentationsWithoutLeakingSecrets() {
        val raw = McpResponses.result(McpTool("example", "eval", "example"), emptyMap(),
            mapOf("value" to "ok", "password" to "never-expose-this", "handle" to "result-1"))
        val safe = McpResponses.sanitizeResult(raw, McpPermissions())
        assertFalse(safe.toString().contains("never-expose-this"))
        assertEquals("result-1", safe.getAsJsonObject("structuredContent")["handle"].asString)
        val text = safe.getAsJsonArray("content")[0].asJsonObject["text"].asString
        assertEquals(safe["structuredContent"], McpJson.parse(text))
        assertTrue(safe.getAsJsonObject("structuredContent")["redacted"].asBoolean)
    }

    @Test fun truncatedExecutionResultKeepsTheHandleWithoutSuggestingReplay() {
        val raw = McpResponses.result(McpTool("example", "eval", "example"), emptyMap(),
            mapOf("value" to "x".repeat(20000), "handle" to "result-1"))
        val safe = McpResponses.sanitizeResult(raw, McpPermissions(maxResultChars = 1024))
        assertTrue(safe.toString().length <= 1024)
        val data = safe.getAsJsonObject("structuredContent")
        assertEquals("result-1", data["handle"].asString)
        assertTrue(data["truncated"].asBoolean)
        assertTrue(data["note"].asString.contains("Do not rerun execution"))
    }

    @Test fun pagingNeverTurnsAnOperationFailureIntoRows() {
        val tool = McpTool("example", "list-beans", "example", paged = true)
        val result = McpResponses.result(tool, mapOf("offset" to "10", "limit" to "2"),
            mapOf("value" to "diagnostic", "err" to "Context unavailable", "status" to "error\ndone"))
        assertTrue(result["isError"].asBoolean)
        val data = result.getAsJsonObject("structuredContent")
        assertFalse(data.has("rows"))
        assertEquals("Context unavailable", data["err"].asString)
    }
}
