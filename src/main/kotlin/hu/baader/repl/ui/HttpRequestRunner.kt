package hu.baader.repl.ui

import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class HttpRequestRunner(
    private val project: Project,
    private val console: ConsoleView
) {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    private val running = ConcurrentHashMap<String, CompletableFuture<HttpResponse<String>>>()
    private val listeners = mutableListOf<HttpRequestRunnerListener>()

    fun run(case: HttpRequestCase) {
        val id = case.id.ifBlank { "http-case" }
        val method = case.method.ifBlank { "GET" }.uppercase()
        val url = case.url.trim()
        if (url.isBlank()) {
            ApplicationManager.getApplication().invokeLater {
                console.print("HTTP request kihagyva: üres URL (${case.displayLabel()})\n", ConsoleViewContentType.ERROR_OUTPUT)
            }
            return
        }

        cancel(id)

        val builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
        case.headers.filter { it.name.isNotBlank() }.forEach {
            builder.header(it.name.trim(), it.value.trim())
        }
        val needsBody = method !in listOf("GET", "DELETE", "HEAD")
        val publisher = when {
            needsBody && case.body.isNotBlank() -> HttpRequest.BodyPublishers.ofString(case.body)
            needsBody -> HttpRequest.BodyPublishers.ofString("")
            else -> HttpRequest.BodyPublishers.noBody()
        }
        val request = builder.method(method, publisher).build()

        ApplicationManager.getApplication().invokeLater {
            console.print("\n▶ HTTP $method $url [${case.displayLabel()}]\n", ConsoleViewContentType.USER_INPUT)
        }

        listeners.forEach { it.onStarted(id) }
        val start = System.currentTimeMillis()
        val future = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        running[id] = future
        future.whenComplete { response, throwable ->
            running.remove(id)
            val duration = System.currentTimeMillis() - start
            val status: HttpRequestRunnerListener.Status
            when {
                future.isCancelled -> {
                    status = HttpRequestRunnerListener.Status.ABORTED
                    ApplicationManager.getApplication().invokeLater {
                        console.print("HTTP kérés megszakítva ($id)\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                    }
                }
                throwable != null -> {
                    status = HttpRequestRunnerListener.Status.ERROR
                    ApplicationManager.getApplication().invokeLater {
                        console.print("HTTP hiba: ${throwable.message}\n", ConsoleViewContentType.ERROR_OUTPUT)
                    }
                }
                response != null -> {
                    status = HttpRequestRunnerListener.Status.SUCCESS
                    ApplicationManager.getApplication().invokeLater {
                        console.print("⬅ Status ${response.statusCode()} (${duration} ms)\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                        response.headers().map().forEach { (k, v) ->
                            console.print("$k: ${v.joinToString(", ")}\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                        }
                        val responseBody = response.body()
                        if (!responseBody.isNullOrBlank()) {
                            console.print("$responseBody\n", ConsoleViewContentType.NORMAL_OUTPUT)
                        }
                    }
                }
                else -> {
                    status = HttpRequestRunnerListener.Status.ERROR
                    ApplicationManager.getApplication().invokeLater {
                        console.print("Ismeretlen HTTP hiba ($id)\n", ConsoleViewContentType.ERROR_OUTPUT)
                    }
                }
            }
            listeners.forEach { it.onFinished(id, status) }
        }
    }

    fun abort(caseId: String) {
        if (caseId.isBlank()) return
        cancel(caseId)
    }

    private fun cancel(caseId: String) {
        running.remove(caseId)?.cancel(true)
    }

    fun addListener(listener: HttpRequestRunnerListener): () -> Unit {
        listeners += listener
        return { listeners.remove(listener) }
    }
}

interface HttpRequestRunnerListener {
    enum class Status { SUCCESS, ERROR, ABORTED }
    fun onStarted(caseId: String)
    fun onFinished(caseId: String, status: Status)
}
