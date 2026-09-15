package hu.baader.repl.workflow

/** A reload must succeed before any selected case may run. No retries or implicit replay. */
class CaseWorkflow(private val transport: Transport, private val status: (String) -> Unit,
                   private val result: (String, Map<String,String>) -> Unit, private val finished: () -> Unit) {
    fun interface Transport {
        fun send(op: String, fields: Map<String,String>, ok: (Map<String,String>) -> Unit, error: (String) -> Unit)
    }
    var busy = false; private set
    private var revision = 0
    private var cancelled = false
    fun start(names: List<String>, javaSource: String? = null) {
        check(!busy) { "A workflow is already running" }
        require(names.isNotEmpty() && names.size <= 20 && names.distinct().size == names.size) { "Select 1–20 distinct saved cases" }
        if (javaSource != null) require(javaSource.isNotBlank() && javaSource.length <= 1_000_000) { "Select a complete Java source file (up to 1 MB)" }
        busy = true; cancelled = false; val run = ++revision
        fun fail(message: String) { if (run == revision) { status(message); busy = false; finished() } }
        fun next(index: Int) {
            if (run != revision) return
            if (cancelled || index == names.size) { status(if (cancelled) "Stopped; no further cases were run" else "Selected cases completed"); busy = false; finished(); return }
            val name = names[index]; status("Running ${index+1}/${names.size}: $name")
            transport.send("case/run", mapOf("name" to name), {
                if (run == revision) {
                    result(name, it)
                    if (it["outcome"] == "CANCELLED") cancelled = true
                    next(index+1)
                }
            }, ::fail)
        }
        if (javaSource == null) next(0)
        else {
            status("Compiling and reloading selected Java source")
            transport.send("class-reload", mapOf("code" to javaSource), { if (run == revision) next(0) }, ::fail)
        }
    }
    fun cancel() {
        if (!busy) return
        cancelled = true; status("Stopping; waiting for the active operation")
        transport.send("interrupt", emptyMap(), {}, { status(it) })
    }
    fun invalidate() { revision++; cancelled = true; busy = false }
}
