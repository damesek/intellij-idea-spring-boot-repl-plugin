package hu.baader.repl.ui

import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class HttpRequestRunner(private val project: Project, private val console: ConsoleView) : Disposable {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val running = ConcurrentHashMap<String, CompletableFuture<HttpResponse<String>>>()
    private val listeners = CopyOnWriteArrayList<HttpRequestRunnerListener>()
    private fun ui(action: () -> Unit) = ApplicationManager.getApplication().invokeLater { if (!project.isDisposed) action() }
    fun run(case: HttpRequestCase) {
        val copy = case.deepCopy()
        val id = copy.id.ifBlank { "http-case" }
        if (running.size >= 8 && !running.containsKey(id)) { ui { console.print("HTTP queue full\n", ConsoleViewContentType.ERROR_OUTPUT) }; return }
        try {
            val uri = URI.create(expand(copy.url.trim()))
            require(uri.scheme in listOf("http", "https") && uri.host != null && uri.userInfo == null) { "Enter an HTTP(S) URL without embedded credentials" }
            val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
            copy.headers.filter { it.name.isNotBlank() }.forEach { builder.header(it.name.trim(), expand(it.value)) }
            val body = expand(copy.body)
            require(body.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) { "HTTP request body exceeds 1 MiB" }
            val request = builder.method(copy.method.uppercase(), if (body.isBlank()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)).build()
            abort(id)
            val start = System.nanoTime()
            val future = http.sendAsync(request, BoundedHttpBody.handler())
            running[id] = future
            ui {
                listeners.forEach { it.onStarted(id) }
                val display = URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null)
                console.print("\nHTTP " + copy.method + " " + display + "\n", ConsoleViewContentType.USER_INPUT)
            }
            future.whenComplete { response, failure ->
                val current = running.remove(id, future)
                ui {
                    val status = when {
                        future.isCancelled -> HttpRequestRunnerListener.Status.ABORTED
                        failure != null -> HttpRequestRunnerListener.Status.ERROR
                        response.statusCode() in 200..299 -> HttpRequestRunnerListener.Status.SUCCESS
                        else -> HttpRequestRunnerListener.Status.ERROR
                    }
                    if (failure != null) console.print(if (future.isCancelled) "HTTP cancelled\n" else "HTTP request failed or timed out\n", ConsoleViewContentType.ERROR_OUTPUT)
                    else {
                        console.print("Status " + response.statusCode() + " (" + ((System.nanoTime() - start) / 1_000_000) + " ms)\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                        response.headers().firstValue("content-type").ifPresent { console.print("Content-Type: " + it + "\n", ConsoleViewContentType.SYSTEM_OUTPUT) }
                        console.print(response.body().take(65536) + (if (response.body().length > 65536) "\n[truncated]" else "") + "\n", ConsoleViewContentType.NORMAL_OUTPUT)
                    }
                    if (current) listeners.forEach { it.onFinished(id, status) }
                }
            }
        } catch (e: Exception) {
            ui {
                console.print("HTTP request rejected: " + e.message + "\n", ConsoleViewContentType.ERROR_OUTPUT)
                listeners.forEach { it.onFinished(id, HttpRequestRunnerListener.Status.ERROR) }
            }
        }
    }
    fun abort(caseId: String) { running[caseId]?.cancel(true) }
    fun addListener(listener: HttpRequestRunnerListener): () -> Unit { listeners.add(listener); return { listeners.remove(listener) } }
    override fun dispose() { running.values.forEach { it.cancel(true) }; running.clear(); listeners.clear() }
    companion object {
        fun expand(value: String): String = Regex("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}").replace(value) {
            System.getenv(it.groupValues[1]) ?: error("Environment variable is missing: " + it.groupValues[1])
        }
    }
}
interface HttpRequestRunnerListener {
    enum class Status { SUCCESS, ERROR, ABORTED }
    fun onStarted(caseId: String)
    fun onFinished(caseId: String, status: Status)
}
