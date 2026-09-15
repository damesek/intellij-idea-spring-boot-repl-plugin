package hu.baader.repl.editor

/** Only bounded references to live results. Never written to workspace/checkpoint files. */
class CellResults {
    data class Result(val sequence: Long, val handle: String?)
    private val values = mutableMapOf<String, Result>()
    fun record(id: String, sequence: Long, response: Map<String, String>) {
        values[id] = Result(sequence, response["handle"]?.takeIf { it.isNotBlank() && it.length <= 256 })
    }
    fun handle(cell: NotebookState.Cell): String? = values[cell.id]?.takeIf { it.sequence == cell.sequence && cell.status == "SUCCESS" }?.handle
    fun remove(id: String) { values.remove(id) }
    fun clear() { values.clear() }
    fun retain(ids: Set<String>) { values.keys.retainAll(ids) }
}
