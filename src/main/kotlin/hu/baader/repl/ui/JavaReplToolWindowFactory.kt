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
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.editor.EditorSettings
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBScrollPane
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.util.Disposer
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.JBColor
import hu.baader.repl.nrepl.NreplService
import hu.baader.repl.history.ReplHistoryService
import javax.swing.JPanel
import java.awt.BorderLayout
import java.awt.CardLayout
import com.intellij.icons.AllIcons
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.command.WriteCommandAction
import hu.baader.repl.settings.PluginSettingsState
import java.awt.FlowLayout
import javax.swing.JList
import javax.swing.BorderFactory
import java.awt.Font
import java.awt.Color
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.codeStyle.CodeStyleManager
import kotlin.text.Regex

class JavaReplToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val consoleImpl = ConsoleViewImpl(project, true)
        val console: ConsoleView = consoleImpl
        Disposer.register(toolWindow.disposable, console)

        val codeSnippetContentType = ConsoleViewContentType(
            "REPL_CODE_SNIPPET",
            TextAttributes(
                JBColor(Color(0x3A3A3A), Color(0xDDDDDD)),
                JBColor(Color(0xF8F8FA), Color(0x2F2F2F)),
                null,
                null,
                Font.PLAIN
            )
        )

        val document = EditorFactory.getInstance().createDocument("")
        val editor = EditorFactory.getInstance().createEditor(
            document,
            project,
            JavaFileType.INSTANCE,
            false
        ) as EditorEx

        editor.settings.apply {
            isLineNumbersShown = true
            isIndentGuidesShown = true
            isCaretRowShown = true
            isFoldingOutlineShown = true
            setTabSize(4)
            isSmartHome = true
        }

        val highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(
            project,
            JavaFileType.INSTANCE
        )
        editor.highlighter = highlighter

        val historyService = ReplHistoryService.getInstance(project)
        val history = historyService.entries()
        var historyIndex = history.size
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
            val snippet = "var $varName = com.baader.devrt.ReplBindings.applicationContext() != null ? ((org.springframework.context.ApplicationContext) com.baader.devrt.ReplBindings.applicationContext()).getBean($targetClass.class) : null;\n"
            WriteCommandAction.runWriteCommandAction(project) {
                val doc = editor.document
                val offset = editor.caretModel.offset
                doc.insertString(offset, snippet)
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
                val pattern = Regex("(?<![\\w$])" + Regex.escape(alias.alias) + "(?![\\w$])")
                updated = pattern.replace(updated, alias.fqn)
            }
            return updated
        }

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
                lastErrorBuffer.append(err)
            }
            val ex = msg["ex"]
            if (ex != null) {
                console.print("Exception: $ex\n", ConsoleViewContentType.ERROR_OUTPUT)
            }
        }

        val run = object : AnAction("Execute", "Execute selected code or entire document (Ctrl+Enter)", AllIcons.Actions.Execute) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
            override fun actionPerformed(e: AnActionEvent) {
                val rawText = editor.selectionModel.selectedText ?: editor.document.text
                if (rawText.isNotBlank()) {
                    var text = rawText
                    try {
                        console.print("\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                        console.print(">> Executing Java code...\n", codeSnippetContentType)
                        historyService.add(rawText)
                        historyIndex = history.size
                        lastErrorBuffer.setLength(0)
                        text = applyImportAliases(text)
                        val snippetForDisplay = text.trim()
                        if (snippetForDisplay.isNotEmpty()) {
                            val decoratedSnippet = buildString {
                                append("\n")
                                append(snippetForDisplay)
                                if (!snippetForDisplay.endsWith("\n")) append("\n")
                                append("\n")
                            }
                            console.print(decoratedSnippet, codeSnippetContentType)
                        }
                        service.eval(text)
                    } catch (ex: Exception) {
                        console.print("Error: ${ex.message}\n", ConsoleViewContentType.ERROR_OUTPUT)
                    }
                }
            }
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = service.isConnected()
            }
        }
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

        val formatCode = object : AnAction("Format Code", "Format Java code", AllIcons.Actions.ReformatCode) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
            override fun actionPerformed(e: AnActionEvent) {
                val projectRef = e.project ?: project
                val documentRef = editor.document
                WriteCommandAction.runWriteCommandAction(projectRef) {
                    try {
                        val psiFile = PsiFileFactory.getInstance(projectRef)
                            .createFileFromText("ReplSnippet.java", JavaFileType.INSTANCE, documentRef.text)
                        CodeStyleManager.getInstance(projectRef).reformat(psiFile)
                        documentRef.setText(psiFile.text)
                    } catch (ex: Exception) {
                        console.print("Format failed: ${ex.message}\n", ConsoleViewContentType.ERROR_OUTPUT)
                    }
                }
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

        val connect = object : AnAction("Connect", "Connect to nREPL server", AllIcons.Actions.Run_anything) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
            override fun actionPerformed(e: AnActionEvent) {
                try {
                    console.print("Connecting to nREPL server...\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                    service.connectAsync { isJshell ->
                        ApplicationManager.getApplication().invokeLater {
                            console.print("Connected successfully!\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                            if (isJshell) {
                                console.print("Mode: JShell session (stateful imports & defs)\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                                console.print("Automatically binding Spring context...\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                                service.bindSpring(
                                    onResult = { msg ->
                                        console.print("Auto-bind successful: $msg\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                                        try {
                                            val snippet = """
                                                var ctx = (org.springframework.context.ApplicationContext) com.baader.devrt.ReplBindings.applicationContext();
                                            """.trimIndent()
                                            service.eval(snippet)
                                            console.print("Spring context variable 'ctx' initialized in JShell session.\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                                        } catch (ex: Exception) {
                                            console.print("Failed to initialize ctx: ${ex.message}\n", ConsoleViewContentType.ERROR_OUTPUT)
                                        }
                                    },
                                    onError = { err -> console.print("Auto-bind failed: $err\n", ConsoleViewContentType.ERROR_OUTPUT) }
                                )
                            } else {
                                console.print("Mode: Legacy (java-eval). For values, use 'return ...;\n'", ConsoleViewContentType.SYSTEM_OUTPUT)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    console.print("Connect failed: ${t.message}\n", ConsoleViewContentType.ERROR_OUTPUT)
                }
            }
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = !service.isConnected()
            }
        }

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
                    .setItemChosenCallback {
                        WriteCommandAction.runWriteCommandAction(project) {
                            editor.document.setText(it)
                            editor.caretModel.moveToOffset(editor.document.textLength)
                            historyIndex = history.indexOf(it) + 1
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
        
        val resetSession = object : AnAction("Reset Session", "Reset JShell session (imports and definitions)", AllIcons.Actions.Rollback) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
            override fun actionPerformed(e: AnActionEvent) {
                service.resetSession(
                    onResult = { console.print("Session reset.\n", ConsoleViewContentType.SYSTEM_OUTPUT) },
                    onError = { err -> console.print("Reset failed: $err\n", ConsoleViewContentType.ERROR_OUTPUT) }
                )
            }
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = service.isConnected() }
        }

        val am = ActionManager.getInstance()
        val bindSpringCtx = am.getAction("hu.baader.repl.BindSpringContext")

        val topActionGroup = DefaultActionGroup().apply {
            add(hotSwap)
            add(Separator.create())
            add(connect)
            add(disconnect)
            bindSpringCtx?.let { add(it) }
            add(beanHelper)
            add(resetSession) // Keep session reset at the top
            add(Separator.create())
            add(historyPrevAction)
            add(historyNextAction)
            add(showHistory)
            add(clearHistory)
            add(showLastError)
            add(clear)
        }

        val topToolbar = am.createActionToolbar("JavaReplTopToolbar", topActionGroup, true)
        val httpRunner = HttpRequestRunner(project, console)
        
        val bottomActionGroup = DefaultActionGroup().apply {
            add(formatCode)
            add(run)
        }
        val bottomToolbar = am.createActionToolbar("JavaReplBottomToolbar", bottomActionGroup, true)
        bottomToolbar.targetComponent = editor.component
        val bottomButtonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            add(bottomToolbar.component)
        }
        val cardPanel = JPanel(CardLayout())
        lateinit var httpQuickPanel: HttpQuickActionsPanel
        val httpCard = JPanel(BorderLayout()).apply {
            httpQuickPanel = HttpQuickActionsPanel(project, httpRunner) { expanded ->
                val layout = cardPanel.layout as CardLayout
                if (expanded) layout.show(cardPanel, "http") else layout.show(cardPanel, "editor")
            }
            add(httpQuickPanel, BorderLayout.CENTER)
        }
        val bottomBar = JPanel(BorderLayout()).apply {
            val httpLink = com.intellij.ui.components.labels.LinkLabel<String>("HTTP", null).apply {
                setListener({ _, _ -> httpQuickPanel.expandPanel() }, null)
            }
            httpLink.toolTipText = "HTTP esetek megnyitása"
            httpLink.border = BorderFactory.createEmptyBorder(0, 8, 0, 0)
            add(httpLink, BorderLayout.WEST)
            add(bottomButtonPanel, BorderLayout.EAST)
        }
        val editorCard = JPanel(BorderLayout()).apply {
            add(JBScrollPane(editor.component), BorderLayout.CENTER)
            add(bottomBar, BorderLayout.SOUTH)
        }
        cardPanel.add(editorCard, "editor")
        cardPanel.add(httpCard, "http")
        (cardPanel.layout as CardLayout).show(cardPanel, "editor")

        val editorPanel = cardPanel

        val mainConsoleSplit = Splitter(true, 0.75f).apply {
            firstComponent = console.component
            secondComponent = editorPanel
        }

        val mainPanel = JPanel(BorderLayout()).apply {
            add(topToolbar.component, BorderLayout.NORTH)
            add(mainConsoleSplit, BorderLayout.CENTER)
        }
        topToolbar.targetComponent = mainPanel

        editor.contentComponent.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                val caret = editor.caretModel
                val doc = editor.document
                val lineCount = doc.lineCount
                val caretLine = caret.logicalPosition.line
                when (e.keyCode) {
                    KeyEvent.VK_UP -> if (caretLine == 0 && history.isNotEmpty()) { loadHistoryEntry(-1); e.consume() }
                    KeyEvent.VK_DOWN -> if (caretLine == lineCount - 1) { loadHistoryEntry(1); e.consume() }
                }
            }
        })

        val prevCmd = object : AnAction() { override fun actionPerformed(e: AnActionEvent) { loadHistoryEntry(-1) } }
        prevCmd.registerCustomShortcutSet(CustomShortcutSet.fromString("ctrl UP"), editor.component)

        val nextCmd = object : AnAction() { override fun actionPerformed(e: AnActionEvent) { loadHistoryEntry(1) } }
        nextCmd.registerCustomShortcutSet(CustomShortcutSet.fromString("ctrl DOWN"), editor.component)

        val mainSplitter = Splitter(false, 0.8f)
        mainSplitter.setFirstComponent(mainPanel)

        val toggleSidePanelAction = object : ToggleAction("Toggle Variables Panel", "Show/Hide the variables panel", AllIcons.Actions.SplitVertically) {
            override fun isSelected(e: AnActionEvent): Boolean = mainSplitter.secondComponent?.isVisible ?: false
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                mainSplitter.secondComponent?.isVisible = state
                mainSplitter.proportion = if (state) 0.8f else 1.0f
            }
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        }
        topActionGroup.add(Separator.create(), Constraints.LAST)
        topActionGroup.add(toggleSidePanelAction, Constraints.LAST)

        val sessionContent = ContentFactory.getInstance().createContent(mainSplitter, "REPL", false)
        sessionContent.setDisposer(Disposable { 
            try { unsub.dispose() } catch (_: Throwable) {}
            try { EditorFactory.getInstance().releaseEditor(editor) } catch (_: Throwable) {}
            try { httpQuickPanel.dispose() } catch (_: Throwable) {}
        })
        
        val insertIntoEditor: (String) -> Unit = { snippet ->
            WriteCommandAction.runWriteCommandAction(project) {
                val editorDocument = editor.document
                val offset = editor.caretModel.offset
                editorDocument.insertString(offset, snippet)
                editor.caretModel.moveToOffset(offset + snippet.length)
            }
        }

        val loadedVariablesPanel = LoadedVariablesPanel(
            connection = { service.takeIf { it.isConnected() } },
            insertSnippet = insertIntoEditor,
            executeCode = { code ->
                if (service.isConnected()) {
                    service.eval(code)
                }
            }
        )
        mainSplitter.setSecondComponent(loadedVariablesPanel)
        loadedVariablesPanel.isVisible = false
        mainSplitter.proportion = 1.0f
        
        toolWindow.contentManager.addContent(sessionContent)

        val snapshotsPanel = SimplifiedSnapshotsPanel(
            connection = { service.takeIf { it.isConnected() } },
            insertSnippet = insertIntoEditor,
            onVariableLoaded = { name, value ->
                loadedVariablesPanel.addLoadedVariable(name, value)
            }
        )
        val snapshotsContent = ContentFactory.getInstance().createContent(snapshotsPanel, "Snapshots", false)
        toolWindow.contentManager.addContent(snapshotsContent)

        val httpPanel = HttpRequestsPanel(
            project = project,
            insertSnippet = insertIntoEditor,
            runner = httpRunner,
            onCasesChanged = { httpQuickPanel.refreshCases() }
        )
        val httpContent = ContentFactory.getInstance().createContent(httpPanel, "HTTP", false)
        toolWindow.contentManager.addContent(httpContent)
    }
}
