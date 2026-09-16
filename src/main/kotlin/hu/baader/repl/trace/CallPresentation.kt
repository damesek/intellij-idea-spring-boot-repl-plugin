package hu.baader.repl.trace

import hu.baader.repl.protocol.RecordedCall
import hu.baader.repl.protocol.ValueTree
import hu.baader.repl.ui.ValueDisplay
import java.util.Locale

/** All browsing/searching uses frozen display data, never application objects. */
data class CallPresentation(val call: RecordedCall, val sqlBadge: String = "") {
    val input = tree(call.input())
    val output = tree(call.output())
    val exception = tree(call.exception())
    val signature = runCatching {
        val names = call.parameterNames().split('\n')
        val parameters = MethodDescriptor.parameters(call.descriptor()).mapIndexed { i, type ->
            "$type ${names.getOrNull(i)?.takeIf(String::isNotBlank) ?: "arg$i"}"
        }
        "${call.className()}.${call.method()}(${parameters.joinToString(", ")})"
    }.getOrElse { "${call.className()}.${call.method()}${call.descriptor()}" }
    val inputSummary = input?.let { summary(it, named = true) } ?: "Input unavailable"
    val resultSummary = when {
        call.status() == "ERROR" -> exception?.let(::summary) ?: call.summary()
        call.status() == "RUNNING" -> "Still running"
        call.status() == "INCOMPLETE" -> "Completion not captured"
        call.descriptor().endsWith(")V") -> "void"
        else -> output?.let(::summary) ?: call.summary()
    }
    val partial = listOfNotNull(input, output, exception).any(::limited)
    val unavailable = input == null || when (call.status()) {
        "SUCCESS" -> output == null && !call.descriptor().endsWith(")V")
        "ERROR" -> exception == null
        else -> false
    }
    val badge = when {
        call.className()=="async.Task" -> {
            val handoff=input?.children()?.firstOrNull()
            val wait=handoff?.children()?.firstOrNull { it.label()=="queueWaitMs" }?.text()?.toDoubleOrNull()
            "ASYNC"+(if(wait==null)"" else " · queue ${"%.2f".format(Locale.ROOT,wait)} ms")+(if(sqlBadge.isEmpty())"" else " · $sqlBadge")
        }
        sqlBadge.isNotEmpty() -> sqlBadge
        call.status() == "INCOMPLETE" -> "INCOMPLETE"
        unavailable -> "VALUES UNAVAILABLE"
        partial -> "PARTIAL PREVIEW"
        else -> ""
    }
    // Lazy so ordinary refreshes do not repeatedly allocate/search large previews.
    val searchable: String by lazy {
        buildString {
            append(signature); append(' '); append(sqlBadge); append(' '); append(call.summary()); append(' '); append(call.threadName())
            fun add(node: ValueTree) {
                append(' '); append(node.label()); append(' '); append(node.type()); append(' '); append(node.text())
                node.children().forEach(::add)
            }
            listOfNotNull(input, output, exception).forEach(::add)
        }.lowercase(Locale.ROOT)
    }
    val tooltip: String get() = "<html><b>${html(signature)}</b><br>" +
        "${html(call.status())} · ${duration(call)} ms · thread ${call.threadId()} · ${html(call.threadName())}<br>" +
        "Input: ${html(summary(input, max = 1200, named = true))}<br>" +
        "Result: ${html(if (call.status()=="ERROR") summary(exception,max=1200) else if(output!=null) summary(output,max=1200) else resultSummary)}" +
        (if (badge.isNotEmpty()) "<br><b>$badge</b>" else "") +
        "<br>Captured display values · select to open source and full captured tree</html>"

    companion object {
        fun tree(wire: String): ValueTree? = if (wire.isEmpty()) null else runCatching {
            val tree = ValueTree.decode(wire)
            if (tree.kind() == "STRING" && tree.text().trimStart().firstOrNull() in listOf('{', '['))
                runCatching { ValueDisplay.parseJson(tree.text()) }.getOrDefault(tree)
            else tree
        }.getOrNull()
        fun limited(tree: ValueTree): Boolean = tree.kind() in setOf("LIMIT", "ERROR", "REFERENCE") ||
            tree.label() == "…" || tree.text().endsWith("…") || tree.children().any(::limited)
        fun summary(tree: ValueTree?, max: Int = 280, named: Boolean = false): String {
            if (tree == null) return "Unavailable"
            fun text(node: ValueTree, depth: Int): String = when (node.kind()) {
                "OBJECT", "ARRAY" -> {
                    val objectNode = node.kind() == "OBJECT"
                    val (left, right) = if (objectNode) "{" to "}" else "[" to "]"
                    if (depth >= 2) "$left…$right" else left + node.children().take(4).joinToString(", ") {
                        (if (objectNode) "${it.label()}=" else "") + text(it, depth + 1)
                    } + (if (node.children().size > 4) ", …" else "") + right
                }
                "STRING" -> "\"${node.text().replace("\n", "\\n").replace("\r", "\\r").take(max)}\""
                else -> node.text().take(max)
            }
            val value = (if (named && tree.kind() !in setOf("OBJECT", "ARRAY")) "${tree.label()}=" else "") + text(tree, 0)
            return if (value.length > max) value.take((max - 1).coerceAtLeast(0)) + "…" else value
        }
        fun html(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("\n", "<br>")
        fun duration(call: RecordedCall) = "%.2f".format(Locale.ROOT, call.durationNanos() / 1_000_000.0)
    }
}

class CallPresentationCache {
    private val values = mutableMapOf<Long, CallPresentation>()
    fun update(calls: List<RecordedCall>): Map<Long, CallPresentation> {
        values.keys.retainAll(calls.map { it.id() }.toSet())
        calls.forEach { call -> if (values[call.id()]?.call != call) values[call.id()] = CallPresentation(call) }
        return values
    }
    fun clear() = values.clear()
}

/** Relative milliseconds avoid loss of precision when adding nanoseconds to epoch timestamps. */
data class CallTimeRange(val startMs: Double, val endMs: Double) {
    init { require(startMs.isFinite() && endMs.isFinite() && startMs >= 0 && endMs >= startMs) }
    fun intersects(start: Double, end: Double) = start <= endMs && end >= startMs
}

data class CallFilter(
    val query: String = "",
    val errorsOnly: Boolean = false,
    val minDurationMs: Double = 0.0,
    val thread: Long? = null,
    val range: CallTimeRange? = null,
    val focus: Long? = null
) {
    val active get() = query.isNotBlank() || errorsOnly || minDurationMs > 0 || thread != null || range != null || focus != null
    fun matches(call: RecordedCall, presentation: CallPresentation, origin: Long): Boolean =
        (!errorsOnly || call.status() == "ERROR") &&
            (minDurationMs <= 0 || call.durationNanos() / 1_000_000.0 >= minDurationMs) &&
            (thread == null || call.threadId() == thread) &&
            (range == null || range.intersects((call.startedAt() - origin).toDouble(),
                (call.startedAt() - origin).toDouble() + call.durationNanos() / 1_000_000.0)) &&
            (query.isBlank() || presentation.searchable.contains(query.trim().lowercase(Locale.ROOT)))
}

object CallNavigation {
    fun path(calls: List<RecordedCall>, id: Long?): List<RecordedCall> {
        val index = calls.associateBy { it.id() }
        val result = mutableListOf<RecordedCall>()
        var call = index[id]
        while (call != null && result.size <= calls.size) {
            result += call; call = index[call.parent()]
        }
        return result.reversed()
    }
    // Invocation IDs are allocated at entry; use them even if the wall clock moves backwards.
    fun adjacent(calls: List<RecordedCall>, selected: Long?, direction: Int, errorsOnly: Boolean = false): RecordedCall? {
        val candidates = calls.sortedBy { it.id() }.filter { !errorsOnly || it.status() == "ERROR" }
        return if (direction > 0) candidates.firstOrNull { selected == null || it.id() > selected }
        else candidates.lastOrNull { selected == null || it.id() < selected }
    }
}
