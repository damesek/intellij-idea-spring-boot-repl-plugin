package hu.baader.repl.ui

import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import hu.baader.repl.nrepl.ExecutionSelection
import hu.baader.repl.nrepl.NreplService
import java.awt.BorderLayout
import javax.swing.*

/** Compact status strip. The server-confirmed policy is the only execution authority. */
class ExecutionPolicyToolbar(private val service: NreplService, private val source: () -> String, private val report: (String) -> Unit) : JPanel(BorderLayout()), Disposable {
    private val target = JLabel("No application connected")
    private val mode = JComboBox(arrayOf("LIVE", "DB rollback", "DB read-only"))
    private val activity = JLabel("Mode not confirmed")
    private var managers = emptyList<String>()
    private var application = "Application"
    private var profiles = ""
    private var pid = ""
    var isWorkbenchBusy: () -> Boolean = { false }
    private var changing = false
    private var disposed = false
    private var contextGeneration = 0
    private val listener: Disposable
    init {
        add(WorkbookToolbar().apply { add(target); add(mode); add(activity) }, BorderLayout.CENTER)
        mode.toolTipText = "Applies after server confirmation. DB rollback covers participating synchronous DB work only; HTTP, files, messages and object changes are not undone."
        mode.accessibleContext.accessibleName = "Execution mode"
        mode.addActionListener {
            if (!changing) {
                val current = service.executionSelection.confirmed
                if (current != null && !service.isExecuting() && !isWorkbenchBusy() && !service.executionSelection.pending) {
                    val selected = listOf("LIVE", "ROLLBACK", "READ_ONLY")[mode.selectedIndex]
                    if (selected != current.mode) {
                        if (selected != "LIVE" && current.manager.isBlank() && managers.size > 1) settings(selected)
                        else service.configureExecution(current.copy(mode = selected))
                    }
                }
                refresh()
            }
        }
        listener = service.onMessage { m ->
            if (m["op"] == "connection") {
                contextGeneration++
                if (m["state"] in setOf("DISCONNECTED", "CONNECTING", "FAILED")) { application = "Application"; profiles = ""; pid = "" }
                if (m["state"] in setOf("SESSION_READY", "READY")) loadContext()
            }
            if (m["op"] == "execution/selection") {
                m["managers"]?.let { managers = it.lines().filter(String::isNotBlank) }
                m["err"]?.let(report)
            }
            if (m["op"] in setOf("connection", "execution/selection", "activity", "session/reset")) refresh()
        }
        if (service.isConnected()) {
            loadContext()
            if (!service.executionSelection.pending && !service.isExecuting()) service.refreshExecutionPolicy()
        }
        refresh()
    }
    private fun loadContext() {
        val gen = ++contextGeneration
        service.request("workspace/context", onResult = { m ->
            if (disposed || gen != contextGeneration) return@request
            runCatching {
                val context = JsonParser.parseString(m["value"]).asJsonObject
                fun value(key: String) = context[key]?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                application = value("spring.application.name").ifBlank { value("applicationClass").substringAfterLast('.') }.ifBlank { "Application" }
                val active = context["activeProfiles"]?.asJsonArray?.map { it.asString }.orEmpty()
                val defaults = context["defaultProfiles"]?.asJsonArray?.map { it.asString }.orEmpty()
                profiles = active.ifEmpty { defaults }.joinToString(", ")
                pid = value("pid")
            }.onFailure { application = "Application" }
            refresh()
        }, onError = { if (!disposed && gen == contextGeneration) { profiles = "context unavailable"; refresh() } })
    }
    private fun refresh() {
        if (disposed) return
        val selection = service.executionSelection; val policy = selection.confirmed
        changing = true
        mode.selectedIndex = listOf("LIVE", "ROLLBACK", "READ_ONLY").indexOf(policy?.mode).coerceAtLeast(0)
        mode.isEnabled = service.canExecute() && !service.isExecuting() && !isWorkbenchBusy()
        changing = false
        val connection = when (service.state) {
            NreplService.State.READY -> "Spring ready"
            NreplService.State.SESSION_READY -> "Java ready"
            NreplService.State.WAITING_CONTEXT -> "Waiting for Spring"
            NreplService.State.CONNECTING -> "Connecting…"
            NreplService.State.FAILED -> "Connection failed"
            else -> "Disconnected"
        }
        val details = listOf(application, profiles, pid.takeIf { it.isNotBlank() }?.let { "PID $it" }.orEmpty(), connection).filter(String::isNotBlank)
        target.text = if (service.isConnected()) listOf(application.take(28), profiles.take(28), connection).filter(String::isNotBlank).joinToString(" · ") else connection
        target.toolTipText = details.joinToString(" · ")
        activity.text = when {
            !service.isConnected() -> ""
            selection.pending -> selection.requested?.let { "Applying ${it.mode}… Run unavailable" } ?: "Reading execution settings…"
            !selection.ready -> "Mode unconfirmed · refresh in Session"
            service.isExecuting() -> "Running · ${policy!!.timeoutMs / 1000.0} s limit"
            else -> "${policy!!.timeoutMs / 1000.0} s · ${if (policy.mode == "LIVE") "direct execution" else policy.manager.ifBlank { "auto manager" }}"
        }
        activity.toolTipText = selection.error.ifBlank { mode.toolTipText }
        revalidate(); repaint()
    }
    fun settings(requestedMode: String? = null) {
        val current = service.executionSelection.confirmed ?: return
        if (service.isExecuting() || service.executionSelection.pending) return
        val manager = JComboBox((listOf("") + managers).distinct().toTypedArray()).apply { isEditable = true; selectedItem = current.manager }
        val timeout = JSpinner(SpinnerNumberModel(current.timeoutMs, 100, 120000, 100))
        val panel = JPanel(java.awt.GridLayout(0, 1, 0, 6)).apply {
            add(JLabel("Transaction manager (empty = the single available manager)")); add(manager)
            add(JLabel("Timeout in milliseconds")); add(timeout)
            add(JLabel("DB rollback covers only participating synchronous DB work."))
        }
        if (JOptionPane.showConfirmDialog(this, panel, "Execution settings", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            try { timeout.commitEdit(); service.configureExecution(ExecutionSelection.Policy(requestedMode ?: current.mode, manager.selectedItem?.toString().orEmpty().trim(), timeout.value as Int)) }
            catch (e: Exception) { report(e.message ?: "Invalid execution settings") }
        }
        refresh()
    }
    fun sideEffects() { service.request("execution/preflight", mapOf("code" to source()), { report(it["warnings"].orEmpty().ifBlank { "No known side-effect patterns found; this is not a guarantee." }) }, report) }
    fun audit() { service.request("audit/events", onResult = { report(it["value"].orEmpty()) }, onError = report) }
    override fun dispose() { disposed = true; contextGeneration++; listener.dispose() }
}
