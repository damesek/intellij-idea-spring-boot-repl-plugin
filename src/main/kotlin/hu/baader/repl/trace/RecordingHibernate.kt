package hu.baader.repl.trace

import hu.baader.repl.protocol.*

object RecordingHibernate {
    const val BASE=500_000L
    fun subtree(record: CallRecording, call: Long): HibernateSnapshot = record.hibernate.subtree(record.calls.filter {
        CallNavigation.path(record.calls,it.id()).any { parent -> parent.id()==call }
    }.map { it.id() }.toSet())
    fun statistics(orm: HibernateSnapshot): Map<String,Any> = linkedMapOf(
        "enabled" to orm.enabled(), "available" to orm.available(), "version" to orm.version(), "partial" to orm.partial(),
        "entityLoads" to orm.count("ENTITY_LOAD"), "entityInserts" to orm.count("ENTITY_INSERT"), "entityUpdates" to orm.count("ENTITY_UPDATE"), "entityDeletes" to orm.count("ENTITY_DELETE"),
        "lazyLoads" to orm.lazyCount(), "responseLazyLoads" to orm.responseLazyCount(), "flushes" to orm.flushCount(), "dirtyChecks" to orm.count("DIRTY_CHECK"),
        "cacheHits" to orm.count("CACHE_HIT"), "cacheMisses" to orm.count("CACHE_MISS"), "cachePuts" to orm.count("CACHE_PUT"),
        "queryCacheHits" to orm.count("QUERY_CACHE_HIT"), "queryCacheMisses" to orm.count("QUERY_CACHE_MISS"), "queryCachePuts" to orm.count("QUERY_CACHE_PUT"),
        "dropped" to orm.dropped(), "pending" to orm.pending())
    fun summary(orm: HibernateSnapshot): String = when {
        !orm.enabled() -> "Hibernate capture was not enabled."
        !orm.available() -> "Hibernate metadata unavailable (${orm.version().ifEmpty { "no observed session" }}); supported adapter: 6.6."
        else -> "${orm.count("ENTITY_LOAD")} entity loads · ${orm.lazyCount()} lazy loads (${orm.responseLazyCount()} during response) · ${orm.flushCount()} flushes · " +
            "L2 cache ${orm.count("CACHE_HIT")} hit / ${orm.count("CACHE_MISS")} miss · query cache ${orm.count("QUERY_CACHE_HIT")} hit / ${orm.count("QUERY_CACHE_MISS")} miss" +
            if(orm.partial()) " · PARTIAL (${orm.dropped()} omitted, ${orm.pending()} pending)" else ""
    }
    fun compare(before: HibernateSnapshot,after: HibernateSnapshot): String =
        "Before: ${summary(before)}\nAfter: ${summary(after)}\nΔ entity loads ${after.count("ENTITY_LOAD")-before.count("ENTITY_LOAD")} · lazy ${after.lazyCount()-before.lazyCount()} · flushes ${after.flushCount()-before.flushCount()}" +
            if(before.partial()||after.partial()) "\nPartial evidence cannot establish that an issue is fixed." else ""
    fun nodes(record: CallRecording): List<RecordedCall> {
        val java=record.calls.associateBy { it.id() };val index=record.hibernate.events().associateBy { it.id() }
        return record.hibernate.events().mapNotNull { e ->
            val caller=java[e.parent()] ?: return@mapNotNull null
            val summary="${e.kind()} · ${e.role().ifEmpty { e.entity() }} ${e.detail()}".take(4096)
            RecordedCall(record.id,BASE+e.id(),if(e.parentOrm() in index) BASE+e.parentOrm() else e.parent(),e.root(),"hibernate.ORM",e.kind().lowercase(),"()Ljava/lang/Object;","",e.thread(),caller.threadName(),
                e.startedAt(),e.durationNanos(),if(e.error().isEmpty()) "SUCCESS" else "ERROR",summary,
                ValueTree.leaf("ORM","STRING","",summary).encode(),ValueTree.leaf("ORM","STRING","",summary).encode(),
                if(e.error().isEmpty()) "" else ValueTree.leaf("error","STRING","",e.error()).encode(),-1,record.hibernate.revision().coerceAtLeast(1))
        }
    }
}
