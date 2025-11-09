package hu.baader.repl.ui

import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.EditorFactory
import com.intellij.lang.java.JavaLanguage
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.editor.EditorSettings
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFileFactory
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBScrollPane
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.util.Disposer
import com.intellij.ui.content.ContentFactory
import hu.baader.repl.nrepl.NreplService
import hu.baader.repl.history.ReplHistoryService
import javax.swing.JPanel
import java.awt.BorderLayout
import com.intellij.icons.AllIcons
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.command.WriteCommandAction
import hu.baader.repl.editor.JavaReplEditorProvider
import hu.baader.repl.editor.ReplJavaFormatter
import hu.baader.repl.settings.PluginSettingsState
import com.intellij.testFramework.LightVirtualFile
import javax.swing.JList
import kotlin.text.Regex

class JavaReplToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val consoleImpl = ConsoleViewImpl(project, true)
        val console: ConsoleView = consoleImpl
        Disposer.register(toolWindow.disposable, console)

        // Create editor with Java file type for syntax highlighting
        val document = EditorFactory.getInstance().createDocument("")
        val editor = EditorFactory.getInstance().createEditor(
            document,
            project,
            JavaFileType.INSTANCE,
            false
        ) as EditorEx

        // Configure editor for Java
        editor.settings.apply {
            isLineNumbersShown = true
            isIndentGuidesShown = true
            isCaretRowShown = true
            isFoldingOutlineShown = true
            isAutoCodeFoldingEnabled = false  // Disable auto-folding in REPL
            setTabSize(4)
            isSmartHome = true
            isWhitespacesShown = false
            isAdditionalPageAtBottom = false
        }

        // Set up Java syntax highlighter properly
        val highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(
            project,
            "ReplSession.java"
        )
        editor.highlighter = highlighter

        // Project-scoped persistent history
        val historyService = ReplHistoryService.getInstance(project)
        val history = historyService.entries()
        var historyIndex = history.size // points to next slot after last entry
        val lastErrorBuffer = StringBuilder()

        val service = NreplService.getInstance(project)

        fun sanitizeVarName(source: String?): String {
            if (source.isNullOrBlank()) return ""
            val cleaned = source.replace(Regex("[^A-Za-z0-9_]"), "")
            if (cleaned.isEmpty()) return ""
            return cleaned.replaceFirstChar { it.lowercaseChar() }
        }

        fun simpleClassName(fqn: String): String {
            if (fqn.isBlank()) return ""
            var simple = fqn.substringAfterLast('.')
            if (simple.contains('$')) simple = simple.substringAfterLast('$')
            return simple
        }

        fun insertBeanSnippet(bean: NreplService.BeanInfo) {
            val targetClass = bean.className.ifBlank { "Object" }
            val preferred = sanitizeVarName(bean.name)
            val fallback = sanitizeVarName(simpleClassName(targetClass))
            val varName = listOf(preferred, fallback, "bean").first { it.isNotBlank() }
            val snippet = "var $varName = applicationContext.getBean($targetClass.class);\n"
            WriteCommandAction.runWriteCommandAction(project) {
                val document = editor.document
                val offset = editor.caretModel.offset
                document.insertString(offset, snippet)
                editor.caretModel.moveToOffset(offset + snippet.length)
            }
        }

        fun replaceEditorText(value: String) {
            WriteCommandAction.runWriteCommandAction(project) {
                editor.document.setText(value)
                editor.caretModel.moveToOffset(editor.document.textLength)
            }
        }

        fun loadHistoryEntry(direction: Int) {
            if (history.isEmpty()) return
            if (direction < 0) {
                when {
                    historyIndex == history.size && history.isNotEmpty() -> {
                        historyIndex = history.size - 1
                        replaceEditorText(history[historyIndex])
                    }
                    historyIndex > 0 -> {
                        historyIndex--
                        replaceEditorText(history[historyIndex])
                    }
                }
            } else {
                when {
                    historyIndex < history.size - 1 -> {
                        historyIndex++
                        replaceEditorText(history[historyIndex])
                    }
                    historyIndex == history.size - 1 -> {
                        historyIndex = history.size
                        replaceEditorText("")
                    }
                    historyIndex == history.size -> replaceEditorText("")
                }
            }
        }

        fun showBeanChooser(items: List<NreplService.BeanInfo>) {
            if (items.isEmpty()) {
                console.print("No Spring beans reported.\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                return
            }
            ApplicationManager.getApplication().invokeLater {
                val popup = JBPopupFactory.getInstance()
                    .createPopupChooserBuilder(items)
                    .setTitle("Insert Spring Bean Getter")
                    .setNamerForFiltering { "${it.name} ${it.className}" }
                    .setRenderer(object : SimpleListCellRenderer<NreplService.BeanInfo>() {
                        override fun customize(
                            list: JList<out NreplService.BeanInfo>,
                            value: NreplService.BeanInfo?,
                            index: Int,
                            selected: Boolean,
                            hasFocus: Boolean
                        ) {
                            text = value?.let {
                                if (it.className.isBlank()) it.name else "${it.name} — ${it.className}"
                            } ?: ""
                        }
                    })
                    .setItemChosenCallback { bean -> insertBeanSnippet(bean) }
                    .createPopup()
                popup.showInBestPositionFor(editor)
            }
        }

        fun applyImportAliases(code: String): String {
            var updated = code
            val aliases = PluginSettingsState.getInstance().state.importAliases
                .filter { it.enabled && it.alias.isNotBlank() && it.fqn.isNotBlank() }
                .sortedByDescending { it.alias.length }
            for (alias in aliases) {
                val pattern = Regex("(?<![\\w$])${Regex.escape(alias.alias)}(?![\\w$])")
                updated = pattern.replace(updated, alias.fqn)
            }
            return updated
        }

        // Handle nREPL messages; capture unsub to dispose later
        val unsub = service.onMessage { msg ->
            val out = msg["out"]
            if (out != null) {
                console.print(out, ConsoleViewContentType.NORMAL_OUTPUT)
            }
            
            val value = msg["value"]
            if (value != null) {
                console.print("=> $value\n", ConsoleViewContentType.SYSTEM_OUTPUT)
            }
            
            val err = msg["err"]
            if (err != null) {
                console.print(err, ConsoleViewContentType.ERROR_OUTPUT)
                // Keep full error text for the "Show Last Error" action
                lastErrorBuffer.append(err)
            }
            
            val ex = msg["ex"]
            if (ex != null) {
                console.print("Exception: $ex\n", ConsoleViewContentType.ERROR_OUTPUT)
            }
        }

        // Run action - executes selected text or entire document
        val run = object : AnAction("Execute", "Execute selected code or entire document (Ctrl+Enter)", AllIcons.Actions.Execute) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
            override fun actionPerformed(e: AnActionEvent) {
                val rawText = editor.selectionModel.selectedText ?: editor.document.text
                if (rawText.isNotBlank()) {
                    var text = rawText
                    try {
                        console.print(">> Executing Java code...\n", ConsoleViewContentType.USER_INPUT)
                        // Save to history
                        historyService.add(rawText)
                        historyIndex = history.size
                        // Reset last error buffer
                        lastErrorBuffer.setLength(0)
                        // Convenience: if Spring is bound, inject typed applicationContext variable
                        if (service.isSpringBound()) {
                            val pre = "org.springframework.context.ApplicationContext applicationContext = (org.springframework.context.ApplicationContext) ctx;\n"
                            if (!text.contains("org.springframework.context.ApplicationContext applicationContext")) {
                                text = pre + text
                            }
                        }
                        text = applyImportAliases(text)
                        service.evalJava(text)
                    } catch (ex: Exception) {
                        console.print("Error: ${ex.message}\n", ConsoleViewContentType.ERROR_OUTPUT)
                    }
                }
            }
            
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = service.isConnected()
            }
        }
        
        // Register Ctrl+Enter shortcut
        run.registerCustomShortcutSet(CustomShortcutSet.fromString("ctrl ENTER"), editor.component)

        val hotSwap = object : AnAction("Hot Swap", "Compile and reload selected class into target JVM", AllIcons.Actions.BuildLoadChanges) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
            override fun actionPerformed(e: AnActionEvent) {
                val text = editor.selectionModel.selectedText?.takeIf { it.isNotBlank() } ?: editor.document.text
                if (text.isBlank()) {
                    console.print("No Java source to hot swap.\n", ConsoleViewContentType.ERROR_OUTPUT)
                    return
                }
                try {
                    console.print(">> HotSwap request sent...\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                    service.hotSwap(text,
                        onResult = { msg ->
                            ApplicationManager.getApplication().invokeLater {
                                console.print("$msg\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                            }
                        },
                        onError = { err ->
                            ApplicationManager.getApplication().invokeLater {
                                console.print("HotSwap error: $err\n", ConsoleViewContentType.ERROR_OUTPUT)
                            }
                        }
                    )
                } catch (ex: Exception) {
                    console.print("HotSwap error: ${ex.message}\n", ConsoleViewContentType.ERROR_OUTPUT)
                }
            }

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = service.isConnected()
            }
        }

        // Format code action (Ctrl+Alt+L)
        val formatCode = object : AnAction("Format Code", "Format Java code", AllIcons.Actions.ReformatCode) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
            override fun actionPerformed(e: AnActionEvent) {
                WriteCommandAction.runWriteCommandAction(project) {
                    val text = editor.document.text
                    // Use simple formatting that actually works
                    val formatted = formatJavaCode(text)
                    editor.document.setText(formatted)
                }
            }

            private fun formatJavaCode(code: String): String {
                val lines = code.split("\n")
                val formatted = mutableListOf<String>()
                var indent = 0

                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) {
                        formatted.add("")
                        continue
                    }

                    // Decrease indent for closing braces
                    if (trimmed.startsWith("}") || trimmed.startsWith("else")) {
                        indent = maxOf(0, indent - 1)
                    }

                    // Add the line with proper indentation
                    formatted.add("    ".repeat(indent) + trimmed)

                    // Increase indent after opening braces
                    if (trimmed.endsWith("{") ||
                        trimmed.startsWith("if ") && trimmed.endsWith(")") ||
                        trimmed.startsWith("for ") && trimmed.endsWith(")") ||
                        trimmed.startsWith("while ") && trimmed.endsWith(")")) {
                        indent++
                    }

                    // Handle single line if/for/while
                    if ((trimmed.startsWith("if ") || trimmed.startsWith("for ") ||
                         trimmed.startsWith("while ")) && !trimmed.endsWith("{") && !trimmed.endsWith(")")) {
                        // Don't increase indent for single-line statements
                    }
                }

                return formatted.joinToString("\n")
            }
        }
        formatCode.registerCustomShortcutSet(CustomShortcutSet.fromString("ctrl alt L"), editor.component)

        val beanHelper = object : AnAction("Insert Bean Getter", "Insert applicationContext.getBean(...) snippet", AllIcons.Nodes.Services) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
            override fun actionPerformed(e: AnActionEvent) {
                try {
                    service.listSpringBeans(
                        onResult = { beans -> showBeanChooser(beans) },
                        onError = { err ->
                            ApplicationManager.getApplication().invokeLater {
                                console.print("Bean lookup error: $err\n", ConsoleViewContentType.ERROR_OUTPUT)
                            }
                        }
                    )
                } catch (ex: Exception) {
                    console.print("Bean lookup error: ${ex.message}\n", ConsoleViewContentType.ERROR_OUTPUT)
                }
            }

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = service.isConnected() && service.isSpringBound()
            }
        }

        // Remove code completion for now - it needs more complex setup
        // We'll rely on manual typing and the Loaded Variables panel

        // Connect action
        val connect = object : AnAction("Connect", "Connect to nREPL server", AllIcons.RunConfigurations.Remote) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
            override fun actionPerformed(e: AnActionEvent) {
                try {
                    console.print("Connecting to nREPL server...\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                    service.connectAsync()
                    console.print("Connected successfully!\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                } catch (t: Throwable) {
                    console.print("Connect failed: ${t.message}\n", ConsoleViewContentType.ERROR_OUTPUT)
                }
            }
            
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = !service.isConnected()
            }
        }

        // Disconnect action
        val disconnect = object : AnAction("Disconnect", "Disconnect from nREPL server", AllIcons.Actions.Cancel) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
            override fun actionPerformed(e: AnActionEvent) {
                service.disconnect()
                console.print("Disconnected from nREPL server\n", ConsoleViewContentType.SYSTEM_OUTPUT)
            }
            
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = service.isConnected()
            }
        }
        
        // Clear console action
        val clear = object : AnAction("Clear Console", "Clear console output", AllIcons.Actions.GC) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
            override fun actionPerformed(e: AnActionEvent) {
                console.clear()
            }
        }

        val showLastError = object : AnAction("Show Last Error", "Print last error stacktrace", AllIcons.General.Error) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
            override fun actionPerformed(e: AnActionEvent) {
                if (lastErrorBuffer.isNotEmpty()) {
                    console.print("\n--- Last Error ---\n", ConsoleViewContentType.ERROR_OUTPUT)
                    console.print(lastErrorBuffer.toString(), ConsoleViewContentType.ERROR_OUTPUT)
                    console.print("\n------------------\n", ConsoleViewContentType.ERROR_OUTPUT)
                } else {
                    console.print("No error captured in this session.\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                }
            }
        }

        val showHistory = object : AnAction("History", "Browse command history", AllIcons.Vcs.History) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
            override fun actionPerformed(e: AnActionEvent) {
                val entries = history.toList().asReversed()
                if (entries.isEmpty()) {
                    console.print("History is empty.\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                    return
                }
                JBPopupFactory.getInstance()
                    .createPopupChooserBuilder(entries)
                    .setTitle("History")
                    .setItemChosenCallback { selected ->
                        WriteCommandAction.runWriteCommandAction(project) {
                            editor.document.setText(selected)
                            editor.caretModel.moveToOffset(editor.document.textLength)
                            historyIndex = history.indexOf(selected) + 1
                        }
                    }
                    .createPopup()
                    .showInFocusCenter()
            }
        }

        val clearHistory = object : AnAction("Clear History", "Clear stored command history", AllIcons.Actions.Rollback) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
            override fun actionPerformed(e: AnActionEvent) {
                historyService.clear()
                historyIndex = 0
                console.print("History cleared.\n", ConsoleViewContentType.SYSTEM_OUTPUT)
            }
        }

        val historyPrevAction = object : AnAction("Previous Command", "Load previous REPL command", AllIcons.Actions.PreviousOccurence) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
            override fun actionPerformed(e: AnActionEvent) { loadHistoryEntry(-1) }
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = history.isNotEmpty()
            }
        }

        val historyNextAction = object : AnAction("Next Command", "Load next REPL command", AllIcons.Actions.NextOccurence) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
            override fun actionPerformed(e: AnActionEvent) { loadHistoryEntry(1) }
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = history.isNotEmpty()
            }
        }

        // Snapshot helper actions (server provides hu.vernyomas.app.repl.SnapshotStore)
        val listSnapshots = object : AnAction("List Snapshots", "Show all saved snapshots", AllIcons.Nodes.DataSchema) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
            override fun actionPerformed(e: AnActionEvent) {
                try {
                    service.listAgentSnapshots(onResult = { tsv ->
                        console.print("\n--- Snapshots (name\ttype\tmode\tts\tsize) ---\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                        console.print(tsv.ifBlank { "(empty)\n" }, ConsoleViewContentType.SYSTEM_OUTPUT)
                        console.print("----------------------------------------------\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                    }, onError = { err ->
                        console.print("List failed: $err\n", ConsoleViewContentType.ERROR_OUTPUT)
                    })
                } catch (t: Throwable) {
                    console.print("List failed: ${t.message}\n", ConsoleViewContentType.ERROR_OUTPUT)
                }
            }
        }

        val bindSnapshot = object : AnAction("Load Snapshot", "Load a saved snapshot into a variable", AllIcons.Actions.Download) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
            override fun actionPerformed(e: AnActionEvent) {
                val name = com.intellij.openapi.ui.Messages.showInputDialog(
                    project,
                    "Snapshot name (handle)",
                    "Bind Snapshot",
                    AllIcons.General.Locate
                ) ?: return
                val type = com.intellij.openapi.ui.Messages.showInputDialog(
                    project,
                    "Target type FQN (optional, leave empty for Object)",
                    "Bind Snapshot",
                    AllIcons.General.Locate
                )
                val fqn = type?.trim().orEmpty()
                // Use reflection to avoid compile-time dependency on agent classes
                val snippet = if (fqn.isNotEmpty()) {
                    """
                    try {
                      Class<?> ss = Class.forName("com.baader.devrt.SnapshotStore");
                      java.lang.reflect.Method gm;
                      try { gm = ss.getMethod("get", String.class); }
                      catch (NoSuchMethodException ex) { gm = ss.getDeclaredMethod("get", String.class); gm.setAccessible(true); }
                      Object tmp = gm.invoke(null, "$name");
                      var $name = ($fqn) tmp;
                    } catch (Throwable t) { t.printStackTrace(); }
                    """.trimIndent()
                } else {
                    """
                    try {
                      Class<?> ss = Class.forName("com.baader.devrt.SnapshotStore");
                      java.lang.reflect.Method gm;
                      try { gm = ss.getMethod("get", String.class); }
                      catch (NoSuchMethodException ex) { gm = ss.getDeclaredMethod("get", String.class); gm.setAccessible(true); }
                      Object $name = gm.invoke(null, "$name");
                    } catch (Throwable t) { t.printStackTrace(); }
                    """.trimIndent()
                }

                WriteCommandAction.runWriteCommandAction(project) {
                    val doc = editor.document
                    doc.insertString(0, snippet)
                }
            }
        }

        // Edit Configurations action - opens Run Config tab
        val editConfigurations = object : AnAction("Edit Configurations", "Open Run Configurations tab", AllIcons.General.Settings) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
            override fun actionPerformed(e: AnActionEvent) {
                // Switch to Run Config tab
                val runConfigContent = toolWindow.contentManager.contents.find { it.tabName == "Run Config" }
                if (runConfigContent != null) {
                    toolWindow.contentManager.setSelectedContent(runConfigContent)
                }
            }
        }

        // Create toolbar (include Attach & Inject + Bind Spring actions here as well)
        val am = ActionManager.getInstance()
        val attachDevRuntime = am.getAction("hu.baader.repl.AttachDevRuntime")
        val bindSpringCtx = am.getAction("hu.baader.repl.BindSpringContext")

        // Quick Actions dropdown (Attach & Bind together)
        val quickActionsGroup = object : DefaultActionGroup("Quick Actions", true) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        }.apply {
            templatePresentation.icon = AllIcons.RunConfigurations.TestState.Run // Orange play icon

            // Combined Attach & Bind action
            val attachAndBind = object : AnAction("Attach & Bind manual", "Manually attach agent and bind Spring context", AllIcons.Actions.Execute) {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
                override fun actionPerformed(e: AnActionEvent) {
                    // First run the attach action
                    attachDevRuntime?.actionPerformed(e)
                    // Wait a bit for attachment to complete, then bind
                    ApplicationManager.getApplication().executeOnPooledThread {
                        Thread.sleep(1500)
                        ApplicationManager.getApplication().invokeLater {
                            // Then run the bind action
                            bindSpringCtx?.actionPerformed(e)
                        }
                    }
                }

                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = attachDevRuntime != null && bindSpringCtx != null
                }
            }
            add(attachAndBind)

            // Run template action - uses saved configuration from Run Config panel
            val runTemplate = object : AnAction("Run template", "Use saved configuration to connect", AllIcons.Actions.Lightning) {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
                override fun actionPerformed(e: AnActionEvent) {
                    // We'll store the RunConfigPanel reference when we create it later
                    ApplicationManager.getApplication().invokeLater {
                        // Switch to Run Config tab
                        val runConfigContent = toolWindow.contentManager.contents.find { it.tabName == "Run Config" }
                        if (runConfigContent != null) {
                            toolWindow.contentManager.setSelectedContent(runConfigContent)
                            // Trigger the start action after a small delay to ensure tab is visible
                            ApplicationManager.getApplication().invokeLater {
                                val runConfigPanel = runConfigContent.component
                                if (runConfigPanel is RunConfigPanel) {
                                    runConfigPanel.startFromTemplate()
                                }
                            }
                        }
                    }
                }
            }
            add(runTemplate)

            add(Separator.create())
            // Individual actions for more control
            attachDevRuntime?.let { add(it) }
            bindSpringCtx?.let { add(it) }
        }

        val group = DefaultActionGroup().apply {
            // Primary actions
            add(run)
            add(hotSwap)
            add(formatCode)
            add(beanHelper)
            add(Separator.create())

            // Quick Actions dropdown
            add(quickActionsGroup)
            add(Separator.create())

            // Connection actions
            add(editConfigurations)  // Edit Configurations at the beginning of connection section
            add(connect)
            add(disconnect)
            add(Separator.create())

            // Data management actions
            add(listSnapshots)
            add(bindSnapshot)
            add(Separator.create())

            // History and utility actions
            add(historyPrevAction)
            add(historyNextAction)
            add(showHistory)
            add(clearHistory)
            add(showLastError)
            add(clear)
        }

        val toolbar = am.createActionToolbar(
            "JavaReplToolbar",
            group,
            true
        )

        // Layout panel - editor and console split
        val split = Splitter(true, 0.65f).apply {
            setFirstComponent(JBScrollPane(editor.component))
            setSecondComponent(console.component)
        }

        // Panel for toolbar and content
        val panel = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.NORTH)
            add(split, BorderLayout.CENTER)
        }

        // Improve action context resolution
        toolbar.targetComponent = panel

        // History navigation with Up/Down arrows (when at first/last line)
        editor.contentComponent.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                val caret = editor.caretModel
                val doc = editor.document
                val lineCount = doc.lineCount
                val caretLine = caret.logicalPosition.line
                when (e.keyCode) {
                    KeyEvent.VK_UP -> {
                        if (caretLine == 0 && history.isNotEmpty()) {
                            loadHistoryEntry(-1)
                            e.consume()
                        }
                    }
                    KeyEvent.VK_DOWN -> {
                        if (caretLine == lineCount - 1) {
                            loadHistoryEntry(1)
                            e.consume()
                        }
                    }
                }
            }
        })

        // Alternative shortcuts: Ctrl+Up / Ctrl+Down for history
        val prevCmd = object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) {
                loadHistoryEntry(-1)
            }
        }
        prevCmd.registerCustomShortcutSet(CustomShortcutSet.fromString("ctrl UP"), editor.component)

        val nextCmd = object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) {
                loadHistoryEntry(1)
            }
        }
        nextCmd.registerCustomShortcutSet(CustomShortcutSet.fromString("ctrl DOWN"), editor.component)

        // Create a split pane for the main content
        val mainSplitter = Splitter(false, 0.8f) // Horizontal split, 80% for main area
        mainSplitter.setFirstComponent(panel) // Left side - original content

        val content = ContentFactory.getInstance().createContent(mainSplitter, "Session", false)
        // Ensure editor and console are disposed with this content
        content.setDisposer(Disposable {
            try {
                unsub.dispose()
            } catch (_: Throwable) {}
            try {
                EditorFactory.getInstance().releaseEditor(editor)
            } catch (_: Throwable) {}
        })
        Disposer.register(toolWindow.disposable) {
            try {
                unsub.dispose()
            } catch (_: Throwable) {}
            try {
                EditorFactory.getInstance().releaseEditor(editor)
            } catch (_: Throwable) {}
        }

        // Right side - loaded variables panel
        val loadedVariablesPanel = LoadedVariablesPanel(
            connection = { service.takeIf { it.isConnected() } },
            insertSnippet = { snippet ->
                WriteCommandAction.runWriteCommandAction(project) {
                    val document = editor.document
                    val offset = editor.caretModel.offset
                    document.insertString(offset, snippet)
                    editor.caretModel.moveToOffset(offset + snippet.length)
                }
            },
            executeCode = { code ->
                // Silent execution for variable injection
                if (service.isConnected()) {
                    service.evalJava(code)
                }
            }
        )

        val importAliasesPanel = ImportAliasesPanel()
        val sideSplitter = Splitter(true, 0.5f).apply {
            firstComponent = loadedVariablesPanel
            secondComponent = importAliasesPanel
        }

        // Add the variables + imports panel to the right side
        mainSplitter.setSecondComponent(sideSplitter)

        // Add the Session tab content - FIRST (main work area)
        toolWindow.contentManager.addContent(content)

        // Simplified Snapshots panel tab - SECOND (data management)
        val currentEditor = editor // capture reference for lambda
        val snapshotsPanel = SimplifiedSnapshotsPanel(
            connection = { service.takeIf { it.isConnected() } },
            insertSnippet = { snippet ->
                WriteCommandAction.runWriteCommandAction(project) {
                    val document = currentEditor.document
                    val offset = currentEditor.caretModel.offset
                    document.insertString(offset, snippet)
                    currentEditor.caretModel.moveToOffset(offset + snippet.length)
                }
            },
            onVariableLoaded = { name, value ->
                // When a snapshot is loaded, add it to the variables panel
                loadedVariablesPanel.addLoadedVariable(name, value)
            }
        )
        val snapshotsContent = ContentFactory.getInstance().createContent(snapshotsPanel, "Snapshots", false)
        toolWindow.contentManager.addContent(snapshotsContent)
        // Only reload if connected
        if (service.isConnected()) snapshotsPanel.refresh()

        // Run Configuration panel tab - THIRD/LAST (configuration management)
        val runConfigPanel = RunConfigPanel(project, connection = { service })
        val runConfigContent = ContentFactory.getInstance().createContent(runConfigPanel, "Run Config", false)
        toolWindow.contentManager.addContent(runConfigContent)
    }
}
