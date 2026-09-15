package hu.baader.repl.ai

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import hu.baader.repl.settings.PluginSettingsState
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

class OpenAiClient(
    private val settings: PluginSettingsState.State
) {
    private val gson = Gson()
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private fun resolveApiKey(): String {
        val explicit = hu.baader.repl.settings.SecretStore.aiKey().trim()
        if (explicit.isNotBlank()) return explicit
        val env = System.getenv("OPENAI_API_KEY") ?: ""
        return env.trim()
    }

    fun chat(prompt: String): String {
        val apiKey = resolveApiKey()
        require(apiKey.isNotBlank()) { "OpenAI API key hiányzik (Settings vagy OPENAI_API_KEY env)." }

        val model = settings.openAiModel.ifBlank { "gpt-4o" }
        val base = settings.openAiBaseUrl.ifBlank { "https://api.openai.com/v1" }
        val url = "${base.trimEnd('/')}/chat/completions"

        val payload = JsonObject().apply {
            addProperty("model", model)
            add("messages", gson.toJsonTree(listOf(
                mapOf("role" to "user", "content" to prompt)
            )))
            addProperty("temperature", 0.0)
            addProperty("max_tokens", 512)
        }

        val endpoint = URI.create(url)
        require(endpoint.scheme == "https" || (endpoint.scheme == "http" && endpoint.host in listOf("localhost", "127.0.0.1", "::1", "[::1]"))) { "Use HTTPS for an external AI endpoint" }
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
            .build()

        val resp = http.send(req, hu.baader.repl.ui.BoundedHttpBody.handler())
        if (resp.statusCode() !in 200..299) {
            throw IllegalStateException("OpenAI hívás HTTP ${resp.statusCode()}")
        }
        return extractContent(resp.body())
    }

    private fun extractContent(body: String): String {
        val root = JsonParser.parseString(body).asJsonObject
        val choices = root.getAsJsonArray("choices") ?: return ""
        if (choices.size() == 0) return ""
        val msg = choices[0].asJsonObject.getAsJsonObject("message")
        return msg.get("content")?.asString ?: ""
    }
}
