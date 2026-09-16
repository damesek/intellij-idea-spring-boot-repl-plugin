package hu.baader.repl.mcp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import hu.baader.repl.trace.CallRecording
import hu.baader.repl.trace.RecordingController
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/** An immutable view copied on the EDT; MCP never borrows the IDE's Java evaluator. */
internal data class McpRecordingState(
    val recording: CallRecording?, val active: Boolean = false, val starting: Boolean = false,
    val offline: Boolean = false, val selected: Long? = null, val downloaded: Set<Long> = emptySet(),
    val message: String = ""
)

internal interface McpRecordingAccess : AutoCloseable {
    fun read(): CompletableFuture<McpRecordingState>
    fun start(expected: String, classes: List<String>): CompletableFuture<McpRecordingState>
    fun startSql(expected: String, classes: List<String>, sql: Boolean, threshold: Int): CompletableFuture<McpRecordingState> = start(expected,classes)
    fun startHibernate(expected: String, classes: List<String>, sql: Boolean, threshold: Int, hibernate: Boolean): CompletableFuture<McpRecordingState> = startSql(expected,classes,sql,threshold)
    fun startWorkflow(expected: String, classes: List<String>, sql: Boolean, threshold: Int, hibernate: Boolean, captureData: Boolean, async: Boolean): CompletableFuture<McpRecordingState> = startHibernate(expected,classes,sql,threshold,hibernate)
    fun experiment(recording: String, call: Long, name: String?, bean: String, actor: String): CompletableFuture<Map<String,String>> = CompletableFuture.failedFuture(IllegalStateException("Full replay DATA is unavailable"))
    fun stop(recording: String): CompletableFuture<McpRecordingState>
    fun select(recording: String, call: Long): CompletableFuture<McpRecordingState>
}

/** One adapter per MCP client. Closing it cancels queued actions, never stops a user's shared recording. */
internal class IdeMcpRecordingAccess(private val project: Project) : McpRecordingAccess {
    private val closed = AtomicBoolean()
    private fun snapshot(controller: RecordingController) = McpRecordingState(
        controller.recording, controller.active, controller.starting, controller.offline, controller.selected,
        controller.recording?.calls.orEmpty().filter(controller::downloaded).map { it.id() }.toSet(), controller.message
    )
    private fun dispatch(action: (RecordingController) -> CompletableFuture<McpRecordingState>): CompletableFuture<McpRecordingState> {
        val result = CompletableFuture<McpRecordingState>()
        ApplicationManager.getApplication().invokeLater {
            if (closed.get() || project.isDisposed || result.isCancelled) {
                result.completeExceptionally(IllegalStateException("Recording access closed"))
            } else try {
                action(RecordingController.get(project)).whenComplete { state, error ->
                    if (error != null) result.completeExceptionally(error) else result.complete(state)
                }
            } catch (e: Exception) { result.completeExceptionally(e) }
        }
        return result
    }
    override fun read() = dispatch { CompletableFuture.completedFuture(snapshot(it)) }
    override fun start(expected: String, classes: List<String>) = dispatch { controller ->
        require((controller.recording?.id ?: "none") == expected) { "Recording changed; read repl_recording_status before starting" }
        controller.start(classes).thenApply { snapshot(controller) }
    }
    override fun startSql(expected: String, classes: List<String>, sql: Boolean, threshold: Int) = dispatch { controller ->
        require((controller.recording?.id ?: "none") == expected) { "Recording changed; read status before starting" }
        controller.start(classes,sql,threshold).thenApply { snapshot(controller) }
    }
    override fun startHibernate(expected: String, classes: List<String>, sql: Boolean, threshold: Int, hibernate: Boolean) = dispatch { controller ->
        require((controller.recording?.id ?: "none") == expected) { "Recording changed; read status before starting" }
        controller.start(classes,sql,threshold,hibernate).thenApply { snapshot(controller) }
    }
    override fun startWorkflow(expected: String, classes: List<String>, sql: Boolean, threshold: Int, hibernate: Boolean, captureData: Boolean, async: Boolean) = dispatch { controller ->
        require((controller.recording?.id ?: "none") == expected) { "Recording changed; read status before starting" }
        controller.start(classes,sql,threshold,hibernate,captureData,async).thenApply { snapshot(controller) }
    }
    override fun experiment(recording: String, call: Long, name: String?, bean: String, actor: String): CompletableFuture<Map<String,String>> {
        val result=CompletableFuture<Map<String,String>>()
        ApplicationManager.getApplication().invokeLater {
            try {
                check(!closed.get() && !project.isDisposed) { "Recording access closed" }
                val controller=RecordingController.get(project)
                require(!controller.offline && controller.recording?.id==recording && controller.recording!!.calls.any { it.id()==call }) { "Live recording or call changed" }
                val args=mutableMapOf("recording" to recording,"call-id" to call.toString(),"bean" to bean,"audit-actor" to actor)
                if(name!=null)args["name"]=name
                hu.baader.repl.nrepl.NreplService.getInstance(project).request(if(name==null)"trace/case-info" else "trace/case-create",args,
                    {result.complete(it)},{result.complete(mapOf("err" to it))})
            }catch(e:Exception){result.completeExceptionally(e)}
        }
        return result
    }
    override fun stop(recording: String) = dispatch { controller ->
        require(controller.recording?.id == recording) { "Recording changed; no recording was stopped" }
        require(!controller.starting && !controller.offline) { "This is not a live recording" }
        controller.stop().thenApply { snapshot(controller) }
    }
    override fun select(recording: String, call: Long) = dispatch { controller ->
        require(controller.recording?.id == recording && controller.recording!!.calls.any { it.id() == call }) {
            "Recording or call changed; no source was selected"
        }
        controller.showPanel?.invoke()
        controller.select(call, true)
        CompletableFuture.completedFuture(snapshot(controller))
    }
    override fun close() { closed.set(true) }
}
