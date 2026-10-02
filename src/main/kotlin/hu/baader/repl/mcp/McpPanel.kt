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
    private val copy: (String) -> Unit = { CopyPasteManager.getInstance().setContents(StringSelection(it)) },
    initialPort: Int = 0
) : JPanel(BorderLayout(8, 8)), Disposable {
    private val start = JButton("Start MCP")
    private val stop = JButton("Stop MCP")
    private val copyConfig = JButton("Copy client config")
    private val port = JSpinner(SpinnerNumberModel(initialPort, 0, 65535, 1))
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
    private val advancedButton = JButton("Advanced settings…")
    private val advanced = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isVisible = false; alignmentX = LEFT_ALIGNMENT }
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
            add(start); add(stop); add(copyConfig)
        })
        controls.add(WorkbookToolbar().apply { alignmentX = LEFT_ALIGNMENT; add(JLabel("MCP URL:")); add(endpoint) })
        controls.add(status); controls.add(clients)
        add(controls, BorderLayout.NORTH)
        fun explanation(text: String, rows: Int) = JTextArea(text, rows, 0).apply {
            isEditable = false; isOpaque = false; lineWrap = true; wrapStyleWord = true
            font = UIManager.getFont("Label.font"); alignmentX = LEFT_ALIGNMENT
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, preferredSize.height)
        }
        val settings = object : JPanel(), Scrollable {
            override fun getPreferredScrollableViewportSize() = preferredSize
            override fun getScrollableTracksViewportWidth() = true
            override fun getScrollableTracksViewportHeight() = false
            override fun getScrollableUnitIncrement(visible: java.awt.Rectangle, orientation: Int, direction: Int) = 16
            override fun getScrollableBlockIncrement(visible: java.awt.Rectangle, orientation: Int, direction: Int) = 160
        }.apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        settings.add(explanation("""
            1. Start or connect your application in Workbook.
            2. Click Start MCP, then Copy client config.
            3. Add the copied URL and Authorization header to your local MCP client.

            All ${McpTools.all.size} tools remain available according to the permissions below.
            Java execution and changes are disabled by default. Stop MCP before changing permissions.
        """.trimIndent(), 7))
        settings.add(JLabel("Client permissions").apply { alignmentX = LEFT_ALIGNMENT })
        settings.add(WorkbookToolbar().apply { alignmentX = LEFT_ALIGNMENT; add(execution); add(reload) })
        settings.add(WorkbookToolbar().apply { alignmentX = LEFT_ALIGNMENT; add(snapshotWrites); add(snapshotDelete) })
        settings.add(WorkbookToolbar().apply { alignmentX = LEFT_ALIGNMENT; add(caseRuns); add(captureChanges) })
        settings.add(WorkbookToolbar().apply { alignmentX = LEFT_ALIGNMENT; add(recordingAccess) })
        settings.add(WorkbookToolbar().apply { alignmentX = LEFT_ALIGNMENT; add(advancedButton) })
        advanced.add(WorkbookToolbar().apply { alignmentX = LEFT_ALIGNMENT; add(JLabel("Port (0 = free):")); add(port) })
        advanced.add(WorkbookToolbar().apply { alignmentX = LEFT_ALIGNMENT; add(JLabel("Java / CASE mode:")); add(executionMode); add(JLabel("Transaction manager:")); add(transactionManager) })
        advanced.add(WorkbookToolbar().apply { alignmentX = LEFT_ALIGNMENT; add(JLabel("Timeout ms:")); add(timeout); add(JLabel("Calls / session:")); add(quota); add(JLabel("Result chars:")); add(resultLimit) })
        advanced.add(WorkbookToolbar().apply { alignmentX = LEFT_ALIGNMENT; add(toolsButton); add(redact) })
        advanced.add(explanation("ROLLBACK and READ_ONLY roll back participating synchronous database work. They do not undo external calls, independent transactions or async work. Java execution can change the whole application. Redaction cannot detect every secret.", 4))
        settings.add(advanced)
        settings.add(explanation("""
            Each client has its own REPL session. Application state and DATA snapshots are shared.
            The local port and private token are remembered for this project; the token is kept in IDE PasswordSafe.
            Help (PDF), chapter 20, explains permissions, client setup and advanced settings.
        """.trimIndent(), 5))
        add(JScrollPane(settings).apply { border = null }, BorderLayout.CENTER)
        advancedButton.addActionListener {
            advanced.isVisible = !advanced.isVisible
            advancedButton.text = if (advanced.isVisible) "Hide advanced settings" else "Advanced settings…"
            revalidate(); repaint()
        }
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
    constructor(service: McpService) : this(service::start, { service.stop() }, service::clientConfig,
        initialPort = service.rememberedPort) {
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
        if (running && state.port > 0) port.value = state.port
        endpoint.text = state.url; status.text = state.detail
        clients.text = "Clients: ${state.clients}" + if (state.lastTool.isEmpty()) "" else " · Last tool: ${state.lastTool}"
    }
    override fun dispose() { subscription?.dispose(); subscription = null }
}
