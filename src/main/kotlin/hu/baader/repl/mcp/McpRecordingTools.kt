package hu.baader.repl.mcp

/** Tools for the IDE's shared frozen recording, not an MCP client's evaluator session. */
internal object McpRecordingTools {
    private fun text(name: String, description: String, required: Boolean = false, max: Int = 256) =
        McpParameter(name, description, required, max)
    private fun number(name: String, description: String, max: Long, required: Boolean = false) =
        McpParameter(name, description, required, maxInteger = max)
    private val recording = text("recording", "Exact recording ID from repl_recording_status; stale IDs are rejected", true, 36)
    private val view = text("view", "Optional view revision from a previous response; rejects changing data between pages", max = 64)
    private val identity = listOf(recording, view)
    private val call = number("call", "Recorded call ID, never an event ID or a live object handle", Long.MAX_VALUE, true)
    private val page = listOf(number("offset", "Zero-based row offset; default 0", 1000000),
        number("limit", "Maximum rows per page; default 20, max 50. Response may return fewer to fit its budget", 50))
    private val filters = listOf(
        text("query", "Case-insensitive search in captured method names and decoded, redacted values", max = 512),
        text("errors-only", "true or false; default false"),
        number("root", "Only this root invocation", Long.MAX_VALUE),
        number("focus", "This call and descendants, retaining recorded ancestor paths", Long.MAX_VALUE),
        number("thread", "Only this recorded thread ID", Long.MAX_VALUE),
        number("min-duration-ms", "Minimum duration in milliseconds", 86400000),
        number("from-ms", "Start of time window relative to earliest recorded start; requires to-ms", 86400000),
        number("to-ms", "End of time window; requires from-ms", 86400000)
    )
    val all = listOf(
        McpTool("repl_recording_status", "recording/status", "Read the current IDE recording, including an opened saved recording. Shared across clients; requires Share IDE recordings. No Java evaluation. Missing recording is reported as available=false."),
        McpTool("repl_recording_calls", "recording/calls", "Browse the same call tree as the IDE. Paginated structured nodes with parent/root/depth, timing, summaries and contextOnly ancestors. Filters search frozen previews only. Follow nextOffset with the returned view revision.",
            identity + filters + text("collapsed", "Optional comma-separated call IDs to fold; matching search descendants stay visible", max=4096) + page),
        McpTool("repl_recording_call", "recording/call", "Read one call's signature, source availability, breadcrumb IDs, children and previous/next/next-error navigation. Values are available separately through repl_recording_values.", identity + call),
        McpTool("repl_recording_values", "recording/values", "Browse frozen input/result/exception trees without executing getters. path is a child-index path, e.g. /0/2, preserving duplicate field names. Page children with offset, scalar text with text-offset. downloaded/partial flags distinguish missing or limited captures.",
            identity + call + text("part", "input, result or exception", true) +
                text("path", "Empty for root, otherwise slash-separated child indexes", max=256) + page +
                number("text-offset", "UTF-16 offset into this node's redacted text", 1000000) +
                number("text-limit", "Text characters per page; default 1024, max 4096", 4096)),
        McpTool("repl_recording_source", "recording/source", "Read source captured at recording start for this call's exact overload. Does not read arbitrary files or current editor contents. Paged UTF-16 offsets refer to redacted source; methodLine refers to original captured source.",
            identity + call + number("offset", "UTF-16 offset; default 0", 1000000) + number("limit", "Characters; default 2048, max 4096", 4096)),
        McpTool("repl_recording_timeline", "recording/timeline", "Read per-thread spans with relative millisecond start/end and recorded parent IDs. Only actual same-thread ancestry is reported; no inferred async links. Same filters as calls, without folding.",
            identity + filters + page),
        McpTool("repl_recording_pin", "recording/pin", "Pin one downloaded frozen call as this MCP client's reference. Replaces this client's previous pin; retained across new IDE recordings until the client session closes. Does not evaluate Java or retain live objects.",
            identity + call, stateChange=true),
        McpTool("repl_recording_compare", "recording/compare", "Compare frozen input/result/exception/status field by field. Supply exactly one of before (current recording call ID) or reference (this client's pin token), and after. Paginated changes; partial/UNKNOWN/redaction cannot establish full object equality.",
            identity + number("before", "Reference call in the current recording", Long.MAX_VALUE) +
                text("reference", "Token from repl_recording_pin in this MCP session", max=36) +
                number("after", "Call in the current recording", Long.MAX_VALUE, true) + page),
        McpTool("repl_recording_select", "recording/select", "Select a call in the IDE graph and open its captured source with input/result. Changes the user's selection; does not evaluate Java or restore a stack frame. Requires execution/state changes and recording sharing.",
            listOf(recording, call), execution=true),
        McpTool("repl_recording_start", "recording/start", "Start class recording in the shared IDE session. Requires recording sharing, execution/state changes and capture/trace changes. Supply expected recording ID (or none) from status to prevent replacing an unseen recording. Records future real application calls; does not invoke methods. Wait for the response; never retry automatically.",
            listOf(text("expected", "Current recording ID or none when no recording exists", true, 36),
                text("classes", "1–8 distinct exact application class names, separated by newlines", true, 4104)), execution=true),
        McpTool("repl_recording_stop", "recording/stop", "Stop adding calls to this exact shared IDE recording. In-flight calls may finish later; use status and view revisions. Keeps downloaded values. Requires execution/state changes and capture/trace changes.",
            listOf(recording), execution=true)
    )
}
