package hu.baader.repl.editor

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Source/evidence model, never a serialized JVM. Access on the UI thread or to an isolated imported copy. */
class NotebookState {
    data class Cell(
        var id: String = UUID.randomUUID().toString(), var source: String = "", var executedHash: String = "",
        var sequence: Long = 0, var status: String = "NEVER", var startedAt: String = "", var durationMs: Long = 0,
        var output: String = "", var stale: String = "", var declarations: List<String> = emptyList(),
        var analyzedHash: String = "", var conservative: Boolean = true
    ) {
        @Transient private var cachedSource: String? = null
        @Transient private var cachedHash = ""
        @Transient private var cachedTokens: Set<String> = emptySet()
        private fun cache() { if (cachedSource != source) { cachedHash = hash(source); cachedTokens = identifiers(source); cachedSource = source } }
        val sourceHash get(): String { cache(); return cachedHash }
        val tokens get(): Set<String> { cache(); return cachedTokens }
        val modified get() = executedHash.isNotEmpty() && executedHash != sourceHash
        val inputs get() = tokens - declarations.toSet()
        val label get() = when { modified -> "$status · modified"; stale.isNotBlank() -> "$status · stale"; else -> status }
    }
    var text: String = ""
    var cells: MutableList<Cell> = mutableListOf()
    var counter: Long = 0
    var executionOrder: MutableList<String> = mutableListOf()
    fun sync(source: String) {
        require(source.length <= 1_000_000) { "Workbook exceeds 1 million characters" }
        val parts = ReplCells.all(source).map { it.source(source) }
        require(parts.size <= 200) { "Workbook supports at most 200 cells" }
        val old = cells.toList(); val used = mutableSetOf<String>()
        // Match exact contents before positional edits so inserting a cell preserves the other identities.
        val matches = parts.map { s -> old.firstOrNull { it.id !in used && it.source == s }?.also { used += it.id } }.toMutableList()
        parts.indices.filter { matches[it] == null }.forEach { i ->
            matches[i] = old.getOrNull(i)?.takeIf { it.id !in used }?.also { used += it.id } ?: Cell()
        }
        cells = matches.map { it!! }.toMutableList(); text = source
        val changed = mutableSetOf<String>()
        cells.forEachIndexed { i, c -> if (c.source != parts[i]) { changed += c.id; c.source = parts[i] } }
        if (old.any { it.id !in used } || old.map { it.id }.filter { it in used } != cells.map { it.id }.filter { id -> old.any { it.id == id } })
            invalidate("Cell order or declarations changed")
        val origins = cells.mapIndexedNotNull { i, c -> if (c.id in changed) (i + 1).toString() else null }.joinToString(", ")
        affected(changed).filter { it !in changed }.mapNotNull(::cell).filter { it.sequence > 0 }
            .forEach { it.stale = "Input cell(s) $origins changed" }
    }
    fun cell(id: String) = cells.firstOrNull { it.id == id }
    fun indexAt(offset: Int) = ReplCells.all(text).indexOfFirst { it.start == ReplCells.at(text, offset).start }.coerceAtLeast(0)
    fun dependencies(id: String): Set<String> {
        val index = cells.indexOfFirst { it.id == id }; if (index < 0) return emptySet()
        val c = cells[index]; val tokens = c.tokens
        return cells.filterIndexed { i, earlier -> i != index && (
            (i < index && (earlier.analyzedHash != earlier.sourceHash || earlier.conservative || "*" in earlier.declarations ||
                c.analyzedHash != c.sourceHash || c.conservative)) || earlier.declarations.any { it in tokens })
        }.map { it.id }.toSet()
    }
    fun affected(id: String): List<String> {
        return affected(setOf(id))
    }
    private fun affected(seeds: Set<String>): List<String> {
        if (seeds.isEmpty()) return emptyList()
        val graph = cells.associate { it.id to dependencies(it.id) }
        val found = seeds.toMutableSet()
        var changed: Boolean
        do { changed = false; cells.forEach { if (it.id !in found && graph.getValue(it.id).any(found::contains)) { found += it.id; changed = true } } } while (changed)
        return cells.filter { it.id in found }.map { it.id }
    }
    fun analyze(id: String, source: String, names: List<String>, conservative: Boolean) {
        cell(id)?.takeIf { it.source == source }?.apply { declarations = names.take(200); analyzedHash = hash(source); this.conservative = conservative }
    }
    fun begin(id: String): Long {
        val c = requireNotNull(cell(id))
        affected(id).filter { it != id }.mapNotNull(::cell).filter { it.sequence > 0 }
            .forEach { it.stale = "Input cell ${cells.indexOf(c) + 1} was run again" }
        c.sequence = ++counter; c.status = "RUNNING"; c.startedAt = Instant.now().toString(); c.durationMs = 0
        c.stale = ""; executionOrder.add(id); while (executionOrder.size > 1000) executionOrder.removeAt(0)
        return c.sequence
    }
    fun finish(id: String, sequence: Long, source: String, output: String, failure: Boolean, duration: Long) {
        val c = cell(id)?.takeIf { it.sequence == sequence && it.status == "RUNNING" } ?: return
        c.status = if (failure) "ERROR" else "SUCCESS"; c.executedHash = hash(source)
        c.output = output.take(16384); c.durationMs = duration.coerceAtLeast(0)
        if (failure) c.stale = "Execution failed; partial changes may remain"
        else if (dependencies(id).mapNotNull(::cell).any { it.status != "SUCCESS" || it.modified || it.stale.isNotBlank() })
            c.stale = "An input cell has not been successfully rerun"
    }
    fun invalidate(reason: String) { cells.filter { it.sequence > 0 }.forEach { it.stale = reason; if (it.status == "RUNNING") it.status = "INTERRUPTED" } }
    fun restored() { sync(text); invalidate("Previous session evidence; rerun or restore DATA explicitly") }
    companion object {
        fun hash(source: String): String = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        /** Strip comments/literals before collecting candidate references; conservative, not alias analysis. */
        fun identifiers(source: String): Set<String> {
            val tokens = linkedSetOf<String>(); var i = 0
            while (i < source.length) {
                when {
                    source.startsWith("//", i) -> i = source.indexOf('\n', i).let { if (it < 0) source.length else it + 1 }
                    source.startsWith("/*", i) -> i = source.indexOf("*/", i + 2).let { if (it < 0) source.length else it + 2 }
                    source.startsWith("\"\"\"", i) -> { i += 3; while (i < source.length && !source.startsWith("\"\"\"", i)) { i += if (source[i] == '\\') 2 else 1 }; i = (i + 3).coerceAtMost(source.length) }
                    source[i] == '"' || source[i] == '\'' -> { val quote = source[i++]; while (i < source.length) { val c = source[i++]; if (c == '\\') i = (i + 1).coerceAtMost(source.length) else if (c == quote) break } }
                    Character.isJavaIdentifierStart(source[i]) -> { val start = i++; while (i < source.length && Character.isJavaIdentifierPart(source[i])) i++; tokens += source.substring(start, i) }
                    else -> i++
                }
            }
            return tokens
        }
    }
}
