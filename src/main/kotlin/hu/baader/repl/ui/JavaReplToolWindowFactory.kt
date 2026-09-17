package hu.baader.repl.ui

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import hu.baader.repl.ai.AiAssistedPanel
import hu.baader.repl.ai.AiContextMetadataStore
import hu.baader.repl.editor.ReplCells
import hu.baader.repl.editor.ReplCompletionController
import hu.baader.repl.editor.ReplDiagnosticsController
import hu.baader.repl.editor.JavaReplEditorProvider
import hu.baader.repl.history.ReplHistoryService
import hu.baader.repl.help.ReplHelp
import hu.baader.repl.nrepl.NreplService
import hu.baader.repl.mcp.McpPanel
import hu.baader.repl.mcp.McpService
import hu.baader.repl.settings.PluginSettingsState
import java.awt.BorderLayout
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.nio.file.Files
import javax.swing.*

class JavaReplToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun init(toolWindow: ToolWindow) {
        // Keep the registered ID stable: only the visible product name changes.
        toolWindow.stripeTitle = "Spring Boot Debug REPL and MCP"
        toolWindow.title = "Spring Boot Debug REPL and MCP"
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = NreplService.getInstance(project)
        val editor = JavaReplEditorProvider.createEnhancedEditor(project)
        Disposer.register(toolWindow.disposable, Disposable { EditorFactory.getInstance().releaseEditor(editor) })
        val history = ReplHistoryService.getInstance(project)
        val tabs = object : JTabbedPane(), com.intellij.openapi.actionSystem.DataProvider {
            override fun getData(id: String): Any? = when {
                com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT.`is`(id) -> project
                com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR.`is`(id) -> editor
                else -> null
            }
        }
        val transcript = JTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
        val result = JTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
        val structuredResult = StructuredValuePanel()
        val resultTabs = JTabbedPane().apply { addTab("Value", structuredResult); addTab("Output / errors", JBScrollPane(result)) }
        val status = JLabel(service.state.name)
        val codeCheckStatus = JLabel("Connect to check against the REPL session")
        var resultHandle: String? = null
        var busy = false
        fun append(text: String) {
            transcript.append(text)
            if (transcript.document.length > 300000) transcript.replaceRange("", 0, transcript.document.length - 250000)
            transcript.caretPosition = transcript.document.length
        }
        fun error(message: String) { status.text = message; append("\nError: " + message + "\n") }
        val insert: (String) -> Unit = { snippet ->
            WriteCommandAction.runWriteCommandAction(project) {
                val offset = editor.caretModel.offset
                editor.document.insertString(offset, snippet + "\n")
                editor.caretModel.moveToOffset(offset + snippet.length + 1)
            }
            tabs.selectedIndex = 0
            editor.contentComponent.requestFocusInWindow()
        }
        lateinit var variables: LoadedVariablesPanel
        val beans = BeanExplorerPanel(service, insert)
        val watches = WatchPanel(service)
        Disposer.register(toolWindow.disposable, beans)
        Disposer.register(toolWindow.disposable, watches)
        resultTabs.addTab("Watches", watches)
        val snapshots = SimplifiedSnapshotsPanel({ service }, insert, { _, _ -> variables.refreshVariables() })
        val inspector = InspectorPanel(service) { variables.refreshVariables(); snapshots.refresh() }
        val inspectValue: (Map<String,String>) -> Unit = { extra -> inspector.open(extra); tabs.selectedComponent = inspector }
        variables = LoadedVariablesPanel({ service }, insert, inspectValue)
        val events = RuntimeEventsPanel(project, service, inspectValue) { tabs.selectedIndex=tabs.indexOfTab("Tap / Trace") }
        val cases = SnapshotCasesPanel(project, service, inspectValue)
        val debugger = InteractiveDebuggerPanel(project, service, inspectValue)
        val mcp = McpPanel(McpService.getInstance(project))
        Disposer.register(toolWindow.disposable, mcp)
        for (panel in listOf(inspector, events, cases, debugger)) Disposer.register(toolWindow.disposable, panel)
        Disposer.register(toolWindow.disposable, snapshots)
        val completion = ReplCompletionController(project, editor, service, { !busy }, { status.text = it })
        Disposer.register(toolWindow.disposable, completion)
        val diagnostics = ReplDiagnosticsController(project, editor, service, { !busy }, { codeCheckStatus.text = it })
        Disposer.register(toolWindow.disposable, diagnostics)

        val workspaceStore = hu.baader.repl.workspace.WorkspaceStore.getInstance(project)
        inspector.bookmarks = { workspaceStore.document.bookmarks }
        inspector.bookmarksChanged = { workspaceStore.checkpoint() }
        val notebook = hu.baader.repl.editor.NotebookController(project, editor, service, workspaceStore, { status.text = it }) { running -> busy = running; if (running) completion.invalidate() }
        Disposer.register(toolWindow.disposable, notebook)
        resultTabs.addTab("Cells", notebook.panel)
        val inlays = hu.baader.repl.editor.NotebookInlays(project, editor, notebook, service, inspectValue, { id ->
            notebook.panel.selectCell(id); resultTabs.selectedComponent = notebook.panel
        }, { snapshots.refresh() }, { status.text = it })
        Disposer.register(toolWindow.disposable, inlays)
        fun run(all: Boolean = false, advance: Boolean = false) {
            notebook.run(if (all) "all" else "current", advance)
        }
        fun saveResult(op: String) {
            val handle = resultHandle ?: return
            val name = Messages.showInputDialog(project, "Snapshot name", "Save Result", null) ?: return
            val extra = mutableMapOf("name" to name, "handle" to handle)
            if (op == "snapshot/save") {
                val type = Messages.showInputDialog(project, "Optional declared type (including generic arguments)", "DATA Type", null) ?: return
                if (type.isNotBlank()) extra["type"] = type.trim()
            }
            service.request(op, extra, { status.text = it["value"]; snapshots.refresh() }, ::error)
        }
        val commands = linkedMapOf<String, () -> Unit>()
        fun button(label: String, action: () -> Unit) { commands[label] = action }
        button("Connect") { service.connectAsync() }
        button("Open endpoint") {
            FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFileDescriptor("properties"), project, null)?.let {
                service.connectEndpoint(java.nio.file.Path.of(it.path))
            }
        }
        button("Disconnect") { service.disconnect() }
        button("Bind") { service.bindSpring(onResult = { status.text = if (it == "true") "READY" else "Spring context is not ready" }, onError = ::error) }
        button("Run cell / selection") { run() }
        button("Run + next") { run(advance = true) }
        button("Run all") { run(all = true) }
        button("Interrupt") { notebook.stopQueue(); service.interrupt({ status.text = it }, ::error) }
        button("Reset") {
            if (Messages.showYesNoDialog(project, "Discard session definitions, handles and LIVE pins?", "Reset REPL", null) == Messages.YES)
                service.resetSession({ resultHandle = null; variables.refreshVariables(); snapshots.refresh(); status.text = it }, ::error)
        }
        button("Complete") { completion.request() }
        var liveCheck = true
        button("Toggle live check") { liveCheck = !liveCheck; diagnostics.setEnabled(liveCheck); status.text = "Live check: " + if (liveCheck) "on" else "off" }
        button("Check code") { diagnostics.request() }
        button("Help (PDF)") { ReplHelp.open(project) }
        button("MCP") { tabs.selectedComponent = mcp }
        button("Bean explorer") { tabs.selectedComponent = beans; beans.refresh() }

        lateinit var http: HttpRequestsPanel
        val workspaceActions = hu.baader.repl.workspace.WorkspaceActions(project, service, workspaceStore, notebook, { http }, { transcript.text }, { transcript.text = it }, { snapshots.refresh(); cases.refresh(); variables.refreshVariables() }, { status.text = it }, { busy })
        fun workbookButton(label: String, action: () -> Unit) { commands[label] = action }
        workbookButton("Run above") { notebook.run("above") }
        workbookButton("Run from here") { notebook.run("from") }
        workbookButton("Run affected") { notebook.run("affected") }
        workbookButton("Restart + run all") {
            if (Messages.showYesNoDialog(project, "Discard the session and run every cell in source order? Application effects may be repeated.", "Restart Workbook", null) == Messages.YES) notebook.run("restart")
        }
        workbookButton("Analyze dependencies") { notebook.analyzeDependencies() }
        workbookButton("Save workspace") { workspaceActions.save() }
        workbookButton("Open workspace") { workspaceActions.open() }
        workbookButton("Workspace info") { workspaceActions.info() }
        workbookButton("Restore DATA binding") { workspaceActions.restoreData() }
        workbookButton("Insert saved imports") { workspaceActions.insertImports(insert) }
        workbookButton("Insert cell") {
            WriteCommandAction.runWriteCommandAction(project) {
                val offset = ReplCells.at(editor.document.text, editor.caretModel.offset).end
                val marker = "\n// %%\n"
                editor.document.insertString(offset, marker)
                editor.caretModel.moveToOffset(offset + marker.length)
            }
            editor.contentComponent.requestFocusInWindow()
        }
        workbookButton("Open workbook") {
            FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFileDescriptor(), project, null)?.let { file ->
                ApplicationManager.getApplication().executeOnPooledThread {
                    try {
                        check(file.length <= 1_000_000) { "Workbook exceeds 1 MB" }
                        val text = String(file.contentsToByteArray(), Charsets.UTF_8)
                        ApplicationManager.getApplication().invokeLater {
                            if (!project.isDisposed) WriteCommandAction.runWriteCommandAction(project) { editor.document.setText(text) }
                        }
                    } catch (e: Exception) { ApplicationManager.getApplication().invokeLater { error(e.message.orEmpty()) } }
                }
            }
        }
        workbookButton("Save workbook") {
            val target = FileChooserFactory.getInstance().createSaveFileDialog(FileSaverDescriptor("Save REPL Workbook", "Saving does not execute code", "jsh"), project)
                .save(null as VirtualFile?, "workbook.jsh") ?: return@workbookButton
            val text = editor.document.text
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    Files.writeString(target.file.toPath(), text)
                    ApplicationManager.getApplication().invokeLater { if (!project.isDisposed) status.text = "Workbook saved; no code executed." }
                } catch (e: Exception) { ApplicationManager.getApplication().invokeLater { error(e.message.orEmpty()) } }
            }
        }
        workbookButton("Save RECIPE") {
            val name = Messages.showInputDialog(project, "Recipe name", "Save Setup Code", null) ?: return@workbookButton
            val code = editor.selectionModel.selectedText ?: editor.document.text
            service.request("recipe/save", mapOf("name" to name, "code" to code), { snapshots.refresh(); status.text = it["value"] }, ::error)
        }
        workbookButton("History") {
            val entries = history.entries().takeLast(100).reversed()
            if (entries.isEmpty()) return@workbookButton
            val labels = entries.map { it.replace('\n', ' ').take(100) }.toTypedArray()
            val index = Messages.showChooseDialog("Insert a previous snippet (does not run it)", "History", labels, labels.first(), null)
            if (index >= 0) insert(entries[index])
        }
        workbookButton("Clear history") { history.clear(); transcript.text = "" }
        workbookButton("Insert bean") {
            service.listSpringBeans({ beans ->
                if (beans.isEmpty()) return@listSpringBeans
                val labels = beans.map { it.name + " : " + it.className }.toTypedArray()
                val index = Messages.showChooseDialog("Choose a bean; it is retrieved only when you run the inserted code.", "Spring Beans", labels, labels.first(), null)
                if (index >= 0) {
                    val name = beans[index].name.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
                    val type = Messages.showInputDialog(project, "Optional public bean type (interface or class)", "Bean Type", null) ?: return@listSpringBeans
                    if (type.isNotBlank() && !type.matches(Regex("[A-Za-z_$][\\w.$]*"))) { error("Invalid Java type"); return@listSpringBeans }
                    insert("var bean = ctx.getBean(\"" + name + "\"" + (if (type.isBlank()) "" else ", " + type + ".class") + ");")
                }
            }, ::error)
        }
        workbookButton("Apply configured imports") {
            val imports = PluginSettingsState.getInstance().state.importAliases.filter { it.enabled }.map { it.fqn }
            service.addImports(imports, { status.text = "Imports applied: " + it.size }, ::error)
        }
        val executionPolicy = ExecutionPolicyToolbar(service, { editor.selectionModel.selectedText ?: editor.document.text }) { message -> status.text = message.lineSequence().first(); append("\n$message\n") }
        executionPolicy.isWorkbenchBusy = { busy }
        val reproductions = ReproductionActions(project,service,{ snapshots.refresh(); cases.refresh() },{ append("\n"+it+"\n") })
        workbookButton("Create reproduction") { reproductions.create() }
        workbookButton("Export reproduction") { reproductions.export() }
        workbookButton("Import reproduction") { reproductions.importBundle() }
        Disposer.register(toolWindow.disposable, executionPolicy)
        workbookButton("Execution settings") { executionPolicy.settings() }
        workbookButton("Refresh execution settings") { service.refreshExecutionPolicy() }
        workbookButton("Side-effect hints") { executionPolicy.sideEffects() }
        workbookButton("Audit events") { executionPolicy.audit() }
        val workbenchToolbar = hu.baader.repl.actions.WorkbenchActions.toolbar(tabs)
        val top = JPanel(BorderLayout()).apply {
            add(workbenchToolbar.component, BorderLayout.NORTH)
            add(executionPolicy, BorderLayout.SOUTH)
        }
        val resultBar = WorkbookToolbar()
        fun resultButton(label: String, action: () -> Unit) {
            commands[label] = action
            resultBar.add(JButton(label).apply { addActionListener { if (service.isConnected() && resultHandle != null) action() } })
        }
        resultButton("Inspect result") { resultHandle?.let { inspectValue(mapOf("handle" to it)) } }
        resultButton("Watch result") { resultTabs.selectedComponent=watches;watches.pin("last1") }
        resultButton("Pin LIVE") { saveResult("snapshot/pin") }
        resultButton("Freeze DATA") { saveResult("snapshot/save") }
        val resultPanel = JPanel(BorderLayout()).apply {
            minimumSize = java.awt.Dimension(220, 160)
            add(resultBar, BorderLayout.NORTH); add(resultTabs, BorderLayout.CENTER)
        }
        val editorPanel = JPanel(BorderLayout()).apply {
            minimumSize = java.awt.Dimension(260, 160)
            add(editor.component, BorderLayout.CENTER); add(codeCheckStatus, BorderLayout.SOUTH)
        }
        val editing = object : JSplitPane(HORIZONTAL_SPLIT, editorPanel, resultPanel) {
            private var positioned = false
            override fun doLayout() {
                if (!positioned && width > 0) { dividerLocation = (width * 0.65).toInt(); positioned = true }
                super.doLayout()
            }
        }.apply { resizeWeight = 0.65 }
        val repl = JPanel(BorderLayout()).apply {
            add(top, BorderLayout.NORTH)
            add(JSplitPane(JSplitPane.VERTICAL_SPLIT, editing, JBScrollPane(transcript)).apply { resizeWeight = 0.65 }, BorderLayout.CENTER)
            add(status, BorderLayout.SOUTH)
        }
        tabs.addTab("Java REPL", repl); tabs.addTab("Variables", variables); tabs.addTab("Snapshots", snapshots)
        tabs.addTab("Inspector", inspector); tabs.addTab("Tap / Trace", events); tabs.addTab("Cases / Reload", cases); tabs.addTab("Debugger", debugger)
        val httpConsole = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        Disposer.register(toolWindow.disposable, httpConsole)
        val httpRunner = HttpRequestRunner(project, httpConsole)
        Disposer.register(toolWindow.disposable, httpRunner)
        http = HttpRequestsPanel(project, insert, httpRunner)
        tabs.addTab("HTTP", JSplitPane(JSplitPane.VERTICAL_SPLIT, http, httpConsole.component).apply { resizeWeight = 0.65 })
        tabs.addTab("AI", AiAssistedPanel(project, service, AiContextMetadataStore.getInstance(project), insert) { tabs.selectedIndex = 0 })
        tabs.addTab("Imports", ImportAliasesPanel())
        tabs.addTab("MCP", mcp)
        tabs.addTab("Beans", beans)
        tabs.addChangeListener {
            when (tabs.selectedIndex) { 1 -> variables.refreshVariables(); 2 -> snapshots.refresh() }
            if (tabs.selectedComponent === events) events.refresh()
            if (tabs.selectedComponent === cases) cases.refresh()
        }
        Disposer.register(toolWindow.disposable, service.onMessage { message ->
            if (message["op"] == "connection") {
                status.text = message["state"] + " " + message["detail"].orEmpty()
                if (message["state"] in setOf("DISCONNECTED", "CONNECTING", "FAILED")) {
                    resultHandle = null; structuredResult.clear(); variables.clear(); busy = false
                }
                return@onMessage
            }
            if (message["op"] == "session/reset" && message["err"].isNullOrEmpty()) {
                resultHandle = null; structuredResult.clear(); result.text = ""
                return@onMessage
            }
            if (message["op"] !in setOf("eval", "java-eval")) return@onMessage
            resultHandle = message["handle"]
            if (message.containsKey("execution-mode")) append("\nExecution: ${message["execution-mode"]}; rollback=${message["transaction-rolled-back"]}; ${message["execution-duration-ms"]} ms\n")
            message["side-effect-warnings"]?.let { append(it + "\n") }
            val text = listOfNotNull(message["out"], message["stderr"], message["values"] ?: message["value"], message["err"]).joinToString("\n")
            result.text = text.take(100000)
            structuredResult.showValue(message, text)
            resultTabs.selectedIndex = if (message["err"].isNullOrEmpty() && message.containsKey("view-data")) 0 else 1
            append("\n> " + message["snippet"].orEmpty() + "\n" + text + "\n")
            if (tabs.selectedIndex == 1) variables.refreshVariables()
        })
        fun shortcut(stroke: KeyStroke, action: () -> Unit) {
            object : com.intellij.openapi.project.DumbAwareAction() {
                override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) = action()
            }.registerCustomShortcutSet(com.intellij.openapi.actionSystem.CustomShortcutSet(stroke), editor.contentComponent, toolWindow.disposable)
        }
        for (modifier in listOf(InputEvent.CTRL_DOWN_MASK, InputEvent.META_DOWN_MASK))
            shortcut(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, modifier)) { run() }
        shortcut(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK)) { run(advance = true) }
        hu.baader.repl.actions.WorkbenchActions.get(project).bind(commands, { command ->
            when {
                command.connected && !service.isConnected() -> "Connect an application first"
                command.executes && !service.canExecute() -> "Wait for confirmed execution settings"
                command.key == "Connect" && (service.isConnected() || service.state == NreplService.State.CONNECTING) -> "Use Session to disconnect or select another endpoint"
                command.group == "Result" && resultHandle == null -> "Evaluate an expression with a result first"
                command.key != "Interrupt" && (command.executes || command.key in setOf("Reset", "OpenWorkbook", "OpenWorkspace", "ExecutionSettings", "Analyze")) && (busy || service.isExecuting()) -> "Wait for the active execution or interrupt it"
                else -> null
            }
        }, toolWindow.disposable, inspectValue)
        workbenchToolbar.updateActionsImmediately()
        toolWindow.contentManager.addContent(toolWindow.contentManager.factory.createContent(tabs, "", false))
    }
}
