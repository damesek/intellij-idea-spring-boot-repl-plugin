package hu.baader.repl.ui

import com.google.gson.GsonBuilder
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import hu.baader.repl.protocol.ValueTree
import java.io.StringReader

/** Strict, bounded JSON parsing preserves number literals and duplicate object keys. */
data class ValueDisplay(val root: ValueTree, val formatted: String, val raw: String, val json: Boolean, val note: String) {
    companion object {
        private val quotes = GsonBuilder().disableHtmlEscaping().create()
        private const val MAX_TEXT = 131_072
        private const val MAX_FORMATTED = 262_144
        fun fromMessage(message: Map<String, String>, fallback: String = ""): ValueDisplay {
            val root = try { ValueTree.decode(message["view-data"]) } catch (_: IllegalArgumentException) {
                return plain(fallback, "Text preview")
            }
            val limited = message["view-limited"] == "true"
            if (root.kind() == "STRING" && !limited && root.text().trimStart().firstOrNull() in listOf('{', '[')) {
                try {
                    val parsed = parseJson(root.text())
                    return ValueDisplay(parsed, pretty(parsed), root.text(), true, "JSON · expand nodes or switch to Formatted")
                } catch (failure: PreviewLimit) {
                    return plain(root.text(), "JSON exceeds preview limits; showing text")
                } catch (_: Exception) { /* Ordinary strings and invalid JSON remain unchanged. */ }
            }
            val raw = if (root.kind() == "STRING") root.text() else fallback
            val formatted = if (root.kind() == "STRING") root.text() else pretty(root)
            return ValueDisplay(root, formatted, raw, false,
                if (limited) "Partial preview · use Inspector → Fields to explore further" else "${root.type()} · display preview")
        }
        private fun plain(text: String, note: String): ValueDisplay {
            val bounded = text.take(MAX_FORMATTED)
            return ValueDisplay(ValueTree.leaf("result", "STRING", "text", bounded), bounded, bounded, false, note)
        }
        fun parseJson(source: String): ValueTree {
            if (source.length > MAX_TEXT) throw PreviewLimit()
            var count = 0
            JsonReader(StringReader(source)).use { reader ->
                reader.isLenient = false
                fun read(label: String, depth: Int): ValueTree {
                    if (depth > ValueTree.MAX_DEPTH || ++count > ValueTree.MAX_NODES) throw PreviewLimit()
                    return when (reader.peek()) {
                        JsonToken.BEGIN_OBJECT -> {
                            reader.beginObject()
                            val children = mutableListOf<ValueTree>()
                            while (reader.hasNext()) children += read(reader.nextName(), depth + 1)
                            reader.endObject(); ValueTree(label, "OBJECT", "JSON object", "", children)
                        }
                        JsonToken.BEGIN_ARRAY -> {
                            reader.beginArray()
                            val children = mutableListOf<ValueTree>()
                            while (reader.hasNext()) children += read("[${children.size}]", depth + 1)
                            reader.endArray(); ValueTree(label, "ARRAY", "JSON array", "", children)
                        }
                        JsonToken.STRING -> ValueTree.leaf(label, "STRING", "string", reader.nextString())
                        JsonToken.NUMBER -> ValueTree.leaf(label, "NUMBER", "number", reader.nextString())
                        JsonToken.BOOLEAN -> ValueTree.leaf(label, "BOOLEAN", "boolean", reader.nextBoolean().toString())
                        JsonToken.NULL -> { reader.nextNull(); ValueTree.leaf(label, "NULL", "null", "null") }
                        else -> throw IllegalArgumentException("Invalid JSON")
                    }
                }
                val root = read("result", 0)
                require(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing JSON content" }
                return root
            }
        }
        fun pretty(root: ValueTree): String {
            val out = StringBuilder()
            fun append(text: String) {
                if (out.length + text.length > MAX_FORMATTED) throw PreviewLimit()
                out.append(text)
            }
            fun render(node: ValueTree, depth: Int) {
                when (node.kind()) {
                    "OBJECT", "ARRAY" -> {
                        val objectNode = node.kind() == "OBJECT"
                        append(if (objectNode) "{" else "[")
                        node.children().forEachIndexed { index, child ->
                            append(if (index == 0) "\n" else ",\n"); append("  ".repeat(depth + 1))
                            if (objectNode) { append(quotes.toJson(child.label())); append(": ") }
                            render(child, depth + 1)
                        }
                        if (node.children().isNotEmpty()) { append("\n"); append("  ".repeat(depth)) }
                        append(if (objectNode) "}" else "]")
                    }
                    "NULL" -> append("null")
                    "NUMBER", "BOOLEAN" -> append(node.text())
                    "REFERENCE" -> append(quotes.toJson("↩ ${node.text()}"))
                    else -> append(quotes.toJson(node.text()))
                }
            }
            try { render(root, 0) } catch (_: PreviewLimit) { out.append("\n… formatted preview truncated") }
            return out.toString()
        }
    }
    private class PreviewLimit : IllegalArgumentException()
}
