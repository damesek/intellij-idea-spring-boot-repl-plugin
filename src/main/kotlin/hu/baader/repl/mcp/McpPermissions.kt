package hu.baader.repl.mcp

internal data class McpPermissions(
    val execution: Boolean = false, val reload: Boolean = false,
    val snapshotWrites: Boolean = false, val snapshotDelete: Boolean = false, val caseRuns: Boolean = false,
    val captureChanges: Boolean = false, val allowedTools: Set<String>? = null,
    val executionMode: String = "ROLLBACK", val transactionManager: String = "",
    val timeoutMillis: Int = 30000, val sessionQuota: Int = 1000, val maxResultChars: Int = 65536,
    val redactResults: Boolean = true, val recordingAccess: Boolean = false
) {
    init {
        require(executionMode in setOf("LIVE", "ROLLBACK", "READ_ONLY"))
        require(timeoutMillis in 100..120000 && sessionQuota in 1..100000 && maxResultChars in 1024..65536)
        require(transactionManager.length <= 256)
    }
}
