package hu.baader.repl.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.xdebugger.*
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.*
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import hu.baader.repl.debug.DebugTransfer
import hu.baader.repl.nrepl.NreplService
import java.awt.BorderLayout
import javax.swing.*

/** Uses the public IDEA debugger APIs and the selected stack frame's lexical evaluator. */
class InteractiveDebuggerPanel(private val project: Project, private val service: NreplService,
                               private val inspect: (Map<String,String>) -> Unit) : JPanel(BorderLayout()), Disposable {
    private val source = JTextArea(5, 70)
    private val variable = JTextField("debugValue", 16)
    private val output = JTextArea().apply { isEditable = false; lineWrap = true }
    private val status = JLabel("Start the application in Debug mode with Enable Spring Boot REPL checked.")
    private var session: XDebugSession? = null
    private var pending: DebugTransfer? = null
    private var captureInFlight: DebugTransfer? = null
    private var claiming = false
    private var evaluating = false
    private var disposed = false
    private var generation = 0
    private var connectionRevision = 0
    private val sessionListener = object : XDebugSessionListener {
        override fun sessionPaused() = onUi { generation++; evaluating = false; updateState() }
        override fun sessionResumed() = onUi { generation++; evaluating = false; updateState() }
        override fun stackFrameChanged() = onUi { generation++; evaluating = false; updateState() }
        override fun sessionStopped() = onUi { generation++; pending = null; captureInFlight = null; evaluating = false; claiming = false; status.text = "Debug session stopped" }
    }
    private val connectionListener = service.onMessage {
        if (it["op"] == "session/reset" || it["op"] == "connection" && it["state"] in setOf("DISCONNECTED", "CONNECTING", "FAILED")) {
            generation++; connectionRevision++; pending = null; captureInFlight = null; claiming = false; evaluating = false
        }
    }
    private val timer = Timer(500) { updateState() }
    init {
        val controls = WorkbookToolbar()
        fun button(text: String, action: () -> Unit) { controls.add(JButton(text).apply { addActionListener { action() } }) }
        button("Pause") { session?.takeUnless { it.isStopped || it.isSuspended }?.pause() }
        button("Resume") { suspended()?.resume() }
        button("Step over") { suspended()?.stepOver(false) }
        button("Step into") { suspended()?.stepInto() }
        button("Step out") { suspended()?.stepOut() }
        button("Show stack / locals") {
            session?.showExecutionPoint(); ToolWindowManager.getInstance(project).getToolWindow("Debug")?.show(null)
        }
        button("Evaluate in frame") { evaluate(false) }
        controls.add(JLabel("REPL variable:")); controls.add(variable)
        button("Capture to REPL") { evaluate(true) }
        add(JPanel(BorderLayout()).apply {
            add(controls, BorderLayout.NORTH)
            add(JLabel("Java expression in the selected paused stack frame (for example, input or this):"), BorderLayout.CENTER)
            add(JScrollPane(source), BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        add(JScrollPane(output), BorderLayout.CENTER); add(status, BorderLayout.SOUTH)
        timer.start()
    }
    private fun suspended(): XDebugSession? {
        val current = session
        if (current == null || current.isStopped || !current.isSuspended) { output.text = "Pause a Java debug session first."; return null }
        if (evaluating || captureInFlight != null) { output.text = "Wait for the current evaluation to finish."; return null }
        return current
    }
    private fun updateState() {
        if (disposed || project.isDisposed) return
        val current = XDebuggerManager.getInstance(project).currentSession
        if (session !== current) {
            session?.removeSessionListener(sessionListener)
            session = current; current?.addSessionListener(sessionListener)
            generation++; pending = null; captureInFlight = null; evaluating = false; claiming = false
        }
        status.text = when {
            current == null || current.isStopped -> "Start the application in Debug mode with Enable Spring Boot REPL checked."
            current.isSuspended -> "Paused: ${current.sessionName} · selected frame · ${if (pending != null) "value captured; Resume to import" else "evaluate or capture an expression"}"
            else -> "Running: ${current.sessionName}"
        }
        val transfer = pending
        if (transfer != null && current != null && !current.isStopped && !current.isSuspended && !claiming) {
            if (!transfer.matches(service.debuggerTarget())) { pending = null; output.text = "REPL target changed; capture the value again."; return }
            claiming = true
            service.request("debug/claim", mapOf("ticket" to transfer.ticket, "var" to transfer.variable), {
                if (!disposed && pending == transfer) { pending = null; claiming = false; output.text = it["value"]; inspect(mapOf("var" to transfer.variable)) }
            }, { if (!disposed && pending == transfer) { pending = null; claiming = false; output.text = it } })
        }
    }
    private fun evaluate(capture: Boolean) {
        val current = suspended() ?: return
        val evaluator = current.currentStackFrame?.evaluator ?: current.debugProcess.evaluator
        if (evaluator == null) { output.text = "This debugger cannot evaluate Java expressions."; return }
        if (capture && pending != null) { output.text = "Resume and import the pending value first."; return }
        val transfer: DebugTransfer?
        val expression: String
        try {
            val target = service.debuggerTarget()
            transfer = if (capture) { requireNotNull(target) { "Connect the REPL before capturing a debugger value." }; DebugTransfer(target.first, target.second, variable.text.trim()) } else null
            expression = transfer?.expression(source.text) ?: source.text.also { require(it.isNotBlank() && it.length <= 100_000) { "Enter a Java expression" } }
        } catch (failure: IllegalArgumentException) { output.text = failure.message; return }
        val request = ++generation; val connectionAtStart = connectionRevision; evaluating = true; captureInFlight = transfer
        evaluator.evaluate(expression, object : XDebuggerEvaluator.XEvaluationCallback {
            override fun evaluated(value: XValue) = onUi {
                if (session !== current || current.isStopped || disposed || connectionAtStart != connectionRevision) return@onUi
                // A completed capture remains claimable even if the user resumed during evaluation.
                if (transfer != null && captureInFlight == transfer) {
                    captureInFlight = null; evaluating = false
                    if (!transfer.matches(service.debuggerTarget())) { output.text = "REPL target changed; capture the value again."; return@onUi }
                    pending = transfer; evaluating = false; output.text = "Value captured in the application. Resume to import it into the REPL."; updateState()
                } else if (request == generation) { evaluating = false; render(value, request) }
            }
            override fun errorOccurred(errorMessage: String) = onUi {
                if (session !== current || current.isStopped || connectionAtStart != connectionRevision) return@onUi
                if (transfer != null && captureInFlight == transfer || request == generation) {
                    captureInFlight = null; evaluating = false; output.text = errorMessage
                }
            }
        }, current.currentPosition)
    }
    private fun render(value: XValue, request: Int) {
        value.computePresentation(object : XValueNode {
            override fun isObsolete() = disposed || request != generation
            override fun setPresentation(icon: Icon?, type: String?, value: String, hasChildren: Boolean) = onUi {
                if (!isObsolete) output.text = (type.orEmpty() + "\n" + value.take(65536)) + if (hasChildren) "\nCapture to REPL to browse this object." else ""
            }
            override fun setPresentation(icon: Icon?, presentation: XValuePresentation, hasChildren: Boolean) {
                val text = StringBuilder()
                fun append(value: String) { if (text.length < 65536) text.append(value.take(65536-text.length)) }
                presentation.renderValue(object : XValuePresentation.XValueTextRenderer {
                    override fun renderValue(value: String) = append(value)
                    override fun renderValue(value: String, key: TextAttributesKey) = append(value)
                    override fun renderStringValue(value: String) = append(value)
                    override fun renderStringValue(value: String, additionalSpecialCharsToHighlight: String?, maxLength: Int) = append(value.take(maxLength.coerceAtLeast(0)))
                    override fun renderNumericValue(value: String) = append(value)
                    override fun renderKeywordValue(value: String) = append(value)
                    override fun renderComment(comment: String) = append(comment)
                    override fun renderSpecialSymbol(symbol: String) = append(symbol)
                    override fun renderError(error: String) = append(error)
                })
                setPresentation(icon, presentation.type, text.toString(), hasChildren)
            }
            override fun setFullValueEvaluator(fullValueEvaluator: XFullValueEvaluator) { /* Keep debugger previews bounded. */ }
        }, XValuePlace.TREE)
    }
    private fun onUi(action: () -> Unit) { ApplicationManager.getApplication().invokeLater { if (!disposed && !project.isDisposed) action() } }
    override fun dispose() { disposed = true; generation++; timer.stop(); session?.removeSessionListener(sessionListener); connectionListener.dispose(); pending = null; captureInFlight = null }
}
