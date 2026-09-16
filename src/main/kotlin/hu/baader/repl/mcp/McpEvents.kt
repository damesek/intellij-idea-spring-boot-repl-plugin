package hu.baader.repl.mcp

import com.google.gson.JsonObject
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded notifications for one authenticated MCP session. One GET stream; no cross-client replay. */
internal class McpEvents : AutoCloseable {
    private val lock=java.lang.Object()
    private val prefix=java.util.UUID.randomUUID().toString()
    private val messages=ArrayDeque<Pair<Long,String>>()
    private var sequence=0L
    private var closed=false
    private var streaming=false
    @Volatile var subscribed=false
    @Volatile var cursor="0"
    @Volatile var resource=McpJson.objectOf("cursor" to 0,"events" to emptyList<Any>(),"gap" to false)
    val polling=AtomicBoolean()
    fun emit(method:String,params:JsonObject)=synchronized(lock){
        if(!closed){
            messages.addLast(++sequence to McpJson.objectOf("jsonrpc" to "2.0","method" to method,"params" to params).toString())
            while(messages.size>256)messages.removeFirst()
            lock.notifyAll()
        }
    }
    fun update(response:Map<String,String>){
        if(response.containsKey("err"))return
        val next=response["cursor"] ?: return
        val changed=next!=cursor || response["ide-recording-revision"]!=resource["ideRecordingRevision"]?.takeIf { it.isJsonPrimitive }?.asString
        if(changed){
            val rows=McpJson.parse(response["events-json"] ?: "[]")
            val prior=resource["events"]?.asJsonArray?.toList().orEmpty()
            resource=McpJson.objectOf("cursor" to next,"events" to (prior+rows.asJsonArray.toList()).takeLast(128),
                "gap" to (response["gap"]=="true" || resource["gap"]?.asBoolean==true),
                "ideRecordingRevision" to response["ide-recording-revision"],
                "note" to "Metadata only, bounded to 128 runtime events. Resource notifications are invalidations; read this resource after an update. No code or values are broadcast.")
            cursor=next
            if(subscribed)emit("notifications/resources/updated",McpJson.objectOf("uri" to URI))
        }
    }
    fun open(lastEvent:String?): Stream=synchronized(lock){
        check(!closed) { "Session closed" };require(!streaming) { "One event stream per MCP session" }
        val after=if(lastEvent==null)sequence else {
            require(lastEvent.startsWith("$prefix:")) { "Unknown event stream; read resources/tasks and reconnect without Last-Event-ID" }
            lastEvent.substringAfterLast(':').toLongOrNull() ?: throw IllegalArgumentException("Invalid event ID")
        }
        require(after in 0..sequence && (messages.isEmpty() || after>=messages.first().first-1)) { "Event replay expired; read resources/tasks and reconnect without Last-Event-ID" }
        streaming=true;Stream(after)
    }
    inner class Stream(private var after:Long) : AutoCloseable {
        private val ended=AtomicBoolean()
        internal fun next(waitMillis:Long=15000):String?=synchronized(lock){
            if(!closed&&!ended.get()&&messages.none { it.first>after })lock.wait(waitMillis.coerceIn(1,15000))
            if(closed||ended.get())return null
            if(messages.isNotEmpty()&&after<messages.first().first-1){close();return null}
            val available=messages.filter { it.first>after }
            if(available.isEmpty())return ": heartbeat\n\n"
            after=available.last().first
            available.joinToString("") { (id,json)->"id: $prefix:$id\nevent: message\ndata: $json\n\n" }
        }
        fun write(output:OutputStream){
            output.write(": connected\n\n".toByteArray());output.flush()
            while(true){val chunk=next() ?: break;output.write(chunk.toByteArray(Charsets.UTF_8));output.flush()}
        }
        override fun close(){if(ended.compareAndSet(false,true))synchronized(lock){streaming=false;lock.notifyAll()}}
    }
    override fun close()=synchronized(lock){closed=true;messages.clear();lock.notifyAll()}
    companion object {const val URI="repl://session/events"}
}
