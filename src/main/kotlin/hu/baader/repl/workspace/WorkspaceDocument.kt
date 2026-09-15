package hu.baader.repl.workspace

import com.google.gson.Gson
import com.google.gson.JsonParser
import hu.baader.repl.editor.NotebookState
import hu.baader.repl.ui.HttpRequestCase

/** Portable data. Importing this document must never evaluate imports, Java, HTTP or snapshot constructors. */
data class WorkspaceDocument(
    var formatVersion: Int = 1,
    var notebook: NotebookState = NotebookState(),
    var imports: MutableList<String> = mutableListOf(),
    var bindings: MutableList<Binding> = mutableListOf(),
    var bookmarks: MutableList<Bookmark> = mutableListOf(),
    var httpRequests: MutableList<HttpRequestCase> = mutableListOf(),
    var unrestorableVariables: MutableList<String> = mutableListOf(),
    var environment: String = "", var transcript: String = ""
) {
    data class Binding(var variable: String = "", var snapshot: String = "", var version: String = "", var type: String = "", var restorable: Boolean = true)
    data class Bookmark(var label: String = "", var variable: String = "", var path: String = "")
    fun encode(): String = gson.toJson(this).also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Workspace metadata exceeds 8 MiB; omit transcript or shorten cell output" } }
    fun validate() {
        require(formatVersion == 1) { "Unsupported workspace version" }
        require(notebook.cells.size <= 200 && notebook.executionOrder.size <= 1000 && notebook.counter in 0..Long.MAX_VALUE - 1000) { "Invalid notebook size" }
        require(notebook.cells.map { it.id }.toSet().size == notebook.cells.size) { "Duplicate cell identities" }
        notebook.cells.forEach {
            require(it.id.length in 1..128 && it.source.length <= 1_000_000 && it.output.length <= 16384 && it.declarations.size <= 200)
            require(it.sequence in 0..notebook.counter && it.durationMs >= 0 && it.status in setOf("NEVER", "RUNNING", "SUCCESS", "ERROR", "INTERRUPTED"))
            require(it.executedHash.isEmpty() || it.executedHash.matches(Regex("[a-f0-9]{64}")))
            require(it.analyzedHash.isEmpty() || it.analyzedHash.matches(Regex("[a-f0-9]{64}")))
        }
        require(imports.size <= 500 && imports.all { it.length <= 2048 })
        require(bindings.size <= 200 && bindings.map { it.variable }.toSet().size == bindings.size)
        bindings.forEach { require(it.variable.matches(Regex("[A-Za-z_$][\\w$]*")) && it.snapshot.length in 1..128 && it.type.length <= 1024 && (it.version.isEmpty() || it.version.matches(Regex("[a-f0-9]{64}")))) }
        require(bookmarks.size <= 100 && bookmarks.all { it.label.length <= 256 && it.variable.length <= 128 && it.path.length <= 16384 })
        require(httpRequests.size <= 200 && httpRequests.map { it.id }.toSet().size == httpRequests.size)
        httpRequests.forEach { require(it.id.length in 1..128 && it.url.length <= 16384 && it.body.length <= 1_048_576 && it.headers.size <= 200 && it.headers.all { h -> h.name.length <= 1024 && h.value.length <= 16384 }) }
        require(environment.length <= 65536 && transcript.length <= 300000)
        require(unrestorableVariables.size <= 1000 && unrestorableVariables.all { it.length <= 2048 })
        notebook.sync(notebook.text)
    }
    companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
        private val gson = Gson()
        fun decode(json: String): WorkspaceDocument {
            require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Workspace metadata exceeds 8 MiB" }
            // Gson does not enforce Kotlin non-null fields; validation rejects malformed imported models before use.
            try {
                val tree = JsonParser.parseString(json)
                require(tree.isJsonObject && tree.asJsonObject["formatVersion"]?.asInt == 1)
                return gson.fromJson(tree, WorkspaceDocument::class.java).also { it.validate() }
            } catch (e: Exception) { throw IllegalArgumentException("Invalid workspace metadata: ${e.message}", e) }
        }
    }
}
