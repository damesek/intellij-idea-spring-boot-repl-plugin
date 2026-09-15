package hu.baader.repl.editor

/** Diagnostic positions use UTF-16 offsets, matching an IntelliJ document and JShell Diag. */
data class AnalysisRequest(val source: String, val stamp: Long, val generation: Long) {
    data class Issue(val severity: String, val start: Int, val end: Int, val message: String)
    fun matches(stamp: Long, generation: Long) = this.stamp == stamp && this.generation == generation
    fun issues(reply: String): List<Issue> = reply.lineSequence().take(100).mapNotNull { row ->
        val fields = row.split('\t', limit = 4)
        if (fields.size != 4 || fields[0] !in setOf("ERROR", "WARNING")) return@mapNotNull null
        val start = fields[1].toIntOrNull() ?: return@mapNotNull null
        val end = fields[2].toIntOrNull() ?: return@mapNotNull null
        if (start !in 0..source.length || end !in start..source.length || fields[3].isBlank()) return@mapNotNull null
        if (source.isEmpty()) return@mapNotNull null
        val visibleStart = start.coerceAtMost(source.length - 1)
        Issue(fields[0], visibleStart, maxOf(end, visibleStart + 1), fields[3].take(1000))
    }.distinct().toList()
}
