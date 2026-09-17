package hu.baader.repl.mcp

import com.google.gson.JsonObject
import hu.baader.repl.ui.ValueDisplay
import hu.baader.repl.protocol.ValueTree

/** Bounded, redacted MCP previews; this layer never executes an operation. */
internal object McpResponses {
    fun result(tool: McpTool, arguments: Map<String, String>, response: Map<String, String>): JsonObject {
        if (tool.operation.startsWith("recording/") && response.containsKey("recording-json"))
            return envelope(McpJson.parse(response.getValue("recording-json")).asJsonObject, response.containsKey("err"))
        val data = JsonObject()
        val truncated = mutableListOf<String>()
        var remaining = 98_304
        val failure = response.containsKey("err") || response["status"].orEmpty().lines().contains("error")
        for ((key, value) in response) {
            if (key in setOf("id", "session", "new-session", "token", "view-data", "op")) continue
            if (key == "value" && tool.paged && !failure) {
                val lines = value.lineSequence().filter { it.isNotEmpty() }.toList()
                val offset = arguments["offset"]?.toInt() ?: 0
                val limit = arguments["limit"]?.toInt() ?: 50
                data.addProperty("totalRows", lines.size)
                val end = minOf(lines.size.toLong(), offset.toLong() + limit).toInt()
                if (end < lines.size) data.addProperty("nextOffset", end)
                val rows = lines.drop(offset).take(limit)
                data.add("rows", McpJson.gson.toJsonTree(rows.map { it.split('\t') }))
                continue
            }
            val length = minOf(value.length, remaining, 65_536)
            data.addProperty(key, value.take(length))
            remaining -= length
            if (length < value.length) truncated += key
        }
        if (response.containsKey("view-data")) {
            val display = ValueDisplay.fromMessage(response, response["value"].orEmpty())
            fun tree(node: ValueTree): JsonObject = McpJson.objectOf("label" to node.label(), "kind" to node.kind(),
                "type" to node.type(), "text" to node.text(), "children" to node.children().map(::tree))
            data.add("preview", tree(display.root))
            data.addProperty("jsonPreview", display.json)
        }
        if (truncated.isNotEmpty()) {
            data.add("truncatedFields", McpJson.gson.toJsonTree(truncated))
            data.addProperty("note", "Partial result. Use paged inspection or the IDE's snapshot file export for large data.")
        }
        var result = envelope(data, failure)
        // Leave room for the JSON-RPC envelope and a maximally escaped 256-character request id.
        if (McpJson.gson.toJson(result).toByteArray(Charsets.UTF_8).size > McpJson.MAX_RESPONSE_BYTES - 4096) {
            val small = McpJson.objectOf("truncated" to true, "note" to "Result exceeds MCP preview limit. Use paged inspection or IDE file export.")
            listOf("handle", "type", "status", "err", "value").forEach { key -> response[key]?.let { small.addProperty(key, it.take(4096)) } }
            result = envelope(small, failure)
        }
        return result
    }

    fun error(message: String) = envelope(McpJson.objectOf("error" to message.take(4096)), true)
    fun sanitizeResult(result: JsonObject, permissions: McpPermissions, redactedBeforePaging: Boolean = false): JsonObject {
        var redacted = false
        fun scrub(value: com.google.gson.JsonElement): com.google.gson.JsonElement = when {
            value.isJsonObject -> JsonObject().apply { value.asJsonObject.entrySet().forEach { (key, child) ->
                val label = value.asJsonObject["label"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
                if (hu.baader.repl.protocol.SensitiveValues.sensitiveName(key) ||
                    (key in setOf("text", "children") && hu.baader.repl.protocol.SensitiveValues.sensitiveName(label))) { addProperty(key, "[REDACTED]"); redacted = true }
                else add(key, scrub(child))
            } }
            value.isJsonArray -> com.google.gson.JsonArray().apply { value.asJsonArray.forEach { add(scrub(it)) } }
            value.isJsonPrimitive && value.asJsonPrimitive.isString -> {
                val raw = value.asString
                val safe = hu.baader.repl.protocol.SensitiveValues.redact(raw)
                if (safe != raw) redacted = true
                com.google.gson.JsonPrimitive(safe)
            }
            else -> value.deepCopy()
        }
        // Rebuild the text envelope from structured data, rather than redacting JSON syntax as text.
        val data = result.getAsJsonObject("structuredContent")
        val safe = if (permissions.redactResults && !redactedBeforePaging) scrub(data).asJsonObject else data.deepCopy()
        if (redacted) safe.addProperty("redacted", true)
        var output = envelope(safe, result["isError"]?.asBoolean ?: false)
        if (McpJson.gson.toJson(output).length > permissions.maxResultChars) {
            val small = McpJson.objectOf("truncated" to true, "note" to "Operation ran; result exceeds the configured MCP limit. Use paged inspection. Do not rerun execution to retrieve this result.")
            small.add("truncatedFields", safe["truncatedFields"] ?: McpJson.gson.toJsonTree(safe.keySet().filter { it !in setOf("handle", "redacted") }))
            if (redacted) small.addProperty("redacted", true)
            safe["handle"]?.let { small.add("handle", it) }
            // Keep recording identity so a caller can reduce a page without losing its view guard.
            listOf("recording", "view", "call", "part", "path", "offset").forEach { key ->
                safe[key]?.takeIf { it.isJsonPrimitive && it.toString().length <= 256 }?.let { small.add(key, it) }
            }
            if (safe.has("recording")) {
                small.remove("truncatedFields")
                small.addProperty("note", "Result too large; reduce limit/text-limit. Do not repeat execution.")
            }
            output = envelope(small, result["isError"]?.asBoolean ?: false)
            for (key in listOf("truncatedFields", "path", "view", "part")) {
                if (McpJson.gson.toJson(output).length <= permissions.maxResultChars) break
                small.remove(key); output = envelope(small, result["isError"]?.asBoolean ?: false)
            }
        }
        return output
    }
    private fun envelope(data: JsonObject, error: Boolean) = McpJson.objectOf("content" to listOf(
        McpJson.objectOf("type" to "text", "text" to McpJson.gson.toJson(data))), "structuredContent" to data, "isError" to error)
}
