package hu.baader.repl.trace

import com.google.gson.Gson
import com.google.gson.JsonParser
import hu.baader.repl.protocol.SqlSnapshot
import hu.baader.repl.protocol.HibernateSnapshot
import hu.baader.repl.protocol.RecordedCall
import hu.baader.repl.protocol.ValueTree
import java.security.MessageDigest

/** Portable display evidence. No executable objects, absolute file paths or connection credentials. */
data class CapturedSource(val className: String, val fileName: String, val text: String, val sha256: String, val methods: Map<String, Int>) {
    fun validate() {
        require(className.matches(Regex("[\\w$]+(?:\\.[\\w$]+)*")) && className.length <= 512)
        require(fileName.matches(Regex("[\\w$.-]+\\.java")) && fileName.length <= 256)
        require(text.length <= MAX_SOURCE && sha256 == hash(text)) { "Recorded source checksum mismatch" }
        require(methods.size <= 512 && methods.all { it.key.length <= 4096 && it.value in 0..text.length })
    }
    companion object {
        const val MAX_SOURCE = 1_000_000
        fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

data class AsyncEvidence(val enabled:Boolean=false,val available:Boolean=false,val pending:Long=0,val dropped:Long=0)

data class CallRecording(val id: String, val calls: List<RecordedCall>, val sources: List<CapturedSource> = emptyList(), val epoch: Long = 0, val sql: SqlSnapshot = SqlSnapshot.disabled(), val hibernate: HibernateSnapshot = HibernateSnapshot.disabled(), val async:AsyncEvidence=AsyncEvidence()) {
    fun validate(values: Boolean = true, sourceChecks: Boolean = true) {
        require(async.pending>=0 && async.dropped>=0)
        require(id.matches(Regex("[a-f0-9-]{36}")) && epoch >= 0 && calls.size <= RecordedCall.MAX_CALLS)
        val index = calls.associateBy { it.id() }
        require(index.size == calls.size) { "Duplicate call identities" }
        calls.forEach { call ->
            require(call.recording() == id && call.id() <= RecordedCall.MAX_CALLS)
            if (call.parent() == 0L) require(call.root() == call.id()) else {
                val parent = requireNotNull(index[call.parent()]) { "Missing recorded parent" }
                require(call.root() == parent.root() && (call.threadId() == parent.threadId() || call.className() == "async.Task")) { "Invalid call tree" }
            }
            MethodDescriptor.parameters(call.descriptor())
            if (values) listOf(call.input(), call.output(), call.exception()).filter(String::isNotEmpty).forEach { ValueTree.decode(it) }
        }
        sql.events().forEach { event ->
            require(event.id() <= Long.MAX_VALUE - RecordingSql.BASE) { "SQL view identity exceeds limit" }
            val parent = requireNotNull(index[event.parent()]) { "SQL parent missing" }
            require(event.root() == parent.root() && event.thread() == parent.threadId()) { "Invalid SQL ancestry" }
        }
        val ormIndex=hibernate.events().associateBy { it.id() }
        hibernate.events().forEach { event ->
            require(event.id()<RecordingSql.BASE-RecordingHibernate.BASE) { "Hibernate view identity exceeds limit" }
            val parent=requireNotNull(index[event.parent()]) { "Hibernate Java parent missing" }
            require(event.root()==parent.root() && event.thread()==parent.threadId()) { "Invalid Hibernate ancestry" }
            require(event.parentOrm()==0L || event.parentOrm() in ormIndex || hibernate.partial()) { "Hibernate parent missing" }
        }
        sql.events().filter { it.orm()!=0L }.forEach { event ->
            val orm=ormIndex[event.orm()]
            require(orm!=null || hibernate.partial()) { "SQL Hibernate parent missing" }
            if(orm!=null)require(event.root()==orm.root() && event.thread()==orm.thread()) { "SQL and Hibernate context mismatch" }
        }
        require(sources.size <= 8 && sources.map { it.className }.toSet().size == sources.size && sources.sumOf { it.text.length.toLong() } <= 4_000_000)
        if (sourceChecks) sources.forEach(CapturedSource::validate)
    }
    fun source(call: RecordedCall) = sources.firstOrNull { it.className == call.className() }
    fun interrupted(reason: String) = copy(calls=calls.map { c -> if (c.status() == "RUNNING") RecordedCall(c.recording(),c.id(),c.parent(),c.root(),c.className(),c.method(),c.descriptor(),c.parameterNames(),c.threadId(),c.threadName(),c.startedAt(),c.durationNanos(),"INCOMPLETE",reason.take(4096),c.input(),"","",-1,c.revision()) else c })
    /** RUNNING is explicitly incomplete in a saved recording; opening it cannot resume a JVM frame. */
    fun encode(): String {
        validate()
        val records = interrupted("Call had not finished when saved").calls
        return Gson().toJson(mapOf("format" to "sbrepl-recording", "version" to 4, "id" to id, "epoch" to epoch,
            "calls" to records.map { it.encode() }, "sources" to sources, "sql" to sql.encode(), "hibernate" to hibernate.encode(), "async" to async)).also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Recording exceeds 64 MiB" } }
    }
    companion object {
        const val MAX_BYTES = 64 * 1024 * 1024
        fun decode(json: String): CallRecording {
            require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            try {
                val obj = JsonParser.parseString(json).asJsonObject
                require(obj["format"].asString == "sbrepl-recording" && obj["version"].asInt in 1..4)
                require(obj["calls"].asJsonArray.size() <= RecordedCall.MAX_CALLS && obj["sources"].asJsonArray.size() <= 8)
                return CallRecording(obj["id"].asString, obj["calls"].asJsonArray.map { RecordedCall.decode(it.asString) },
                    obj["sources"].asJsonArray.map { Gson().fromJson(it, CapturedSource::class.java) }, obj["epoch"].asLong, obj["sql"]?.let { SqlSnapshot.decode(it.asString) } ?: SqlSnapshot.disabled(),
                    obj["hibernate"]?.let { HibernateSnapshot.decode(it.asString) } ?: HibernateSnapshot.disabled(), obj["async"]?.let { Gson().fromJson(it,AsyncEvidence::class.java) } ?: AsyncEvidence()).also { it.validate() }
            } catch (e: Exception) { throw IllegalArgumentException("Invalid recording: ${e.message}", e) }
        }
    }
}

/** Exact JVM overload identity, including arrays and nested classes. Reject malformed imported descriptors. */
object MethodDescriptor {
    fun parameters(descriptor: String): List<String> {
        require(descriptor.length in 3..4096 && descriptor.startsWith('(')) { "Invalid method descriptor" }
        var at = 1
        fun type(allowVoid: Boolean): String {
            var arrays = 0
            while (at < descriptor.length && descriptor[at] == '[') { arrays++; at++; require(arrays <= 255) }
            require(at < descriptor.length)
            val name = when (val letter = descriptor[at++]) {
                'B' -> "byte"; 'C' -> "char"; 'D' -> "double"; 'F' -> "float"; 'I' -> "int"; 'J' -> "long"; 'S' -> "short"; 'Z' -> "boolean"
                'V' -> { require(allowVoid && arrays == 0); "void" }
                'L' -> {
                    val end = descriptor.indexOf(';', at); require(end > at)
                    descriptor.substring(at, end).also { require(it.matches(Regex("[\\w$/]+"))); at = end + 1 }.replace('/', '.').replace('$', '.')
                }
                else -> throw IllegalArgumentException("Invalid descriptor type: $letter")
            }
            return name + "[]".repeat(arrays)
        }
        val result = mutableListOf<String>()
        while (at < descriptor.length && descriptor[at] != ')') { require(result.size < 255); result += type(false) }
        require(at < descriptor.length && descriptor[at++] == ')'); type(true); require(at == descriptor.length)
        return result
    }
}
