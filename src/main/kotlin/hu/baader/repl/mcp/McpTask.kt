package hu.baader.repl.mcp

import com.google.gson.JsonObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** In-memory task owned by one MCP session; terminal state never changes after cancellation. */
internal class McpTask(val requestKey:String,val ttl:Long) {
    val id=UUID.randomUUID().toString()
    val createdMillis=System.currentTimeMillis()
    private val created=Instant.ofEpochMilli(createdMillis).toString()
    private var updated=created
    private var state="working"
    val result=CompletableFuture<JsonObject>()
    @Synchronized fun terminal()=state!="working"
    @Synchronized fun cancelled()=state=="cancelled"
    @Synchronized fun view()=McpJson.objectOf("taskId" to id,"status" to state,"createdAt" to created,"lastUpdatedAt" to updated,
        "ttl" to ttl,"pollInterval" to 1000,"statusMessage" to if(state=="cancelled") "Cooperative interruption requested; application effects may already have occurred." else "Task $state")
    @Synchronized fun complete(response:JsonObject){
        if(terminal())return
        state=if(response.has("error")||response["result"]?.asJsonObject?.get("isError")?.asBoolean==true)"failed" else "completed"
        updated=Instant.now().toString();result.complete(response)
    }
    @Synchronized fun cancel(){
        if(terminal())throw McpError(-32602,"Task is already terminal")
        state="cancelled";updated=Instant.now().toString()
        result.complete(McpJson.objectOf("result" to McpTools.error("Task cancelled. Interruption is cooperative; effects may have occurred. Session stays busy until execution returns.")))
    }
}
