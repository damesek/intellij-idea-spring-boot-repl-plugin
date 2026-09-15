package hu.baader.repl.mcp

import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

/** Untrusted requests are bounded before parsing; reject duplicate keys and excessive nesting. */
internal object McpJson {
    val gson: Gson = GsonBuilder().disableHtmlEscaping().create()
    const val MAX_REQUEST_BYTES = 262_144
    const val MAX_RESPONSE_BYTES = 524_288
    fun objectOf(vararg values: Pair<String, Any?>): JsonObject = gson.toJsonTree(mapOf(*values)).asJsonObject
    fun parse(text: String): JsonElement {
        var nodes = 0
        JsonReader(StringReader(text)).use { reader ->
            reader.isLenient = false
            fun read(depth: Int): JsonElement {
                require(depth <= 32 && ++nodes <= 10_000) { "JSON complexity limit exceeded" }
                return when (reader.peek()) {
                    JsonToken.BEGIN_OBJECT -> JsonObject().also { obj ->
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val name = reader.nextName()
                            require(!obj.has(name)) { "Duplicate JSON property" }
                            obj.add(name, read(depth + 1))
                        }
                        reader.endObject()
                    }
                    JsonToken.BEGIN_ARRAY -> JsonArray().also { array ->
                        reader.beginArray()
                        while (reader.hasNext()) array.add(read(depth + 1))
                        reader.endArray()
                    }
                    JsonToken.STRING -> JsonPrimitive(reader.nextString())
                    JsonToken.NUMBER -> JsonPrimitive(reader.nextString().toBigDecimal())
                    JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
                    JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
                    else -> throw IllegalArgumentException("Invalid JSON")
                }
            }
            val result = read(0)
            require(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing JSON content" }
            return result
        }
    }
    fun string(obj: JsonObject, key: String): String? = obj[key]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
}

internal class McpError(val code: Int, override val message: String) : RuntimeException(message)
