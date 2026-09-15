package hu.baader.repl.nrepl

/** A selected mode is not executable until the same connection has acknowledged it. */
class ExecutionSelection {
    data class Policy(val mode: String, val manager: String, val timeoutMs: Int) {
        init {
            require(mode in setOf("LIVE", "ROLLBACK", "READ_ONLY"))
            require(timeoutMs in 100..120000)
        }
        fun arguments() = mapOf("execution-mode" to mode, "transaction-manager" to manager, "timeout-ms" to timeoutMs.toString())
        companion object {
            fun from(response: Map<String, String>) = Policy(response.getValue("execution-mode"),
                response["transaction-manager"].orEmpty(), response.getValue("timeout-ms").toInt())
        }
    }
    private var revision = 0L
    @Volatile var confirmed: Policy? = null; private set
    @Volatile var requested: Policy? = null; private set
    @Volatile var pending = false; private set
    @Volatile var error = ""; private set
    val ready get() = confirmed != null && !pending && error.isEmpty()
    @Synchronized fun reset() { revision++; confirmed = null; requested = null; pending = false; error = "" }
    @Synchronized fun begin(policy: Policy? = null): Long { requested = policy; pending = true; error = ""; return ++revision }
    @Synchronized fun accept(ticket: Long, response: Map<String, String>): Boolean {
        if (ticket != revision) return false
        confirmed = Policy.from(response); requested = null; pending = false; error = ""; return true
    }
    @Synchronized fun fail(ticket: Long, message: String): Boolean {
        if (ticket != revision) return false
        confirmed = null; requested = null; pending = false; error = message; return true
    }
}
