package hu.baader.repl.editor

/** Pure request/response mapping used by the asynchronous editor lookup. */
data class CompletionRequest(val source: String, val cursor: Int, val base: Int, val stamp: Long, val generation: Long) {
    data class Choice(val anchor: Int, val text: String)
    fun choices(reply: String): List<Choice> = reply.lineSequence().mapNotNull {
        val parts = it.split('\t', limit = 2)
        val anchor = parts.firstOrNull()?.toIntOrNull()
        if (parts.size != 2 || anchor == null || anchor !in 0..cursor || parts[1].isBlank()) null
        else Choice(base + anchor, parts[1])
    }.distinct().take(100).toList()
    fun matches(stamp: Long, caret: Int, generation: Long) =
        this.stamp == stamp && base + cursor == caret && this.generation == generation
    companion object {
        fun capture(text: String, caret: Int, stamp: Long, generation: Long): CompletionRequest? {
            val cell = ReplCells.at(text, caret)
            if (caret < cell.start || !ReplCells.isCode(text, caret)) return null
            return CompletionRequest(text.substring(cell.start, caret), caret - cell.start, cell.start, stamp, generation)
        }
    }
}
