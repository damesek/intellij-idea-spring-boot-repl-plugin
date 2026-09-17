package hu.baader.repl.ui

data class HttpHeaderEntry(
    var name: String = "",
    var value: String = ""
)

data class HttpRequestCase(
    var id: String = "",
    var name: String = "",
    var method: String = "GET",
    var url: String = "",
    var body: String = "",
    var topic: String = "",
    var version: String = "",
    var description: String = "",
    var headers: MutableList<HttpHeaderEntry> = mutableListOf()
) {
    fun displayLabel(): String = name.ifBlank { id.ifBlank { "HTTP request" } }
    fun deepCopy(): HttpRequestCase = copy(headers = headers.map { it.copy() }.toMutableList())
}
