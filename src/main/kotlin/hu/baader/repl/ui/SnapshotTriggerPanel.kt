package hu.baader.repl.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import hu.baader.repl.nrepl.NreplService
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.*

class SnapshotTriggerPanel(private val connection: () -> NreplService?, private val captured: () -> Unit) : JPanel(BorderLayout()), Disposable {
    private val point = JTextField("cv-input")
    private val captureName = JTextField("cv-input-capture")
    private val caseId = JTextField()
    private val type = JTextField()
    private val count = JSpinner(SpinnerNumberModel(1, 1, 100, 1))
    private val sampleEvery = JSpinner(SpinnerNumberModel(1, 1, 10000, 1))
    private var ruleId = ""
    private var updatingRules = false
    private val observedSnapshots = mutableSetOf<String>()
    private val rulesModel = object : javax.swing.table.DefaultTableModel(arrayOf("Rule ID", "State", "Point", "Name", "Saved", "Count", "Sample every", "Last snapshot"), 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val rules = JTable(rulesModel)
    private val status = JLabel("Disabled. Arm to capture the next matching application call.")
    private var phase = "IDLE"
    private var disposed = false
    private var generation = 0
    private var polling = false
    private val timer = Timer(1000) { poll() }
    init {
        val fields = JPanel(GridLayout(0, 2, 8, 6)).apply {
            add(JLabel("Capture point")); add(point)
            add(JLabel("Snapshot name (creates a new version)")); add(captureName)
            add(JLabel("Exact case ID filter (empty = any)")); add(caseId)
            add(JLabel("Declared type (optional)")); add(type)
            add(JLabel("Capture count (names gain a sequence suffix)")); add(count)
            add(JLabel("Capture every Nth matching call")); add(sampleEvery)
        }
        val controls = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JButton("Arm capture rule · 5 minutes").apply { addActionListener {
                try {
                    count.commitEdit(); sampleEvery.commitEdit()
                    request("capture/arm", mapOf("point" to point.text.trim(), "name" to captureName.text.trim(), "case" to caseId.text,
                        "type" to type.text.trim(), "ttl-ms" to "300000", "count" to count.value.toString(), "sample-every" to sampleEvery.value.toString()))
                } catch (_: java.text.ParseException) { status.text = "Capture count: 1–100; sample interval: 1–10000." }
            } })
            add(JButton("Disarm selected rule").apply { addActionListener { request("capture/disarm", mapOf("rule-id" to ruleId)) } })
            add(JButton("Refresh status").apply { addActionListener { poll(true) } })
        }
        val help = JTextArea("Add a capture point to your application using the bridge:\n\n" +
            "SnapshotHelper.capture(\"cv-input\", requestId, inputDto);\n\n" +
            "For an expensive projection, use captureLazy(point, caseId, () -> dto).\n" +
            "Up to 16 rules can be active in the JVM. Each rule has its own count, sampling and expiry.\n" +
            "Use ${'$'}{sequence} in the name or a sequence suffix is added for multiple captures.\n" +
            "Saving is synchronous. Calls arriving while a rule is saving are skipped; no async object consistency is implied.\n" +
            "Capture failures stop that rule and return false when no rule saved. DATA limit: 200 MiB per snapshot.").apply {
            isEditable = false; lineWrap = true; wrapStyleWord = true; border = BorderFactory.createEmptyBorder(12, 8, 12, 8)
        }
        add(JPanel(BorderLayout()).apply { add(fields, BorderLayout.CENTER); add(controls, BorderLayout.SOUTH) }, BorderLayout.NORTH)
        add(JSplitPane(JSplitPane.VERTICAL_SPLIT, JScrollPane(rules), help).apply { resizeWeight = 0.6 }, BorderLayout.CENTER); add(status, BorderLayout.SOUTH)
        rules.selectionModel.addListSelectionListener {
            if (!updatingRules && !it.valueIsAdjusting && rules.selectedRow >= 0) {
                ruleId = rulesModel.getValueAt(rules.convertRowIndexToModel(rules.selectedRow), 0).toString(); poll(true)
            }
        }
        connection()?.let { service -> Disposer.register(this, service.onMessage {
            if (it["op"] == "connection" || it["op"] == "session/reset") {
                generation++; polling = false; phase = "IDLE"; ruleId = ""; rulesModel.rowCount = 0; observedSnapshots.clear()
                status.text = if (service.isConnected()) "Refresh status or arm the next capture." else "Disconnected; pending captures are released with the session."
            }
        }) }
        timer.start()
    }
    private fun request(op: String, args: Map<String, String> = emptyMap()) {
        val service = connection() ?: return
        val expected = generation
        service.request(op, args, { if (!disposed && generation == expected) update(it) }, {
            if (!disposed && generation == expected) status.text = it
        })
    }
    private fun poll(force: Boolean = false) {
        val service = connection() ?: return
        if (disposed || polling || !service.isConnected() || (!force && !isShowing && phase !in setOf("ARMED", "CAPTURING"))) return
        val expected = generation; polling = true
        service.request("capture/status", mapOf("rule-id" to ruleId), onResult = {
            if (!disposed && generation == expected) { polling = false; update(it) }
        }, onError = { if (!disposed && generation == expected) { polling = false; status.text = it } })
    }
    private fun update(reply: Map<String, String>) {
        reply["rule-id"]?.let { ruleId = it }
        phase = reply["phase"].orEmpty()
        status.text = when (phase) {
            "ARMED" -> "Armed: ${reply["point"]} → ${reply["name"]}; saved ${reply["saved"]}/${reply["count"]}; expires in ${(reply["expires-in-ms"]?.toLongOrNull() ?: 0) / 1000}s"
            "CAPTURING" -> "Saving ${reply["name"]}… The capture has been claimed; further calls are ignored."
            "SAVED" -> "Saved ${reply["saved-name"]}: ${reply["bytes"]} bytes in ${reply["duration-ms"]} ms. Rule complete."
            "FAILED" -> "Capture failed; trigger disabled. ${reply["detail"]}"
            "EXPIRED" -> "Capture expired; arm again to capture a call."
            "CANCELLED" -> "Capture disarmed."
            "OTHER_SESSION" -> "No rule in this session; other sessions have active rules."
            else -> "Disabled. Arm to capture the next matching application call."
        }
        val expected = generation
        connection()?.request("capture/list", onResult = { list ->
            if (!disposed && generation == expected) {
                updatingRules = true
                try {
                    rulesModel.rowCount = 0
                    var newlySaved = false
                    list["value"].orEmpty().lines().filter(String::isNotBlank).forEach {
                        val row = it.split('\t'); rulesModel.addRow(row.toTypedArray())
                        if (row[0] == ruleId) rules.setRowSelectionInterval(rulesModel.rowCount - 1, rulesModel.rowCount - 1)
                        if (row.size > 7 && row[7].isNotBlank() && observedSnapshots.add(row[0] + ":" + row[7])) newlySaved = true
                    }
                    if (newlySaved) captured()
                } finally { updatingRules = false }
            }
        })
    }
    override fun dispose() { disposed = true; generation++; timer.stop() }
}
