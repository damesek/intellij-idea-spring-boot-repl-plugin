package hu.baader.repl.trace

import hu.baader.repl.protocol.*

/** View-only JDBC nodes. Persisted Java invocation IDs and data are never changed. */
object RecordingSql {
    const val BASE = 1_000_000L
    fun subtree(record: CallRecording, call: Long): SqlSnapshot = record.sql.subtree(record.calls.filter {
        CallNavigation.path(record.calls, it.id()).any { parent -> parent.id() == call }
    }.map { it.id() }.toSet())
    fun nodes(record: CallRecording, grouped: Boolean): List<RecordedCall> {
        val parents = record.calls.associateBy { it.id() }
        val orm=record.hibernate.events().map { it.id() }.toSet()
        val groups = if (grouped) record.sql.events().groupBy { "${it.parent()}:${it.orm()}:${it.kind()}:${it.fingerprint()}" }.values.toList()
            else record.sql.events().map { listOf(it) }
        return groups.mapNotNull { rows ->
            val first = rows.first(); val parent = parents[first.parent()] ?: return@mapNotNull null
            val label = if (first.kind() == "CONNECTION") "Connection" else "SQL"
            val description = "${rows.size} × ${first.operation()} · ${first.datasource()}" +
                if (rows.size >= record.sql.threshold() && first.sql().startsWith("select ")) " · suspected N+1" else ""
            RecordedCall(record.id, BASE + first.id(), if(first.orm() in orm) RecordingHibernate.BASE+first.orm() else parent.id(), parent.root(), "jdbc.$label", first.operation(), "()Ljava/lang/Object;", "",
                first.thread(), parent.threadName(), rows.minOf { it.startedAt() }, rows.sumOf { it.durationNanos() },
                if (rows.any { it.error().isNotEmpty() }) "ERROR" else "SUCCESS", description,
                ValueTree.leaf("SQL", "STRING", "JDBC", first.sql()).encode(), ValueTree.leaf("JDBC", "STRING", "", description).encode(),
                rows.firstOrNull { it.error().isNotEmpty() }?.let { ValueTree.leaf("error", "STRING", "", it.error()).encode() }.orEmpty(), -1, record.sql.revision().coerceAtLeast(1))
        }
    }
    fun summary(sql: SqlSnapshot): String = when {
        !sql.enabled() -> "SQL capture was not enabled for this recording."
        !sql.available() -> "JDBC capture unavailable; counts cannot establish an upper bound."
        else -> "${sql.count()} SQL executions · ${"%.2f".format(java.util.Locale.ROOT,sql.nanos()/1e6)} ms SQL · " +
            "${"%.2f".format(java.util.Locale.ROOT,sql.connectionNanos()/1e6)} ms acquiring connections · max repetition ${sql.maxRepetitions()} · " +
            "${sql.findings().size} suspected N+1 groups" + if (sql.partial()) " · PARTIAL (${sql.dropped()} omitted, ${sql.pending()} pending)" else ""
    }
    fun compare(before: SqlSnapshot, after: SqlSnapshot): String =
        "Before: ${before.count()} SQL · ${before.nanos()/1e6} ms · max repetition ${before.maxRepetitions()}\nAfter: ${after.count()} SQL · ${after.nanos()/1e6} ms · max repetition ${after.maxRepetitions()}\n" +
            "SQL count Δ ${after.count()-before.count()} · SQL ms Δ ${"%.2f".format(java.util.Locale.ROOT,(after.nanos()-before.nanos())/1e6)} · " +
            "max repetition Δ ${after.maxRepetitions()-before.maxRepetitions()}" +
            if (before.partial() || after.partial()) "\nPartial evidence: this does not establish that the issue was fixed." else ""
}
