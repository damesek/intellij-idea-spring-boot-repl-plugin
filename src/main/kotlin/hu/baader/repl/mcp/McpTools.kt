package hu.baader.repl.mcp

import com.google.gson.JsonObject
import hu.baader.repl.ui.ValueDisplay
import hu.baader.repl.protocol.ValueTree

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

internal data class McpParameter(
    val name: String, val description: String, val required: Boolean = false,
    val maxLength: Int = 256, val maxInteger: Long? = null
) {
    fun schema() = if (maxInteger == null) McpJson.objectOf("type" to "string", "description" to description,
        "minLength" to if (required) 1 else 0, "maxLength" to maxLength)
    else McpJson.objectOf("type" to "integer", "description" to description, "minimum" to 0, "maximum" to maxInteger)
}

internal data class McpTool(
    val name: String, val operation: String, val description: String,
    val parameters: List<McpParameter> = emptyList(), val execution: Boolean = false,
    val reload: Boolean = false, val paged: Boolean = false, val stateChange: Boolean = false
) {
    fun enabled(permissions: McpPermissions): Boolean {
        if (permissions.allowedTools != null && name !in permissions.allowedTools) return false
        if (operation.startsWith("recording/") && !permissions.recordingAccess) return false
        if (operation in setOf("recording/start", "recording/stop") && !permissions.captureChanges) return false
        if (execution && !permissions.execution || reload && !permissions.reload) return false
        if (operation == "snapshot/delete" && !permissions.snapshotDelete) return false
        if (operation in setOf("snapshot/edit-copy", "case/variants", "recording/case-create", "snapshot/save", "snapshot/import", "case/save", "reproduction/create", "snapshot/restore-version", "workspace/export-file", "workspace/import-file") && !permissions.snapshotWrites) return false
        if (operation in setOf("case/run", "case/run-batch") && !permissions.caseRuns) return false
        if ((operation.startsWith("capture/") || operation.startsWith("trace/")) && execution && !permissions.captureChanges) return false
        return true
    }
    val taskSupport get() = operation in setOf("eval", "case/run", "case/run-batch", "class-reload", "watch/refresh")
    fun descriptor() = McpJson.objectOf("name" to name, "description" to description,
        "inputSchema" to McpJson.objectOf("type" to "object", "properties" to parameters.associate { it.name to it.schema() },
            "required" to parameters.filter { it.required }.map { it.name }, "additionalProperties" to false),
        "annotations" to McpJson.objectOf("readOnlyHint" to (!execution && !stateChange), "destructiveHint" to execution,
            "idempotentHint" to false, "openWorldHint" to true))

    fun arguments(input: JsonObject): Map<String, String> {
        if (input.keySet().any { key -> parameters.none { it.name == key } }) throw McpError(-32602, "Unknown argument for $name")
        return buildMap {
            for (p in parameters) {
                val value = input[p.name]
                if (value == null) { if (p.required) throw McpError(-32602, "Missing argument: ${p.name}"); continue }
                if (!value.isJsonPrimitive) throw McpError(-32602, "Invalid argument: ${p.name}")
                val primitive = value.asJsonPrimitive
                val valid = if (p.maxInteger == null) primitive.isString && primitive.asString.length <= p.maxLength &&
                    (!p.required || primitive.asString.isNotBlank())
                else primitive.isNumber && runCatching { primitive.asBigDecimal.longValueExact() in 0..p.maxInteger }.getOrDefault(false)
                if (!valid) throw McpError(-32602, "Invalid type or range: ${p.name}")
                // JSON Schema integers can also be written as 1.0 or 1e0; nREPL expects decimal integer strings.
                put(p.name, if (p.maxInteger == null) primitive.asString else primitive.asBigDecimal.longValueExact().toString())
            }
            for(flag in listOf("async","capture-data","allow-java"))if(get(flag)?.let { it !in setOf("true","false") }==true)throw McpError(-32602,"$flag must be true or false")
            if(operation in setOf("recording/case-info","recording/case-create") && get("call").isNullOrEmpty())throw McpError(-32602,"call is required")
            if (operation == "complete" && (get("cursor")?.toInt() ?: 0) > getValue("code").length)
                throw McpError(-32602, "Cursor must be within code (UTF-16 offset)")
            if (operation in setOf("inspector/start", "snapshot/save", "snapshot/pin") &&
                listOf("handle", "var", "event").count { !get(it).isNullOrBlank() } != 1)
                throw McpError(-32602, "Supply exactly one of handle, var or event")
            if (get("limit") == "0") throw McpError(-32602, "Limit must be at least 1")
            if (operation.startsWith("recording/")) {
                if (get("text-limit") == "0") throw McpError(-32602, "Text limit must be at least 1")
                if(get("sql")?.let { it !in setOf("true","false") } == true) throw McpError(-32602,"sql must be true or false")
                if(get("hibernate")?.let { it !in setOf("true","false") } == true) throw McpError(-32602,"hibernate must be true or false")
                if(get("hibernate")=="true" && get("sql")=="false") throw McpError(-32602,"Hibernate recording requires SQL recording")
                if(operation=="recording/hibernate" && get("kind")?.let { it !in hu.baader.repl.protocol.HibernateObservation.KINDS } == true) throw McpError(-32602,"Unknown Hibernate event kind")
                if(get("n-plus-one-threshold")?.toInt()?.let { it < 2 } == true) throw McpError(-32602,"N+1 threshold must be 2–1000")
                if (get("errors-only")?.let { it !in setOf("true", "false") } == true) throw McpError(-32602, "errors-only must be true or false")
                if (containsKey("from-ms") != containsKey("to-ms") ||
                    containsKey("from-ms") && getValue("from-ms").toLong() > getValue("to-ms").toLong())
                    throw McpError(-32602, "Supply an ordered from-ms / to-ms pair")
                if (operation in setOf("recording/compare", "recording/sql-compare", "recording/hibernate-compare") && listOf("before", "reference").count { !get(it).isNullOrBlank() } != 1)
                    throw McpError(-32602, "Supply exactly one of before or reference")
                if (operation == "recording/values" && get("part") !in setOf("input", "result", "exception"))
                    throw McpError(-32602, "part must be input, result or exception")
            }
        }
    }
}

internal object McpTools {
    private fun text(name: String, description: String, required: Boolean = false, max: Int = 256) = McpParameter(name, description, required, max)
    private fun number(name: String, description: String, max: Long, required: Boolean = false) = McpParameter(name, description, required, maxInteger = max)
    private val code = text("code", "Java source, evaluated only by execution tools", true, 100_000)
    private val name = text("name", "Snapshot or case name", true)
    private val variable = text("var", "Name of a variable in this MCP session")
    private val type = text("type", "Optional Java declared type, including generic arguments", max = 4096)
    private val selection = listOf(text("handle", "Result handle returned by this session"), variable, number("event", "Tap/trace event ID in this session", Long.MAX_VALUE))
    private val page = listOf(number("offset", "Zero-based row offset; default 0", 1_000_000), number("limit", "Rows per page; default 50, max 100", 100))
    val all = listOf(
        McpTool("repl_status", "describe", "Application PID, Spring context readiness/epoch and runtime capabilities. Each MCP session has its own Java variables."),
        McpTool("repl_list_beans", "list-beans", "Spring bean names and types, without instantiating beans. Paginated rows: name, type.", page, paged = true),
        McpTool("repl_analyze", "analyze", "Check Java syntax/types in this session without executing the code. Use before repl_eval.", listOf(code)),
        McpTool("repl_complete", "complete", "Java completion candidates at a UTF-16 cursor offset.", listOf(code, number("cursor", "UTF-16 cursor offset", 100_000, true))),
        McpTool("repl_variables", "vars/list", "Variables in this MCP session, with bounded previews. Paginated TSV rows.", page, paged = true),
        McpTool("repl_imports", "imports/get", "Imports in this MCP session."),
        McpTool("repl_snapshot_list", "snapshot/list", "List available DATA/RECIPE snapshots and this session's LIVE pins. Paginated TSV rows.", page, paged = true),
        McpTool("repl_snapshot_info", "snapshot/info", "Metadata for a named snapshot.", listOf(name)),
        McpTool("repl_snapshot_diff", "snapshot/diff", "Compare two DATA snapshots with bounded path/value differences.", listOf(text("before", "Before snapshot", true), text("after", "After snapshot", true)) + page),
        McpTool("repl_snapshot_export", "snapshot/export", "Small DATA snapshot JSON preview. Truncation is explicit; use the IDE's file export for large snapshots.", listOf(name)),
        McpTool("repl_capture_status", "capture/status", "Status of a capture rule owned by this session.", listOf(text("rule-id", "Rule ID; empty selects this session's latest rule"))),
        McpTool("repl_capture_list", "capture/list", "List capture rules owned by this session; TSV: id, phase, point, name, saved, count, sample interval, last saved name."),
        McpTool("repl_execution_policy", "execution/policy", "Runtime policy and transaction managers. MCP execution uses the stricter settings selected by the user in the MCP tab."),
        McpTool("repl_execution_preflight", "execution/preflight", "Non-executing side-effect hints. Heuristics are incomplete and are not a sandbox or an outbound firewall.", listOf(code)),
        McpTool("repl_audit_events", "audit/events", "Recent redacted runtime audit entries for this session. Local audit files rotate and are not tamper-proof."),
        McpTool("repl_events", "events/list", "Bounded tap/trace events subscribed by this session."),
        McpTool("repl_case_list", "case/list", "Saved CASE names, tags, disabled flag and row count; no Java execution.", page, paged = true),
        McpTool("repl_case_result", "case/result", "Last result of a CASE in this MCP session; optional zero-based row selects a compact row report. No rerun.", listOf(name, number("row", "Zero-based parameter result row", 19))),
        McpTool("repl_case_export_junit", "case/export-junit", "Generate JUnit 5/AssertJ sources without executing code or writing files. Returns file manifest; request one exact file to read. Requires saved input type and result-expression. DATA can contain secrets; inline/MCP limits apply, use IDE ZIP export for full output.", listOf(name, text("package", "Java package; default reproduction"), text("class", "Java test class; default ReproductionTest"), text("file", "Exact path from the manifest", max=1024))),
        McpTool("repl_case_run_batch", "case/run-batch", "Run 1–20 saved CASEs (up to 100 parameter rows) sequentially within one MCP deadline. Each row gets a fresh session and transaction. Stops on error/cancellation; use repl_case_result for details.", listOf(text("names", "Distinct CASE names separated by newlines", true, 4096)), execution = true),
        McpTool("repl_case_load", "case/load", "Read a saved case's definition without executing it.", listOf(name)),
        McpTool("repl_reproduction_create", "reproduction/create", "Create DATA copies, an executable CASE, RECIPE and environment metadata from the last execution in this MCP session. Does not rerun code. Export the returned bundle-id through the IDE.", listOf(name, text("input", "Existing captured input DATA snapshot", true), text("variable", "Input variable used by the last executed code; default input"), type, text("metadata-json", "Optional explicit tenant, flags or HTTP metadata as a JSON object; avoid secrets", max=65536)), execution=true),
        McpTool("repl_eval", "eval", "Run Java using the MCP tab's fixed execution mode and deadline. ROLLBACK/READ_ONLY require a transaction manager and roll back participating synchronous DB work; network, messages, files and object mutations are not undone. Results include handles. Never automatically retry after failure.", listOf(code), execution = true),
        McpTool("repl_interrupt", "interrupt", "Request cooperative interruption. The active rollback boundary rolls back when execution returns; general application effects are not undone.", execution = true),
        McpTool("repl_reset", "session/reset", "Discard this session's variables, handles, LIVE pins and subscriptions; bind to the current Spring context. DATA snapshots and app state remain.", execution = true),
        McpTool("repl_bind_spring", "bind-spring", "Bind ctx after Spring finishes startup, keeping this session's variables. No custom expression is accepted. After a context restart use repl_reset instead.", execution = true),
        McpTool("repl_add_imports", "imports/add", "Add newline-separated imports to this session.", listOf(text("imports", "Java imports separated by newlines", true, 16_384)), execution = true),
        McpTool("repl_inspect", "inspector/start", "Open an object by handle, variable or event from this session. Returns a bounded tree and field rows. Explicit inspection may invoke collection accessors.", selection, execution = true),
        McpTool("repl_inspect_page", "inspector/page", "Read another field page of the currently inspected object.", listOf(page.first()), execution = true),
        McpTool("repl_inspect_push", "inspector/push", "Navigate into a field using the revision and row index from an inspector response.", listOf(text("revision", "Current inspector revision", true), number("index", "Field row index", 1_000_000, true)), execution = true),
        McpTool("repl_inspect_back", "inspector/back", "Return to the previous inspected object.", execution = true),
        McpTool("repl_snapshot_save", "snapshot/save", "Serialize selected value as a persistent DATA snapshot. Serialization can call getters. Limit: 200 MiB in the runtime; MCP returns metadata only.", listOf(name, type) + selection, execution = true),
        McpTool("repl_snapshot_pin", "snapshot/pin", "Retain a LIVE reference in this MCP session; it expires when the session closes.", listOf(name) + selection, execution = true),
        McpTool("repl_snapshot_load", "snapshot/load", "Materialize a DATA snapshot or LIVE pin into a variable (default loadedSnapshot). Constructors/deserialization can run application code.", listOf(name, variable, type, text("version", "Optional immutable snapshot SHA-256", max=64)), execution = true),
        McpTool("repl_snapshot_versions", "snapshot/versions", "List immutable snapshot revisions and capture provenance. Maximum 100 versions; response contains versions-json.", listOf(name, number("offset", "Version offset", 100), number("limit", "Page size, default 20", 100))),
        McpTool("repl_snapshot_provenance", "snapshot/provenance", "Read capture environment and verify a historical revision SHA-256. Empty version selects current.", listOf(name, text("version", "SHA-256 revision", max=64))),
        McpTool("repl_snapshot_restore_version", "snapshot/restore-version", "Make a historical revision current as a new immutable version. Does not execute Java. Requires snapshot writes.", listOf(name, text("version", "SHA-256 revision", true, 64)), execution=true),
        McpTool("repl_notebook_symbols", "notebook/symbols", "Analyze candidate declarations without executing Java. Conservative is true for unknown state dependencies.", listOf(code)),
        McpTool("repl_workspace_export", "workspace/export-file", "Write a local workspace ZIP with runtime snapshots and an explicit IDE workspace metadata JSON file. Both paths are on the application machine. Requires execution and snapshot writes; use IDE Save workspace to collect editor state.", listOf(text("path", "Target .sbrepl-workspace file", true, 4096), text("state-path", "Existing workspace metadata JSON, formatVersion 1 (up to 8 MiB)", true, 4096)), execution=true),
        McpTool("repl_workspace_import", "workspace/import-file", "Validate a local workspace ZIP and import snapshots under new names; returns mappings. Does not execute Java or modify IDE editor state. Requires snapshot writes.", listOf(text("path", "Workspace ZIP on application machine", true, 4096), text("prefix", "New snapshot name prefix, at most 32 characters", true, 32)), execution=true),
        McpTool("repl_snapshot_delete", "snapshot/delete", "Delete a named snapshot. Persistent snapshots are shared by the application.", listOf(name), execution = true),
        McpTool("repl_snapshot_import", "snapshot/import", "Import small JSON as DATA without evaluating Java. For large files use the IDE.", listOf(name, text("json", "JSON document", true, 100_000)), execution = true),
        McpTool("repl_capture_arm", "capture/arm", "Arm an independent capture rule. Up to 16 rules per JVM; serialization is synchronous. Multiple saves use a sequence suffix or ${'$'}{sequence} in the name.", listOf(text("point", "Trigger point name", true), name, type, text("case", "Exact optional case ID"), number("ttl-ms", "Expiry in milliseconds; default 300000, minimum 1", 1_800_000), number("count", "Number of captures; default 1, minimum 1", 100), number("sample-every", "Capture every Nth matching call; default 1, minimum 1", 10000)), execution = true),
        McpTool("repl_capture_disarm", "capture/disarm", "Disarm a capture rule owned by this session.", listOf(text("rule-id", "Rule ID; empty selects this session's latest rule")), execution = true),
        McpTool("repl_events_start", "events/start", "Subscribe this session to application tap events with an optional label filter.", listOf(text("label", "Optional label filter")), execution = true),
        McpTool("repl_events_stop", "events/stop", "Stop this session's event subscription.", execution = true),
        McpTool("repl_case_save", "case/save", "Save a case definition; no Java is evaluated until repl_case_run.", listOf(name, text("input", "Input DATA snapshot", true), text("expected", "Expected DATA snapshot", true), type, text("variable", "Input variable name; default input"), text("code", "Java statements or legacy JShell code; optional with result-expression", max=100000), text("expected-exception", "Optional fully qualified exception class", max=256), text("expected-message", "Exact expected exception message", max=65536),
            text("assertions-json", "JSON assertion options: include, ignore, unordered, numericTolerance, timeToleranceMs, checks", max=65536),
            text("parameters-json", "JSON array of 1–20 objects containing id, input, expected", max=65536),
            text("result-expression", "Optional final Java expression; required for JUnit export", max=100000),
            text("imports", "Java import statements", max=16384), text("setup", "Java setup code", max=100000), text("teardown", "Java cleanup code", max=100000),
            text("observed-classes", "Newline-separated classes observed in a recording; incomplete coverage", max=32768), text("tags", "Comma-separated tags", max=1024), text("disabled", "true or false"), number("max-duration-ms", "Optional code duration assertion, minimum 1", 120000), number("max-sql-count", "Maximum synchronous JDBC executions, including failed SQL; 0 allowed", 1000000), number("max-sql-repetitions", "Maximum repetitions per root, datasource and SQL template", 1000000), number("max-hibernate-loads", "Maximum Hibernate entity loads; requires 6.6 adapter", 1000000), number("max-hibernate-flushes", "Maximum actual Hibernate flushes", 1000000), number("max-hibernate-lazy-loads", "Maximum lazy entity/collection/attribute initializations", 1000000), number("max-hibernate-response-lazy-loads", "Maximum lazy initializations during MVC response handling", 1000000)), execution = true),
        McpTool("repl_case_run", "case/run", "Run a saved snapshot case in a temporary evaluator. Spring beans and application effects are shared; this is not a sandbox.", listOf(name), execution = true),
        McpTool("repl_reload", "class-reload", "Compile complete Java source and HotSwap supported method-body changes in the running JVM. Requires Allow HotSwap. No automatic retry; structural changes may require restart.", listOf(code), execution = true, reload = true)
    ) + McpRecordingTools.all + McpWorkflowTools.all

    fun result(tool: McpTool, arguments: Map<String, String>, response: Map<String, String>): JsonObject {
        if (tool.operation.startsWith("recording/") && response.containsKey("recording-json"))
            return envelope(McpJson.parse(response.getValue("recording-json")).asJsonObject, response.containsKey("err"))
        val data = JsonObject()
        val truncated = mutableListOf<String>()
        var remaining = 98_304
        val failure = response.containsKey("err") || response["status"].orEmpty().lines().contains("error")
        for ((key, value) in response) {
            if (key in setOf("id", "session", "new-session", "token", "view-data", "op")) continue
            if (key == "value" && tool.paged && !failure) {
                val lines = value.lineSequence().filter { it.isNotEmpty() }.toList()
                val offset = arguments["offset"]?.toInt() ?: 0
                val limit = arguments["limit"]?.toInt() ?: 50
                data.addProperty("totalRows", lines.size)
                val end = minOf(lines.size.toLong(), offset.toLong() + limit).toInt()
                if (end < lines.size) data.addProperty("nextOffset", end)
                val rows = lines.drop(offset).take(limit)
                data.add("rows", McpJson.gson.toJsonTree(rows.map { it.split('\t') }))
                continue
            }
            val length = minOf(value.length, remaining, 65_536)
            data.addProperty(key, value.take(length))
            remaining -= length
            if (length < value.length) truncated += key
        }
        if (response.containsKey("view-data")) {
            val display = ValueDisplay.fromMessage(response, response["value"].orEmpty())
            fun tree(node: ValueTree): JsonObject = McpJson.objectOf("label" to node.label(), "kind" to node.kind(),
                "type" to node.type(), "text" to node.text(), "children" to node.children().map(::tree))
            data.add("preview", tree(display.root))
            data.addProperty("jsonPreview", display.json)
        }
        if (truncated.isNotEmpty()) {
            data.add("truncatedFields", McpJson.gson.toJsonTree(truncated))
            data.addProperty("note", "Partial result. Use paged inspection or the IDE's snapshot file export for large data.")
        }
        var result = envelope(data, failure)
        // Leave room for the JSON-RPC envelope and a maximally escaped 256-character request id.
        if (McpJson.gson.toJson(result).toByteArray(Charsets.UTF_8).size > McpJson.MAX_RESPONSE_BYTES - 4096) {
            val small = McpJson.objectOf("truncated" to true, "note" to "Result exceeds MCP preview limit. Use paged inspection or IDE file export.")
            listOf("handle", "type", "status", "err", "value").forEach { key -> response[key]?.let { small.addProperty(key, it.take(4096)) } }
            result = envelope(small, failure)
        }
        return result
    }

    fun error(message: String) = envelope(McpJson.objectOf("error" to message.take(4096)), true)
    fun sanitizeResult(result: JsonObject, permissions: McpPermissions, redactedBeforePaging: Boolean = false): JsonObject {
        var redacted = false
        fun scrub(value: com.google.gson.JsonElement): com.google.gson.JsonElement = when {
            value.isJsonObject -> JsonObject().apply { value.asJsonObject.entrySet().forEach { (key, child) ->
                val label = value.asJsonObject["label"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
                if (hu.baader.repl.protocol.SensitiveValues.sensitiveName(key) ||
                    (key in setOf("text", "children") && hu.baader.repl.protocol.SensitiveValues.sensitiveName(label))) { addProperty(key, "[REDACTED]"); redacted = true }
                else add(key, scrub(child))
            } }
            value.isJsonArray -> com.google.gson.JsonArray().apply { value.asJsonArray.forEach { add(scrub(it)) } }
            value.isJsonPrimitive && value.asJsonPrimitive.isString -> {
                val raw = value.asString
                val safe = hu.baader.repl.protocol.SensitiveValues.redact(raw)
                if (safe != raw) redacted = true
                com.google.gson.JsonPrimitive(safe)
            }
            else -> value.deepCopy()
        }
        // Rebuild the text envelope from structured data, rather than redacting JSON syntax as text.
        val data = result.getAsJsonObject("structuredContent")
        val safe = if (permissions.redactResults && !redactedBeforePaging) scrub(data).asJsonObject else data.deepCopy()
        if (redacted) safe.addProperty("redacted", true)
        var output = envelope(safe, result["isError"]?.asBoolean ?: false)
        if (McpJson.gson.toJson(output).length > permissions.maxResultChars) {
            val small = McpJson.objectOf("truncated" to true, "note" to "Operation ran; result exceeds the configured MCP limit. Use paged inspection. Do not rerun execution to retrieve this result.")
            small.add("truncatedFields", safe["truncatedFields"] ?: McpJson.gson.toJsonTree(safe.keySet().filter { it !in setOf("handle", "redacted") }))
            if (redacted) small.addProperty("redacted", true)
            safe["handle"]?.let { small.add("handle", it) }
            // Keep recording identity so a caller can reduce a page without losing its view guard.
            listOf("recording", "view", "call", "part", "path", "offset").forEach { key ->
                safe[key]?.takeIf { it.isJsonPrimitive && it.toString().length <= 256 }?.let { small.add(key, it) }
            }
            if (safe.has("recording")) {
                small.remove("truncatedFields")
                small.addProperty("note", "Result too large; reduce limit/text-limit. Do not repeat execution.")
            }
            output = envelope(small, result["isError"]?.asBoolean ?: false)
            for (key in listOf("truncatedFields", "path", "view", "part")) {
                if (McpJson.gson.toJson(output).length <= permissions.maxResultChars) break
                small.remove(key); output = envelope(small, result["isError"]?.asBoolean ?: false)
            }
        }
        return output
    }
    private fun envelope(data: JsonObject, error: Boolean) = McpJson.objectOf("content" to listOf(
        McpJson.objectOf("type" to "text", "text" to McpJson.gson.toJson(data))), "structuredContent" to data, "isError" to error)
}
