package hu.baader.repl.editor

/** Workbook boundaries are standalone // %% comments, never text inside Java literals/comments. */
object ReplCells {
    data class Cell(val start: Int, val end: Int, val next: Int?) {
        fun source(text: String) = text.substring(start, end)
    }
    private val delimiter = Regex("[ \t]*//[ \t]*%%(?:[ \t].*)?\r?")
    private enum class State { CODE, LINE, BLOCK, STRING, CHARACTER, TEXT }
    private fun scan(text: String, end: Int, marker: (Int, Int) -> Unit = { _, _ -> }): Boolean {
        var state = State.CODE
        var i = 0
        var lineStart = 0
        while (i < end) {
            if (i == lineStart && state == State.CODE) {
                val lineEnd = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                if (text.substring(i, lineEnd).matches(delimiter))
                    marker(i, (lineEnd + 1).coerceAtMost(text.length))
            }
            val c = text[i]
            when (state) {
                State.CODE -> when {
                    text.startsWith("//", i) -> { state = State.LINE; i++ }
                    text.startsWith("/*", i) -> { state = State.BLOCK; i++ }
                    text.startsWith("\"\"\"", i) -> { state = State.TEXT; i += 2 }
                    c == '"' -> state = State.STRING
                    c == '\'' -> state = State.CHARACTER
                }
                State.LINE -> if (c == '\n') state = State.CODE
                State.BLOCK -> if (text.startsWith("*/", i)) { state = State.CODE; i++ }
                State.STRING, State.CHARACTER -> when {
                    c == '\\' -> i++
                    c == '"' && state == State.STRING || c == '\'' && state == State.CHARACTER -> state = State.CODE
                    c == '\n' -> state = State.CODE
                }
                State.TEXT -> when {
                    c == '\\' -> i++
                    text.startsWith("\"\"\"", i) -> { state = State.CODE; i += 2 }
                }
            }
            if (c == '\n') lineStart = i + 1
            i++
        }
        return state == State.CODE
    }
    fun at(text: String, caret: Int): Cell {
        val markers = mutableListOf<Pair<Int, Int>>()
        scan(text, text.length) { start, body -> markers += start to body }
        val offset = caret.coerceIn(0, text.length)
        val previous = markers.indexOfLast { it.first <= offset }
        val start = if (previous < 0) 0 else markers[previous].second
        val following = markers.getOrNull(previous + 1)
        return Cell(start, following?.first ?: text.length, following?.second)
    }
    fun isCode(text: String, caret: Int) = scan(text, caret.coerceIn(0, text.length))
    fun all(text: String): List<Cell> {
        val markers = mutableListOf<Pair<Int, Int>>()
        scan(text, text.length) { start, body -> markers += start to body }
        val cells = mutableListOf<Cell>()
        if (markers.isEmpty() || markers.first().first > 0) cells += Cell(0, markers.firstOrNull()?.first ?: text.length, markers.firstOrNull()?.second)
        markers.forEachIndexed { i, (_, body) -> cells += Cell(body, markers.getOrNull(i + 1)?.first ?: text.length, markers.getOrNull(i + 1)?.second) }
        return cells
    }
}
