package hu.baader.repl.mcp

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class McpTasksEventsTest {
    private class Backend : McpBackend {
        val operations=CopyOnWriteArrayList<String>()
        var answer:(String,Map<String,String>)->CompletableFuture<Map<String,String>> = {_,_->CompletableFuture.completedFuture(mapOf("value" to "42"))}
        var closed=false
        override fun request(operation:String,arguments:Map<String,String>):CompletableFuture<Map<String,String>> {operations+=operation;return answer(operation,arguments)}
        override fun close(){closed=true}
    }
    private val headers=mapOf("Host" to "127.0.0.1:1234","Authorization" to "Bearer secret","Content-Type" to "application/json","Accept" to "application/json, text/event-stream")
    private val ids=AtomicInteger()
    private fun request(method:String,session:String?=null,params:JsonObject=JsonObject(),notification:Boolean=false):McpHttpRequest {
        val json=McpJson.objectOf("jsonrpc" to "2.0","method" to method,"params" to params)
        if(!notification)json.addProperty("id",ids.incrementAndGet())
        return McpHttpRequest("POST","/mcp",headers+if(session==null)emptyMap() else mapOf("MCP-Session-Id" to session),json.toString())
    }
    private fun init(router:McpRouter,version:String="2025-11-25"):String {
        val response=router.handle(request("initialize",params=McpJson.objectOf("protocolVersion" to version,"capabilities" to JsonObject(),"clientInfo" to mapOf("name" to "task-test","version" to "1"))))
        val session=response.headers.getValue("MCP-Session-Id");router.handle(request("notifications/initialized",session,notification=true));return session
    }
    private fun result(r:McpHttpResponse)=r.body!!.getAsJsonObject("result")
    private fun code(r:McpHttpResponse)=r.body!!.getAsJsonObject("error")["code"].asInt
    private fun start(router:McpRouter,session:String)=router.handle(request("tools/call",session,McpJson.objectOf("name" to "repl_eval","arguments" to mapOf("code" to "work()"),"task" to mapOf("ttl" to 600000))))
    private fun task(router:McpRouter,session:String,id:String,method:String)=router.handle(request("tasks/$method",session,McpJson.objectOf("taskId" to id)))
    private fun router(backend:()->McpBackend,permissions:McpPermissions=McpPermissions(execution=true,executionMode="LIVE"))=McpRouter("secret","127.0.0.1:1234",permissions,backend)
    @Test fun taskReturnsImmediatelyPreservesResultAndCannotBeReadByAnotherSession(){
        val backend=Backend();val pending=CompletableFuture<Map<String,String>>();val entered=CountDownLatch(1)
        backend.answer={op,args->assertEquals("eval",op);assertEquals("LIVE",args["execution-mode"]);entered.countDown();pending}
        router({backend}).use { router->
            val session=init(router);val other=init(router)
            val created=result(start(router,session)).getAsJsonObject("task");val id=created["taskId"].asString
            assertEquals("working",created["status"].asString);assertTrue(entered.await(5,TimeUnit.SECONDS))
            assertEquals(-32602,code(task(router,other,id,"get")))
            assertEquals(-32000,code(start(router,session)))
            assertEquals(1,result(router.handle(request("tasks/list",session))).getAsJsonArray("tasks").size())
            pending.complete(mapOf("value" to "42","status" to "done"))
            val response=result(task(router,session,id,"result"));assertFalse(response["isError"].asBoolean)
            assertEquals("42",response.getAsJsonObject("structuredContent")["value"].asString)
            assertEquals(id,response.getAsJsonObject("_meta").getAsJsonObject("io.modelcontextprotocol/related-task")["taskId"].asString)
            assertEquals("completed",result(task(router,session,id,"get"))["status"].asString)
            assertEquals(-32602,code(task(router,session,id,"cancel")));assertEquals(listOf("eval"),backend.operations)
        }
    }
    @Test fun cancellationStaysTerminalAndKeepsSessionBusyUntilExecutionActuallyReturns(){
        val backend=Backend();val pending=CompletableFuture<Map<String,String>>();val entered=CountDownLatch(1)
        backend.answer={op,_->if(op=="eval"){entered.countDown();pending}else CompletableFuture.completedFuture(mapOf("value" to "interrupted"))}
        router({backend}).use {router->
            val session=init(router);val id=result(start(router,session)).getAsJsonObject("task")["taskId"].asString
            assertTrue(entered.await(5,TimeUnit.SECONDS));assertEquals("cancelled",result(task(router,session,id,"cancel"))["status"].asString)
            assertTrue(result(task(router,session,id,"result"))["isError"].asBoolean);assertEquals(-32000,code(start(router,session)))
            assertEquals(listOf("eval","interrupt"),backend.operations)
            pending.complete(mapOf("value" to "late success"))
            assertEquals("cancelled",result(task(router,session,id,"get"))["status"].asString)
            assertEquals(-32602,code(task(router,session,id,"cancel")))
        }
    }
    @Test fun tasksRespectPermissionsAndOldClientsKeepSynchronousCalls(){
        val backend=Backend()
        router({backend},McpPermissions()).use {router->val session=init(router);assertTrue(result(start(router,session))["isError"].asBoolean);assertTrue(backend.operations.isEmpty())}
        router({backend}).use {router->
            val old=init(router,"2025-06-18");assertFalse(result(start(router,old))["isError"].asBoolean)
            assertEquals(-32601,code(router.handle(request("tasks/list",old))))
            val tools=result(router.handle(request("tools/list",old))).getAsJsonArray("tools");assertTrue(tools.none { it.asJsonObject.has("execution") })
            val current=init(router)
            val currentTools=result(router.handle(request("tools/list",current))).getAsJsonArray("tools")
            assertEquals("optional",currentTools.first { it.asJsonObject["name"].asString=="repl_eval" }.asJsonObject.getAsJsonObject("execution")["taskSupport"].asString)
        }
    }
    @Test fun failedTransportRetainsTaskResultButPreventsNewExecution(){
        val backend=Backend();backend.answer={_,_->CompletableFuture.failedFuture(java.io.IOException("lost"))}
        router({backend}).use {router->val session=init(router);val id=result(start(router,session)).getAsJsonObject("task")["taskId"].asString
            assertTrue(result(task(router,session,id,"result"))["isError"].asBoolean)
            assertEquals("failed",result(task(router,session,id,"get"))["status"].asString)
            assertEquals(-32000,code(start(router,session)));assertEquals(listOf("eval"),backend.operations);assertTrue(backend.closed)
        }
    }
    @Test fun subscribedResourcesPushAuthenticatedSseAndReplayOnlyWithinTheirSession(){
        val backend=Backend();var revision=0
        backend.answer={op,_->assertEquals("notifications/poll",op);CompletableFuture.completedFuture(mapOf("cursor" to (++revision).toString(),"events-json" to "[{\"sequence\":$revision,\"kind\":\"capture.completed\"}]","gap" to "false"))}
        router({backend}).use {router->
            val session=init(router);val second=init(router)
            val get=McpHttpRequest("GET","/mcp",headers+("MCP-Session-Id" to session))
            assertEquals(401,router.handle(get.copy(headers=get.headers-"Authorization")).status)
            assertEquals(400,router.handle(get.copy(headers=get.headers+("MCP-Protocol-Version" to "2025-06-18"))).status)
            router.handle(request("resources/subscribe",session,McpJson.objectOf("uri" to McpEvents.URI)))
            val stream=router.handle(get).stream!!;assertEquals(409,router.handle(get).status)
            router.pollEvents();val wire=stream.next(1)!!;assertTrue(wire.contains("notifications/resources/updated"))
            val eventId=wire.lineSequence().first { it.startsWith("id: ") }.removePrefix("id: ")
            val resource=result(router.handle(request("resources/read",session,McpJson.objectOf("uri" to McpEvents.URI))))
            assertTrue(resource.toString().contains("capture.completed"))
            stream.close();router.pollEvents()
            router.handle(get.copy(headers=get.headers+("Last-Event-ID" to eventId))).stream!!.use { assertTrue(it.next(1)!!.contains("notifications/resources/updated")) }
            assertEquals(409,router.handle(get.copy(headers=headers+mapOf("MCP-Session-Id" to second,"Last-Event-ID" to eventId))).status)
            router.handle(request("resources/unsubscribe",session,McpJson.objectOf("uri" to McpEvents.URI)))
            router.handle(get).stream!!.use {router.pollEvents();assertEquals(": heartbeat\n\n",it.next(1))}
        }
    }
    @Test fun newToolsEnforceSeparateWriteAndCapturePermissions(){
        val read=McpPermissions(recordingAccess=true)
        for(name in listOf("repl_bean_info","repl_case_affected","repl_recording_case_info","repl_watch_list"))assertTrue(McpTools.all.first { it.name==name }.enabled(read))
        for(name in listOf("repl_snapshot_edit_copy","repl_case_variants","repl_recording_case_create","repl_watch_refresh"))assertFalse(McpTools.all.first { it.name==name }.enabled(read))
        val execution=read.copy(execution=true)
        assertFalse(McpTools.all.first { it.name=="repl_recording_case_create" }.enabled(execution))
        assertTrue(McpTools.all.first { it.name=="repl_recording_case_create" }.enabled(execution.copy(snapshotWrites=true)))
        assertEquals(82,McpTools.all.map { it.name }.toSet().size)
    }
}
