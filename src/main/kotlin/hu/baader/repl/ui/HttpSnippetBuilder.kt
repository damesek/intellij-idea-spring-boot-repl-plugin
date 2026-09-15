package hu.baader.repl.ui

/** Generates source only. Environment placeholders are resolved in the target JVM when Run is pressed. */
object HttpSnippetBuilder {
    fun definitions(cases: List<HttpRequestCase>): String {
        require(cases.map { it.id }.distinct().size == cases.size) { "HTTP case IDs must be unique" }
        return buildString {
            append("""
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
class HttpReq {
  private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();
  private static String env(String value) {
    var matcher = java.util.regex.Pattern.compile("\\§\\{([A-Za-z_][A-Za-z0-9_]*)}").matcher(value);
    return matcher.replaceAll(match -> {
      String resolved = System.getenv(match.group(1));
      if (resolved == null) throw new IllegalArgumentException("Missing environment variable: " + match.group(1));
      return java.util.regex.Matcher.quoteReplacement(resolved);
    });
  }
  public static HttpResponse<String> perform(String caseId) throws Exception {
    switch (caseId) {
""".trimIndent().replace('§', '$'))
            append('\n')
            for (case in cases) {
                val method = case.method.ifBlank { "GET" }.uppercase()
                require(method.matches(Regex("[A-Z]+"))) { "Invalid HTTP method" }
                append("case " + literal(case.id) + ": {\n")
                append("var uri = URI.create(env(" + literal(case.url) + "));\n")
                append("if ((!\"http\".equals(uri.getScheme()) && !\"https\".equals(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException(\"Use an HTTP(S) URL without embedded credentials\");\n")
                append("var req = HttpRequest.newBuilder(uri).timeout(java.time.Duration.ofSeconds(30));\n")
                case.headers.filter { it.name.isNotBlank() }.forEach {
                    append("req.header(" + literal(it.name.trim()) + ", env(" + literal(it.value) + "));\n")
                }
                append("String body = env(" + literal(case.body) + ");\n")
                append("if (body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1048576) throw new IllegalArgumentException(\"Request body exceeds 1 MiB\");\n")
                append("return CLIENT.send(req.method(" + literal(method) + ", body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), hu.baader.repl.protocol.BoundedBodyHandler.handler(1048576));\n}\n")
            }
            append("default: throw new IllegalArgumentException(\"Unknown HTTP case: \" + caseId);\n}\n}\n}\n")
        }
    }
    fun build(cases: List<HttpRequestCase>, selectedId: String): String = definitions(cases) +
        "var httpResp = HttpReq.perform(" + literal(selectedId) + ");\nSystem.out.println(\"Status: \" + httpResp.statusCode());\nhttpResp.body()\n"

    private fun literal(value: String): String = buildString {
        append('"')
        for (c in value) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 32) append("\\" + c.code.toString(8).padStart(3, '0')) else append(c)
        }
        append('"')
    }
}
