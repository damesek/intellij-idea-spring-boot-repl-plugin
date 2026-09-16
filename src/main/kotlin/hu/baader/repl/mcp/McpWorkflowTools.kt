package hu.baader.repl.mcp

internal object McpWorkflowTools {
    private fun text(name: String, description: String, required: Boolean = false, max: Int = 256) = McpParameter(name,description,required,max)
    private fun number(name: String, description: String, max: Long) = McpParameter(name,description,maxInteger=max)
    private val name=text("name","Existing DATA / CASE name",true)
    private val bean=text("bean","Exact Spring bean name",true,512)
    private val method=listOf(bean,text("method","Public method name",true),text("descriptor","Exact JVM descriptor from bean info",true,4096))
    private val edit=listOf(name,text("version","Source SHA-256 from edit-read; stale edits rejected",true,64),text("json","Edited JSON payload; small inline document only",true,100000),text("type","Declared Java type; default original",max=4096))
    val all=listOf(
        McpTool("repl_bean_search","beans/list","Search bean definitions by name/type without initializing lazy beans. Returns up to 100 rows.",listOf(text("query","Name/type search"),number("offset","Zero-based row offset",100000))),
        McpTool("repl_bean_info","beans/info","Bean scope, qualifiers, primary flag, proxy/target type, resolved dependencies and public method signatures. Reads definitions/existing singletons only.",listOf(bean)),
        McpTool("repl_bean_compatible_data","beans/compatible-data","Suggest DATA by declared argument type. Suggestions do not prove successful deserialization.",method+number("parameter","Zero-based parameter index",31)),
        McpTool("repl_bean_prepare","beans/prepare","Prepare Java calling an exact bean method with existing DATA; does not deserialize or execute. Review and analyze the returned code before explicit eval.",method+text("inputs-json","JSON array of one DATA name per parameter",true,8192)),
        McpTool("repl_snapshot_edit_read","snapshot/edit-read","Read a detached DATA payload, declared type and optimistic edit version. Runtime limit 2 MiB; MCP may return a truncated preview. Never save truncated or redacted JSON as an authoritative copy.",listOf(name)),
        McpTool("repl_snapshot_edit_validate","snapshot/edit-validate","Validate edited JSON against its declared type. Executes deserializers/constructors; does not save or mutate the source DATA.",edit,execution=true),
        McpTool("repl_snapshot_edit_copy","snapshot/edit-copy","Validate and save edited DATA to an unused different name. Original DATA and live objects remain unchanged. Deserializers may have application effects.",edit+text("target","Unused name for new DATA",true),execution=true),
        McpTool("repl_case_variants","case/variants","Clone a CASE with 1–20 different DATA inputs. Every row initially uses the source expectation; review expectations before running. Does not execute Java.",listOf(name,text("target","Unused CASE name",true),text("inputs","Distinct DATA names separated by newlines",true,4096)),execution=true),
        McpTool("repl_case_affected","case/affected","Suggest CASEs whose observed classes intersect this Java source. Returns unknown-coverage CASEs separately. Observation is incomplete; this does not prove unaffected behavior or reload/run anything.",listOf(text("code","Complete modified Java source",true,100000))),
        McpTool("repl_watch_add","watch/add","Pin an expression in this MCP session. Field/index/key paths by default; allow-java=true explicitly permits application method execution on future eval/refresh. Adding does not sample.",listOf(text("expression","Variable/field/index path or explicit Java expression",true,2048),text("allow-java","true enables Java expressions; default false",max=5)),execution=true),
        McpTool("repl_watch_list","watch/list","Pinned watch identities and state in this MCP session. No sampling or execution."),
        McpTool("repl_watch_get","watch/get","Last sampled before/after values and changed paths. PARTIAL cannot prove equality. No evaluation.",listOf(text("watch-id","Watch identity in this session",true,36))),
        McpTool("repl_watch_remove","watch/remove","Remove a watch from this MCP session.",listOf(text("watch-id","Watch identity",true,36)),execution=true),
        McpTool("repl_watch_refresh","watch/refresh","Explicitly sample pinned watches under the MCP execution policy. Java watches can call methods; no periodic application-code evaluation occurs.",execution=true),
        McpTool("repl_recording_case_info","recording/case-info","Check whether full replay DATA was captured for a call in the live IDE recording. Display previews alone are insufficient.",listOf(text("recording","Live IDE recording ID",true,36),number("call","Call identity",1000))),
        McpTool("repl_recording_case_create","recording/case-create","Create new input/expected DATA and a CASE from full entry/exit capture. Requires sharing IDE recordings, execution and snapshot writes. Does not rerun the method; review generated codec, proxy behavior and assertions before running.",listOf(text("recording","Live IDE recording ID",true,36),number("call","Call identity",1000),text("name","Unused reproduction name",true),text("bean","Selected bean; omit only for a unique candidate or static method",max=512)),execution=true)
    )
}
