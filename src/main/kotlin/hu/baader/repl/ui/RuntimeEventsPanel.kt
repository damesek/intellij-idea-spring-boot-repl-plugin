package hu.baader.repl.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import hu.baader.repl.trace.RecordingController
import hu.baader.repl.trace.RecordingPanel
import hu.baader.repl.nrepl.NreplService
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.table.DefaultTableModel

class RuntimeEventsPanel(project: Project, private val service: NreplService, private val inspect: (Map<String,String>) -> Unit, activate: () -> Unit = {}) : JPanel(BorderLayout()), Disposable {
    private val model = object : DefaultTableModel(arrayOf("ID", "Time", "Kind", "Label / method", "ms", "Preview"), 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val table = JTable(model)
    private val status = JLabel("Tap is off. Live values: at most 128, retained for five minutes.")
    private val label = JTextField(14)
    private val className = JTextField(34)
    private val method = JTextField(16)
    private val traces = DefaultListModel<String>()
    private val traceList = JList(traces)
    private var disposed = false
    private var polling = false
    private var generation = 0
    private val timer = Timer(750) { if (isShowing && service.isConnected()) refresh() }
    private val listener = service.onMessage {
        if (it["op"] == "trace/configure") refreshTraces()
        if (it["op"] == "session/reset" || it["op"] == "connection" && it["state"] in setOf("CONNECTING", "DISCONNECTED", "FAILED")) {
            generation++; polling = false; model.rowCount = 0; traces.clear(); status.text = "Session changed; observations stopped."
        }
    }
    init {
        val tapBar = WorkbookToolbar()
        tapBar.add(JLabel("Exact tap label (empty = all):")); tapBar.add(label)
        fun button(bar: JPanel, text: String, action: () -> Unit) { bar.add(JButton(text).apply { addActionListener { action() } }) }
        button(tapBar, "Start tap") { service.request("events/start", mapOf("label" to label.text), { refresh() }, ::error) }
        button(tapBar, "Stop tap") { service.request("events/stop", onResult = { refresh() }, onError = ::error) }
        button(tapBar, "Refresh") { refresh(); refreshTraces() }
        button(tapBar, "Clear values") { service.request("events/clear", onResult = { refresh() }, onError = ::error) }
        button(tapBar, "Inspect selected", ::inspectSelected)
        val traceBar = WorkbookToolbar()
        traceBar.add(JLabel("Class:")); traceBar.add(className); traceBar.add(JLabel("Method (all overloads):")); traceBar.add(method)
        button(traceBar, "Trace") { configure(true) }
        button(traceBar, "Untrace") { configure(false) }
        button(traceBar, "Stop all traces") { service.request("trace/clear", onResult = { refreshTraces() }, onError = ::error) }
        traceList.addListSelectionListener { if (!it.valueIsAdjusting) traceList.selectedValue?.split('\t')?.let { parts ->
            className.text = parts[0]; method.text = parts.getOrElse(1) { "" }
        } }
        table.addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2) inspectSelected() } })
        val legacy=JPanel(BorderLayout()).apply {
            add(JPanel(BorderLayout()).apply { add(tapBar, BorderLayout.NORTH); add(traceBar, BorderLayout.SOUTH) }, BorderLayout.NORTH)
            add(JSplitPane(JSplitPane.VERTICAL_SPLIT, JScrollPane(table), JScrollPane(traceList)).apply { resizeWeight = 0.8 }, BorderLayout.CENTER)
            add(status, BorderLayout.SOUTH)
        }
        val recording=RecordingPanel(project,inspect); Disposer.register(this,recording)
        val tabs=JTabbedPane().apply { addTab("Live events",legacy);addTab("Recorded calls",recording) }
        val controller=RecordingController.get(project)
        val show: () -> Unit = { activate();tabs.selectedComponent=recording }
        controller.showPanel=show
        Disposer.register(this,Disposable { if (controller.showPanel === show) controller.showPanel=null })
        add(tabs,BorderLayout.CENTER);timer.start()
    }
    private fun configure(enabled: Boolean) {
        service.request("trace/configure", mapOf("class" to className.text.trim(), "method" to method.text.trim(), "enabled" to enabled.toString()),
            { status.text = if (enabled) "Tracing enabled" else "Tracing disabled"; refreshTraces() }, ::error)
    }
    private fun inspectSelected() {
        if (table.selectedRow >= 0) inspect(mapOf("event" to model.getValueAt(table.convertRowIndexToModel(table.selectedRow), 0).toString()))
    }
    fun refresh() {
        if (disposed || polling) return
        polling = true; val requestGeneration = generation
        service.request("events/list", onResult = {
            if (disposed || requestGeneration != generation) return@request
            polling = false
            val selected = if (table.selectedRow >= 0) table.getValueAt(table.selectedRow, 0)?.toString() else null
            model.rowCount = 0
            for (line in it["value"].orEmpty().lines().filter(String::isNotBlank)) {
                val fields = line.split('\t'); if (fields.size == 6) model.addRow(fields.toTypedArray())
            }
            for (row in 0 until model.rowCount) if (model.getValueAt(row, 0) == selected) table.setRowSelectionInterval(row, row)
            status.text = "Tap: ${if (it["subscribed"] == "true") "ON" else "OFF"} · ${model.rowCount}/128 live values · dropped: ${it["dropped"] ?: "0"} · five-minute retention"
        }, onError = { if (!disposed && requestGeneration == generation) { polling = false; error(it) } })
    }
    private fun refreshTraces() {
        val requestGeneration = generation
        service.request("trace/list", onResult = {
            if (!disposed && requestGeneration == generation) { traces.clear(); it["value"].orEmpty().lines().filter(String::isNotBlank).forEach(traces::addElement) }
        }, onError = ::error)
    }
    private fun error(message: String) { if (!disposed) status.text = message }
    override fun dispose() { disposed = true; timer.stop(); listener.dispose() }
}
