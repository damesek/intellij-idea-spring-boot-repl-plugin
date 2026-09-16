package hu.baader.repl.trace

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import hu.baader.repl.nrepl.NreplService
import hu.baader.repl.protocol.RecordedCall
import java.util.concurrent.CompletableFuture
import javax.swing.Timer

/** EDT-owned state. Generation + recording + revision checks keep late responses out of newer recordings. */
@Service(Service.Level.PROJECT)
class RecordingController(private val project: Project) : Disposable {
    private val service = NreplService.getInstance(project)
    var recording: CallRecording? = null; private set
    var active = false; private set
    var starting = false; private set
    var offline = false; private set
    var message = "Record application methods, then select a call to see its source and data."; private set
    var selected: Long? = null; private set
    var followLatest = true
    var inline = true
        set(value) { field=value; if (!value) RecordedCallInlays.get(project).clear() }
    var showPanel: (() -> Unit)? = null
    var configurePanel: ((String) -> Unit)? = null
    var navigate: ((RecordedCall,Boolean) -> Unit)? = null
    private val listeners = linkedSetOf<() -> Unit>()
    private val loaded = mutableMapOf<Long,Long>()
    private var target: Pair<String,Long>? = null
    private var generation = 0
    private var polling = false
    private var fetching = false
    private var disposed = false
    private var openSelectedSource = true
    private val timer = Timer(750) { if (!offline && !starting && recording != null && (active || recording!!.calls.any { it.status()=="RUNNING" } || !complete())) refresh() }
    private val subscription = service.onMessage { event ->
        if (!offline && recording != null && event["op"] in setOf("describe","bind-spring") && event["context-epoch"]?.toLongOrNull()?.let { it != recording!!.epoch } == true)
            detach("Spring context changed. Recorded values are retained; live references are unavailable.")
        if (event["op"] == "session/reset" || event["op"] == "connection" && event["state"] in setOf("CONNECTING","DISCONNECTED","FAILED")) {
            generation++; polling=false; fetching=false; starting=false; active=false; target=null
            if (recording != null) { offline=true; recording=recording?.interrupted("Session ended before completion was downloaded"); message="Session ended. Downloaded recorded values are retained; live references are unavailable."; changed() }
        }
        if (event["op"] == "trace/clear" && recording != null) detach("Traces cleared. Downloaded recorded values are retained.")
        if (event["op"] == "class-reload" && recording != null) {
            message="Code was reloaded. Start a new recording to capture updated source; this recording keeps its original source."; changed()
            if (active) stop()
        }
    }
    init { timer.start() }
    fun listen(listener: () -> Unit): Disposable { listeners += listener; return Disposable { listeners -= listener } }
    private fun changed() { if (!disposed) listeners.toList().forEach { it() } }
    fun error(text: String) { message=text; changed() }
    fun complete() = recording?.let { r -> r.sql.pending() == 0L && r.hibernate.pending()==0L && r.async.pending==0L && r.calls.all { loaded[it.id()] == it.revision() } } == true
    fun downloaded(call: RecordedCall) = call.recording() == recording?.id && loaded[call.id()] == call.revision()
    fun live(call: RecordedCall) = !offline && target != null && target == service.debuggerTarget() && service.isConnected() && call.recording() == recording?.id && call.event() >= 0
    fun start(classes: List<String>, sql: Boolean = true, threshold: Int = 5, hibernate: Boolean = sql, captureData: Boolean = false, async: Boolean = false): CompletableFuture<String> {
        val completion = CompletableFuture<String>()
        fun fail(reason: String) { error(reason); completion.completeExceptionally(IllegalStateException(reason)) }
        if (starting || active) { fail("Stop the current recording first."); return completion }
        if (!service.isConnected()) { fail("Connect to the application before recording."); return completion }
        try {
            require(classes.size in 1..8 && classes.all { it.length <= 512 && it.matches(Regex("[\\w$]+(?:\\.[\\w$]+)+")) }) { "Enter 1–8 exact application class names, one per line." }
            require(threshold in 2..1000) { "N+1 threshold must be 2–1000" }
            require(!hibernate || sql) { "Hibernate capture requires SQL capture" }
            val sources = RecordingSource.capture(project,classes)
            val expectedTarget = service.debuggerTarget(); val ticket=++generation
            starting=true; polling=false; fetching=false; message="Preparing class recording…"; changed()
            service.request("trace/record",mapOf("classes" to classes.joinToString("\n"), "sql" to sql.toString(), "n-plus-one-threshold" to threshold.toString(), "hibernate" to hibernate.toString(), "capture-data" to captureData.toString(), "async" to async.toString()), { response ->
                if (!valid(ticket) || expectedTarget != service.debuggerTarget()) {
                    completion.completeExceptionally(IllegalStateException("Recording target changed during start")); return@request
                }
                starting=false; offline=false; target=expectedTarget; selected=null; loaded.clear(); followLatest=true
                RecordedCallInlays.get(project).clear()
                recording=CallRecording(response["recording"].orEmpty(),emptyList(),sources,response["context-epoch"]?.toLongOrNull() ?: 0)
                accept(response); showPanel?.invoke(); refresh()
                if (offline) completion.completeExceptionally(IllegalStateException(message))
                else completion.complete(recording!!.id)
            }, {
                if (valid(ticket)) { starting=false; error(it) }
                completion.completeExceptionally(IllegalStateException(it))
            })
        } catch (e: Exception) { starting=false; fail(e.message ?: "Could not prepare class recording") }
        return completion
    }
    private fun valid(ticket: Int) = !disposed && !project.isDisposed && ticket == generation
    private fun detach(reason: String) { generation++; polling=false; fetching=false; active=false; offline=true; target=null; recording=recording?.interrupted(reason); message=reason; changed() }
    fun stop(): CompletableFuture<Unit> {
        if (offline || recording == null || !service.isConnected()) return CompletableFuture.completedFuture(Unit)
        val completion = CompletableFuture<Unit>()
        val ticket=generation
        service.request("trace/stop",onResult={
            if (valid(ticket)) { accept(it); refresh(); completion.complete(Unit) }
            else completion.completeExceptionally(IllegalStateException("Recording changed during stop"))
        },onError={
            if (valid(ticket)) error(it)
            completion.completeExceptionally(IllegalStateException(it))
        })
        return completion
    }
    fun refresh() {
        if (disposed || offline || polling || starting || recording == null || !service.isConnected()) return
        polling=true; val ticket=generation
        service.request("trace/history",onResult={ if (valid(ticket)) { polling=false; accept(it) } },onError={ if (valid(ticket)) { polling=false; detach("Recording unavailable: $it. Downloaded values are retained.") } })
    }
    private fun accept(response: Map<String,String>) {
        val current=recording ?: return
        if (response["recording"] != current.id) { detach("Runtime recording changed. Downloaded values are retained; start a new recording to continue."); return }
        try {
            val wire=response["value"].orEmpty(); require(wire.length <= 4_000_000)
            val headers=wire.lineSequence().filter(String::isNotEmpty).map(RecordedCall::decode).toList()
            require(headers.size <= RecordedCall.MAX_CALLS)
            val old=current.calls.associateBy { it.id() }
            val sql = response["sql"]?.let(hu.baader.repl.protocol.SqlSnapshot::decode) ?: current.sql
            val hibernate=response["hibernate"]?.let(hu.baader.repl.protocol.HibernateSnapshot::decode) ?: current.hibernate
            recording=current.copy(sql=sql, hibernate=hibernate, async=AsyncEvidence(response["async"]=="true",response["async-available"]=="true",response["async-pending"]?.toLongOrNull()?:0,response["async-dropped"]?.toLongOrNull()?:0), calls=headers.map { h -> old[h.id()]?.takeIf { it.revision() >= h.revision() } ?: h }).also { it.validate(false,false) }
            active=response["active"] == "true"
            message="${if (active) "Recording" else "Stopped"} · ${headers.size}/200 calls · ${response["bytes"]?.toLongOrNull()?.div(1024) ?: 0} KiB of previews · omitted: ${response["dropped"] ?: "0"}"
            changed(); fetchNext()
            // Remove now-inert instrumentation after a recording limit or context change.
            if (!active && !response["classes"].isNullOrBlank()) stop()
        } catch (e: Exception) { detach("Invalid recording response: ${e.message}") }
    }
    private fun fetchNext() {
        if (fetching || offline || disposed) return
        val current=recording ?: return
        val needed=current.calls.firstOrNull { it.id()==selected && loaded[it.id()]!=it.revision() }
            ?: current.calls.firstOrNull { loaded[it.id()]!=it.revision() } ?: return
        fetching=true; val ticket=generation
        service.request("trace/call",mapOf("recording" to current.id,"call-id" to needed.id().toString()), { response ->
            if (!valid(ticket)) return@request
            fetching=false
            try {
                val call=RecordedCall.decode(response["value"]); val state=recording ?: return@request
                require(call.id()==needed.id() && call.recording()==state.id)
                val existing=state.calls.firstOrNull { it.id()==call.id() }
                if (existing != null && call.revision() >= existing.revision()) {
                    recording=state.copy(calls=state.calls.map { if (it.id()==call.id()) call else it })
                    loaded[call.id()]=call.revision()
                    if (inline && followLatest && call.status() != "RUNNING") {
                        RecordedCallInlays.get(project).show(call,state.source(call),false) { select(call.id(),false); showPanel?.invoke() }
                    }
                    if (followLatest) selected=call.id()
                    if (selected == call.id() && !followLatest) navigate?.invoke(call,openSelectedSource)
                    changed()
                }
                fetchNext()
            } catch (e: Exception) { detach("Cannot read recorded values: ${e.message}") }
        }, { if (valid(ticket)) { fetching=false; detach("Cannot download recorded values: $it") } })
    }
    fun select(id: Long, openSource: Boolean = true) {
        val call=recording?.calls?.firstOrNull { it.id()==id } ?: return
        selected=id; followLatest=false; openSelectedSource=openSource; changed()
        if (loaded[id]==call.revision()) navigate?.invoke(call,openSource) else fetchNext()
    }
    fun selectedCall() = recording?.calls?.firstOrNull { it.id()==selected }
    fun open(record: CallRecording) {
        require(!active && !starting) { "Stop recording before opening another recording." }
        record.validate(); generation++; polling=false; fetching=false; offline=true; target=null; loaded.clear(); followLatest=false
        RecordedCallInlays.get(project).clear(); recording=record.interrupted("Call completion was not captured in this saved recording"); record.calls.forEach { loaded[it.id()]=it.revision() }
        selected=record.calls.firstOrNull()?.id(); message="Saved recording · ${record.calls.size} calls · captured source and values · no live references"
        changed()
    }
    override fun dispose() { disposed=true; timer.stop(); subscription.dispose(); listeners.clear(); showPanel=null; navigate=null; configurePanel=null }
    companion object { fun get(project: Project): RecordingController = project.service() }
}
