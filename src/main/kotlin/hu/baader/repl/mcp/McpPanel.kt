package hu.baader.repl.mcp

import com.intellij.openapi.Disposable
import com.intellij.openapi.ide.CopyPasteManager
import hu.baader.repl.ui.WorkbookToolbar
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import javax.swing.*
import javax.swing.border.EmptyBorder

internal class McpPanel(
    private val startServer: (Int, McpPermissions) -> Unit,
    private val stopServer: () -> Unit,
    private val config: () -> String?,
    private val copy: (String) -> Unit = { CopyPasteManager.getInstance().setContents(StringSelection(it)) }
) : JPanel(BorderLayout(8, 8)), Disposable {
    private val start = JButton("Start MCP")
    private val stop = JButton("Stop MCP")
    private val copyConfig = JButton("Copy client config")
    private val port = JSpinner(SpinnerNumberModel(0, 0, 65535, 1))
    private val execution = JCheckBox("Allow Java execution / state changes", false)
    private val reload = JCheckBox("Allow HotSwap", false)
    private val snapshotWrites = JCheckBox("Allow snapshot / CASE writes", false)
    private val snapshotDelete = JCheckBox("Allow snapshot deletion", false)
    private val caseRuns = JCheckBox("Allow CASE execution", false)
    private val captureChanges = JCheckBox("Allow capture / trace changes", false)
    private val recordingAccess = JCheckBox("Share IDE recordings with MCP", false)
    private val executionMode = JComboBox(arrayOf("ROLLBACK", "READ_ONLY", "LIVE"))
    private val transactionManager = JTextField(16)
    private val timeout = JSpinner(SpinnerNumberModel(30000, 100, 120000, 1000))
    private val quota = JSpinner(SpinnerNumberModel(1000, 1, 100000, 100))
    private val resultLimit = JSpinner(SpinnerNumberModel(65536, 1024, 65536, 1024))
    private val redact = JCheckBox("Redact likely secrets in MCP results", true)
    private val toolsButton = JButton("Choose allowed tools")
    private var allowedTools: Set<String>? = null
    private val status = JTextArea(2, 0).apply {
        isEditable = false; isOpaque = false; lineWrap = true; wrapStyleWord = true
        font = UIManager.getFont("Label.font"); alignmentX = LEFT_ALIGNMENT
        maximumSize = java.awt.Dimension(Int.MAX_VALUE, preferredSize.height)
    }
    private val clients = JLabel().apply { alignmentX = LEFT_ALIGNMENT }
    private val endpoint = JTextField().apply { isEditable = false; columns = 38 }
    private var subscription: Disposable? = null
    init {
        border = EmptyBorder(10, 10, 10, 10)
        val controls = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        controls.add(WorkbookToolbar().apply {
            alignmentX = LEFT_ALIGNMENT
            add(start); add(stop); add(JLabel("Port (0 = free):")); add(port); add(copyConfig)
        })
        controls.add(WorkbookToolbar().apply { alignmentX = LEFT_ALIGNMENT; add(execution); add(reload) })
        controls.add(WorkbookToolbar().apply { alignmentX = LEFT_ALIGNMENT; add(snapshotWrites); add(snapshotDelete) })
        controls.add(WorkbookToolbar().apply { alignmentX = LEFT_ALIGNMENT; add(caseRuns); add(captureChanges) })
        controls.add(WorkbookToolbar().apply { alignmentX = LEFT_ALIGNMENT; add(recordingAccess) })
        controls.add(WorkbookToolbar().apply { alignmentX = LEFT_ALIGNMENT; add(JLabel("Java / CASE mode:")); add(executionMode); add(JLabel("Transaction manager:")); add(transactionManager) })
        controls.add(WorkbookToolbar().apply { alignmentX = LEFT_ALIGNMENT; add(JLabel("Timeout ms:")); add(timeout); add(JLabel("Calls / session:")); add(quota); add(JLabel("Result chars:")); add(resultLimit) })
        controls.add(WorkbookToolbar().apply { alignmentX = LEFT_ALIGNMENT; add(toolsButton); add(redact) })
        controls.add(WorkbookToolbar().apply { alignmentX = LEFT_ALIGNMENT; add(JLabel("MCP URL:")); add(endpoint) })
        controls.add(status); controls.add(clients)
        add(controls, BorderLayout.NORTH)
        add(JScrollPane(JTextArea("""
            1. Start or connect your application in Java REPL.
            2. Click Start MCP, then Copy client config.
            3. Add the URL and Authorization header to a local AI client's Streamable HTTP MCP settings.

            Each client gets a separate persistent REPL session. The AI can check code, evaluate Java,
            inspect results, work with snapshots, arm capture triggers and run saved cases.
            Share IDE recordings exposes the current graph, captured values/source and comparisons to all allowed clients.
            Reading these frozen values does not execute Java. Start/stop also need capture/trace and state-change permissions.
            Select opens the call in your editor and needs state-change permission. Each client keeps its own reference pin.
            The shared recording remains in the IDE when a client closes. Opening a saved recording in the IDE makes it readable here.
            The default is read-only API access. Each write category and individual tool can be enabled separately.
            Java and CASE execution use the mode, manager and timeout selected above, independently of the workbook.
            When several transaction managers exist, enter the exact manager bean name. Missing managers stop execution.
            Both ROLLBACK and READ_ONLY always roll back their participating synchronous database work.
            Read-only is a driver hint. Independent transactions and async work are not covered.
            Change permissions while stopped, then start and copy the new config.

            These are tool permissions, not a Java sandbox: arbitrary Java can access the whole target JVM.
            Do not grant Java execution to an untrusted client. It can bypass narrower tool restrictions.
            Secret redaction uses heuristics and cannot detect all personal data or disguised credentials.
            Redacted audit logs are saved under ~/.java-repl-audit (4 MiB rotation, 5 retained files per channel).

            Spring beans, application effects and DATA snapshots are shared. Closing an MCP session discards
            its variables and LIVE pins. It does not undo application or database changes.
            Check result truncation flags; large snapshots stay in the app and can be exported from the IDE.

            The server listens on 127.0.0.1 only. A new private access token is generated on every start.
            Copy config includes that token. Up to 4 clients; sessions expire after 30 idle minutes.
            Stop MCP, REPL disconnect or project close ends access. No code is replayed automatically.
            Protocol details and examples are in Help (PDF) and the MCP guide.
            Remote/cloud-only clients cannot reach this local endpoint directly.
        """.trimIndent()).apply { isEditable = false; lineWrap = true; wrapStyleWord = true; isOpaque = false }).apply { border = null }, BorderLayout.CENTER)
        start.addActionListener {
            try {
                port.commitEdit(); timeout.commitEdit(); quota.commitEdit(); resultLimit.commitEdit()
                startServer((port.value as Number).toInt(), McpPermissions(execution.isSelected, reload.isSelected,
                    snapshotWrites.isSelected, snapshotDelete.isSelected, caseRuns.isSelected, captureChanges.isSelected,
                    allowedTools, executionMode.selectedItem.toString(), transactionManager.text.trim(),
                    (timeout.value as Number).toInt(), (quota.value as Number).toInt(), (resultLimit.value as Number).toInt(), redact.isSelected, recordingAccess.isSelected))
            }
            catch (_: java.text.ParseException) { status.text = "Enter valid numbers for port, timeout, quota and result size." }
            catch (e: IllegalArgumentException) { status.text = e.message }
        }
        stop.addActionListener { stopServer() }
        execution.addActionListener { if (!execution.isSelected) reload.isSelected = false; reload.isEnabled = execution.isSelected }
        copyConfig.addActionListener { config()?.let { copy(it); status.text = "Client config copied, including the private access token." } }
        toolsButton.addActionListener {
            val choices = McpTools.all.map { JCheckBox(it.name, allowedTools == null || it.name in allowedTools!!) }
            val list = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); choices.forEach(::add) }
            val scroll = JScrollPane(list).apply { preferredSize = java.awt.Dimension(390, 420) }
            if (JOptionPane.showConfirmDialog(this, scroll, "Allowed MCP tools (category permissions still apply)", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                allowedTools = choices.filter { it.isSelected }.map { it.text }.toSet()
                toolsButton.text = "Choose allowed tools"
                status.text = "${allowedTools!!.size} tools selected; execution/category permissions also apply."
            }
        }
        showState(McpState())
    }
    constructor(service: McpService) : this(service::start, { service.stop() }, service::clientConfig) {
        subscription = service.listen(::showState)
    }
    internal fun showState(state: McpState) {
        val running = state.url.isNotEmpty()
        start.isEnabled = !state.busy && !running
        stop.isEnabled = !state.busy && running
        copyConfig.isEnabled = running && !state.busy
        port.isEnabled = !state.busy && !running
        execution.isEnabled = port.isEnabled
        reload.isEnabled = port.isEnabled && execution.isSelected
        listOf(snapshotWrites, snapshotDelete, caseRuns, captureChanges, recordingAccess, executionMode, transactionManager, timeout, quota, resultLimit, redact, toolsButton).forEach { it.isEnabled = port.isEnabled }
        endpoint.text = state.url; status.text = state.detail
        clients.text = "Clients: ${state.clients}" + if (state.lastTool.isEmpty()) "" else " · Last tool: ${state.lastTool}"
    }
    override fun dispose() { subscription?.dispose(); subscription = null }
}
