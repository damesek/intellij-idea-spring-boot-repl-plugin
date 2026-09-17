package hu.baader.repl.mcp

import com.google.gson.JsonObject

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
