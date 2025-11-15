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
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
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
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.codeStyle.CodeStyleManager
import kotlin.text.Regex

class JavaReplToolWindowFactory : ToolWindowFactory, DumbAware {
    companion object {
        /**
         * First iteration: return null. Will be wired to a PSI-backed Java editor
         * plus transcript viewer in a later step.
         */
        fun createAdvancedEditorPanel(project: Project): JPanel? {
            // TODO: build Advanced Editor panel (session PSI + transcript)
            return null
        }
    }

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

        val replDocument = EditorFactory.getInstance().createDocument("")
        val replEditor = EditorFactory.getInstance().createEditor(
            replDocument,
            project,
            JavaFileType.INSTANCE,
            false
        ) as EditorEx

        val psiFile = PsiFileFactory.getInstance(project)
            .createFileFromText("SpringBootReplSession.java", JavaFileType.INSTANCE, "")
        val advancedDocument = PsiDocumentManager.getInstance(project)
            .getDocument(psiFile)
            ?: EditorFactory.getInstance().createDocument("")
        val advancedEditor = EditorFactory.getInstance().createEditor(
            advancedDocument,
            project,
            JavaFileType.INSTANCE,
            false
        ) as EditorEx

        fun configureEditorSettings(ed: EditorEx) {
            ed.settings.apply {
                isLineNumbersShown = true
                isIndentGuidesShown = true
                isCaretRowShown = true
                isFoldingOutlineShown = true
                setTabSize(4)
                isSmartHome = true
            }
        }

        configureEditorSettings(replEditor)
        configureEditorSettings(advancedEditor)

        val highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(
            project,
            JavaFileType.INSTANCE
        )
        replEditor.highlighter = highlighter
        advancedEditor.highlighter = highlighter

        var activeEditor: EditorEx = replEditor

        val historyService = ReplHistoryService.getInstance(project)
        val history = historyService.entries()
        var historyIndex = history.size
        val lastErrorBuffer = StringBuilder()
        var blockCounter = 0

        val service = NreplService.getInstance(project)

        // Last result viewer (single value, JSON-aware)
        val resultDocument = EditorFactory.getInstance().createDocument("")
        val resultEditor = EditorFactory.getInstance()
            .createViewer(resultDocument, project) as EditorEx
        resultEditor.apply {
            isRendererMode = true
            setCaretEnabled(false)
            settings.apply {
                isLineNumbersShown = true
                isFoldingOutlineShown = true
                isRightMarginShown = false
                isUseSoftWraps = true
            }
        }
        resultEditor.highlighter = EditorHighlighterFactory.getInstance()
            .createEditorHighlighter(project, JavaFileType.INSTANCE)

        // Transcript viewer (history of snippet + result blocks)
        val transcriptDocument = EditorFactory.getInstance().createDocument("")
        val transcriptEditor = EditorFactory.getInstance()
            .createViewer(transcriptDocument, project) as EditorEx
        transcriptEditor.apply {
            isRendererMode = true
            setCaretEnabled(false)
            settings.apply {
                isLineNumbersShown = true
                isFoldingOutlineShown = true
                isRightMarginShown = false
                isUseSoftWraps = true
            }
        }
        transcriptEditor.highlighter = EditorHighlighterFactory.getInstance()
            .createEditorHighlighter(project, JavaFileType.INSTANCE)

        // Panels for last result and log popups
        val lastResultPanel = JPanel(BorderLayout()).apply {
            add(JBScrollPane(resultEditor.component), BorderLayout.CENTER)
        }
        val logPanel = JPanel(BorderLayout()).apply {
            add(console.component, BorderLayout.CENTER)
        }

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
                val doc = activeEditor.document
                val offset = activeEditor.caretModel.offset
                doc.insertString(offset, snippet)
                activeEditor.caretModel.moveToOffset(offset + snippet.length)
            }
        }

        fun replaceEditorText(value: String) {
            WriteCommandAction.runWriteCommandAction(project) {
                replEditor.document.setText(value)
                replEditor.caretModel.moveToOffset(replEditor.document.textLength)
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
                val allItems = items.toList()
                val listModel = javax.swing.DefaultListModel<NreplService.BeanInfo>().apply {
                    allItems.forEach { addElement(it) }
                }
                val list = JList(listModel).apply {
                    cellRenderer = object : SimpleListCellRenderer<NreplService.BeanInfo>() {
                        override fun customize(
                            l: JList<out NreplService.BeanInfo>,
                            value: NreplService.BeanInfo?,
                            index: Int,
                            selected: Boolean,
                            hasFocus: Boolean
                        ) {
                            text = value?.let {
                                if (it.className.isBlank()) it.name else "${it.name} — ${it.className}"
                            } ?: ""
                        }
                    }
                }
                val searchField = javax.swing.JTextField()
                val panel = javax.swing.JPanel(java.awt.BorderLayout()).apply {
                    border = javax.swing.BorderFactory.createEmptyBorder(4, 4, 4, 4)
                    add(searchField, java.awt.BorderLayout.NORTH)
                    add(JBScrollPane(list), java.awt.BorderLayout.CENTER)
                }

                fun applyFilter() {
                    val query = searchField.text.trim().lowercase()
                    listModel.clear()
                    if (query.isEmpty()) {
                        allItems.forEach { listModel.addElement(it) }
                    } else {
                        allItems.filter {
                            it.name.lowercase().contains(query) ||
                                it.className.lowercase().contains(query)
                        }.forEach { listModel.addElement(it) }
                    }
                    if (!listModel.isEmpty) {
                        list.selectedIndex = 0
                    }
                }

                searchField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
                    override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = applyFilter()
                    override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = applyFilter()
                    override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = applyFilter()
                })

                fun chooseSelected(popup: com.intellij.openapi.ui.popup.JBPopup) {
                    val bean = list.selectedValue ?: return
                    insertBeanSnippet(bean)
                    popup.closeOk(null)
                }

                val popup = JBPopupFactory.getInstance()
                    .createComponentPopupBuilder(panel, searchField)
                    .setTitle("Insert Spring Bean Getter")
                    .setResizable(true)
                    .setMovable(true)
                    .setRequestFocus(true)
                    .setCancelOnClickOutside(true)
                    .createPopup()

                val width = activeEditor.component.width.coerceAtLeast(400)
                val height = (activeEditor.component.height * 0.5).toInt().coerceAtLeast(200)
                popup.setSize(java.awt.Dimension(width, height))

                list.addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent) {
                        if (e.clickCount == 2 && list.selectedValue != null) {
                            chooseSelected(popup)
                        }
                    }
                })
                searchField.addActionListener {
                    if (!listModel.isEmpty) {
                        chooseSelected(popup)
                    }
                }

                applyFilter()
                popup.showInBestPositionFor(activeEditor)
                searchField.requestFocusInWindow()
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

        service.setDebugSink { line ->
            console.print("$line\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        }

        val unsub = service.onMessage { msg ->
            val out = msg["out"]
            if (!out.isNullOrBlank()) {
                console.print(out, ConsoleViewContentType.NORMAL_OUTPUT)
            }
            val value = msg["value"]
            if (value != null) {
                val jsonFormatted = prettyPrintJsonIfLikely(value)
                val formatted = jsonFormatted ?: value
                if (formatted.contains('\n')) {
                    console.print("\n=>\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                    console.print(formatted + "\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                } else {
                    console.print("\n=> $formatted\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                }
                ApplicationManager.getApplication().invokeLater {
                    WriteCommandAction.runWriteCommandAction(project) {
                        resultDocument.setText(formatted)
                        val snippet = service.consumeLastEvalSnippet()?.trimEnd()
                        if (!snippet.isNullOrEmpty()) {
                            val doc = transcriptDocument
                            blockCounter += 1
                            val header = ">>\n"
                            val body = snippet + "\n"
                            val arrow = "=>\n"
                            val valueText = formatted + "\n\n"
                            val blockText = header + body + arrow + valueText

                            val start = doc.textLength
                            doc.insertString(start, blockText)
                            val end = doc.textLength
                            val headerEnd = start + header.length

                            transcriptEditor.foldingModel.runBatchFoldingOperation {
                                val foldingModel = transcriptEditor.foldingModel
                                val region = if (headerEnd < end) {
                                    foldingModel.addFoldRegion(headerEnd, end, "...")
                                } else null

                                val regions = foldingModel.allFoldRegions.sortedBy { it.startOffset }
                                val keepOpen = 5
                                regions.forEachIndexed { index, r ->
                                    r.isExpanded = index >= regions.size - keepOpen
                                }
                            }

                            // Highlight snippet background within the block
                            val snippetStart = start + header.length
                            val snippetEnd = snippetStart + body.length
                            if (snippetEnd <= doc.textLength) {
                                val attrs = TextAttributes(
                                    null,
                                    JBColor(Color(0xFFF8E1), Color(0x2A2110)),
                                    null,
                                    null,
                                    Font.PLAIN
                                )
                                transcriptEditor.markupModel.addRangeHighlighter(
                                    snippetStart,
                                    snippetEnd,
                                    HighlighterLayer.ADDITIONAL_SYNTAX,
                                    attrs,
                                    HighlighterTargetArea.EXACT_RANGE
                                )
                            }

                            transcriptEditor.caretModel.moveToOffset(doc.textLength)
                            transcriptEditor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
                        }
                    }
                    updateResultHighlighter(project, resultEditor, jsonFormatted != null)
                }
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
                val rawText = activeEditor.selectionModel.selectedText ?: activeEditor.document.text
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
        run.registerCustomShortcutSet(CustomShortcutSet.fromString("ctrl ENTER"), replEditor.component)
        run.registerCustomShortcutSet(CustomShortcutSet.fromString("ctrl ENTER"), advancedEditor.component)

        val hotSwap = object : AnAction("Hot Swap", "Compile and reload selected class into target JVM", AllIcons.Actions.BuildLoadChanges) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
            override fun actionPerformed(e: AnActionEvent) {
                val text = activeEditor.selectionModel.selectedText?.takeIf { it.isNotBlank() } ?: activeEditor.document.text
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
                val documentRef = activeEditor.document
                val selectionModel = activeEditor.selectionModel
                val selectedText = selectionModel.selectedText
                val rangeStart = selectionModel.selectionStart
                val rangeEnd = selectionModel.selectionEnd
                val originalSnippet = (selectedText ?: documentRef.text) ?: return
                if (originalSnippet.isBlank()) return

                WriteCommandAction.runWriteCommandAction(projectRef) {
                    try {
                        val wrapped = buildString {
                            append("class ReplWrapper {\n")
                            append("  void run() {\n")
                            append(originalSnippet)
                            if (!originalSnippet.endsWith("\n")) append("\n")
                            append("  }\n")
                            append("}\n")
                        }

                        val psiFile = PsiFileFactory.getInstance(projectRef)
                            .createFileFromText("ReplWrapper.java", JavaFileType.INSTANCE, wrapped)
                        CodeStyleManager.getInstance(projectRef).reformat(psiFile)

                        val psiClass = psiFile.children.firstOrNull { it is com.intellij.psi.PsiClass } as? com.intellij.psi.PsiClass
                        val runMethod = psiClass?.methods?.firstOrNull { it.name == "run" }
                        val body = runMethod?.body
                        val formattedBody = body?.statements
                            ?.joinToString(separator = "\n") { it.text }
                            ?.trimEnd()
                            ?: originalSnippet

                        if (!selectedText.isNullOrEmpty()) {
                            documentRef.replaceString(rangeStart, rangeEnd, formattedBody)
                        } else {
                            documentRef.setText(formattedBody)
                        }
                    } catch (ex: Exception) {
                        console.print("Format failed: ${ex.message}\n", ConsoleViewContentType.ERROR_OUTPUT)
                    }
                }
            }
        }
        formatCode.registerCustomShortcutSet(CustomShortcutSet.fromString("ctrl alt L"), replEditor.component)
        formatCode.registerCustomShortcutSet(CustomShortcutSet.fromString("ctrl alt L"), advancedEditor.component)

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
                            replEditor.document.setText(it)
                            replEditor.caretModel.moveToOffset(replEditor.document.textLength)
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

        val showLastResultPopup = object : AnAction("Last Result", "Show last result viewer", AllIcons.Actions.Preview) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
            override fun actionPerformed(e: AnActionEvent) {
                JBPopupFactory.getInstance()
                    .createComponentPopupBuilder(lastResultPanel, null)
                    .setTitle("Last Result")
                    .setResizable(true)
                    .setMovable(true)
                    .setRequestFocus(true)
                    .createPopup()
                    .showInBestPositionFor(activeEditor)
            }
        }

        val showLogPopup = object : AnAction("Log", "Show log console", AllIcons.Debugger.Console) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
            override fun actionPerformed(e: AnActionEvent) {
                JBPopupFactory.getInstance()
                    .createComponentPopupBuilder(logPanel, null)
                    .setTitle("Log")
                    .setResizable(true)
                    .setMovable(true)
                    .setRequestFocus(true)
                    .createPopup()
                    .showInBestPositionFor(activeEditor)
            }
        }

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
            add(Separator.create())
            add(showLastResultPopup)
            add(showLogPopup)
        }

        val topToolbar = am.createActionToolbar("JavaReplTopToolbar", topActionGroup, true)
        val httpRunner = HttpRequestRunner(project, console)
        
        val bottomActionGroup = DefaultActionGroup().apply {
            add(formatCode)
            add(run)
        }
        val bottomToolbar = am.createActionToolbar("JavaReplBottomToolbar", bottomActionGroup, true)
        bottomToolbar.targetComponent = replEditor.component
        val bottomButtonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            add(bottomToolbar.component)
        }
        val cardPanel = JPanel(CardLayout())
        lateinit var httpQuickPanel: HttpQuickActionsPanel
        val editorStackLayout = CardLayout()
        val editorStack = JPanel(editorStackLayout).apply {
            add(JBScrollPane(replEditor.component), "repl")
            add(JBScrollPane(advancedEditor.component), "advanced")
        }
        editorStackLayout.show(editorStack, "repl")

        val httpCard = JPanel(BorderLayout()).apply {
            httpQuickPanel = HttpQuickActionsPanel(project, httpRunner) { expanded ->
                val layout = cardPanel.layout as CardLayout
                if (expanded) layout.show(cardPanel, "http") else layout.show(cardPanel, "editor")
            }
            add(httpQuickPanel, BorderLayout.CENTER)
        }
        val bottomBar = JPanel(BorderLayout()).apply {
            val modePanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                val replDot = javax.swing.JLabel("●")
                val replLabel = com.intellij.ui.components.labels.LinkLabel<String>("REPL", null)
                val advDot = javax.swing.JLabel("●")
                val advLabel = com.intellij.ui.components.labels.LinkLabel<String>("Advanced", null)

                fun updateDots() {
                    if (activeEditor === replEditor) {
                        replDot.foreground = JBColor.GREEN
                        replLabel.foreground = JBColor.GREEN
                        advDot.foreground = JBColor.GRAY
                        advLabel.foreground = JBColor.GRAY
                    } else {
                        replDot.foreground = JBColor.GRAY
                        replLabel.foreground = JBColor.GRAY
                        advDot.foreground = JBColor.GREEN
                        advLabel.foreground = JBColor.GREEN
                    }
                }

                replLabel.setListener({ _, _ ->
                    activeEditor = replEditor
                    editorStackLayout.show(editorStack, "repl")
                    bottomToolbar.targetComponent = replEditor.component
                    updateDots()
                }, null)

                advLabel.setListener({ _, _ ->
                    activeEditor = advancedEditor
                    editorStackLayout.show(editorStack, "advanced")
                    bottomToolbar.targetComponent = advancedEditor.component
                    updateDots()
                }, null)

                add(replDot)
                add(replLabel)
                add(advDot)
                add(advLabel)
                updateDots()
            }

            val httpLink = com.intellij.ui.components.labels.LinkLabel<String>("HTTP", null).apply {
                setListener({ _, _ -> httpQuickPanel.expandPanel() }, null)
            }
            httpLink.toolTipText = "HTTP esetek megnyitása"
            httpLink.border = BorderFactory.createEmptyBorder(0, 8, 0, 0)

            val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                add(modePanel)
                add(httpLink)
            }

            add(leftPanel, BorderLayout.WEST)
            add(bottomButtonPanel, BorderLayout.EAST)
        }

        val editorCard = JPanel(BorderLayout()).apply {
            add(editorStack, BorderLayout.CENTER)
            add(bottomBar, BorderLayout.SOUTH)
        }
        cardPanel.add(editorCard, "editor")
        cardPanel.add(httpCard, "http")
        (cardPanel.layout as CardLayout).show(cardPanel, "editor")

        val editorPanel = cardPanel

        val transcriptPanel = JPanel(BorderLayout()).apply {
            add(JBScrollPane(transcriptEditor.component), BorderLayout.CENTER)
        }

        val replSplit = Splitter(true, 0.6f).apply {
            firstComponent = transcriptPanel
            secondComponent = editorPanel
        }

        val mainPanel = JPanel(BorderLayout()).apply {
            add(topToolbar.component, BorderLayout.NORTH)
            add(replSplit, BorderLayout.CENTER)
        }
        topToolbar.targetComponent = mainPanel

        replEditor.contentComponent.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                val caret = replEditor.caretModel
                val doc = replEditor.document
                val lineCount = doc.lineCount
                val caretLine = caret.logicalPosition.line
                when (e.keyCode) {
                    KeyEvent.VK_UP -> if (caretLine == 0 && history.isNotEmpty()) { loadHistoryEntry(-1); e.consume() }
                    KeyEvent.VK_DOWN -> if (caretLine == lineCount - 1) { loadHistoryEntry(1); e.consume() }
                }
            }
        })

        val prevCmd = object : AnAction() { override fun actionPerformed(e: AnActionEvent) { loadHistoryEntry(-1) } }
        prevCmd.registerCustomShortcutSet(CustomShortcutSet.fromString("ctrl UP"), replEditor.component)

        val nextCmd = object : AnAction() { override fun actionPerformed(e: AnActionEvent) { loadHistoryEntry(1) } }
        nextCmd.registerCustomShortcutSet(CustomShortcutSet.fromString("ctrl DOWN"), replEditor.component)

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

        val replContent = ContentFactory.getInstance().createContent(mainSplitter, "REPL", false)
        replContent.setDisposer(Disposable {
            try { unsub.dispose() } catch (_: Throwable) {}
            try { EditorFactory.getInstance().releaseEditor(replEditor) } catch (_: Throwable) {}
            try { EditorFactory.getInstance().releaseEditor(advancedEditor) } catch (_: Throwable) {}
            try { EditorFactory.getInstance().releaseEditor(resultEditor) } catch (_: Throwable) {}
            try { EditorFactory.getInstance().releaseEditor(transcriptEditor) } catch (_: Throwable) {}
            try { httpQuickPanel.dispose() } catch (_: Throwable) {}
            try { service.setDebugSink(null) } catch (_: Throwable) {}
        })

        val insertIntoEditor: (String) -> Unit = { snippet ->
            WriteCommandAction.runWriteCommandAction(project) {
                val editorDocument = activeEditor.document
                val offset = activeEditor.caretModel.offset
                editorDocument.insertString(offset, snippet)
                activeEditor.caretModel.moveToOffset(offset + snippet.length)
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
        
        toolWindow.contentManager.addContent(replContent)

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

        // Enable soft wraps in console editor on EDT once the UI is ready
        ApplicationManager.getApplication().invokeLater {
            consoleImpl.editor?.settings?.isUseSoftWraps = true
        }
    }

    private fun prettyPrintJsonIfLikely(raw: String?): String? {
        val original = raw?.trim() ?: return null
        if (original.length < 2) return null

        // If value is a quoted JSON string, strip outer quotes and unescape basic sequences
        val s = if (original.first() == '"' && original.last() == '"') {
            val inner = original.substring(1, original.length - 1)
            buildString {
                var escape = false
                for (ch in inner) {
                    if (escape) {
                        when (ch) {
                            '\\' -> append('\\')
                            '"' -> append('"')
                            'n' -> append('\n')
                            'r' -> append('\r')
                            't' -> append('\t')
                            else -> append(ch)
                        }
                        escape = false
                    } else if (ch == '\\') {
                        escape = true
                    } else {
                        append(ch)
                    }
                }
            }.trim()
        } else {
            original
        }

        if (s.length < 2) return null
        val first = s.first()
        val last = s.last()
        if (!((first == '{' && last == '}') || (first == '[' && last == ']'))) return null
        // Heuristic: must look like real JSON (have ':' or quoted strings)
        if (!s.contains(':') && !s.contains('"')) return null

        val sb = StringBuilder()
        var indent = 0
        var inString = false
        var escape = false

        fun appendIndent() {
            repeat(indent) { sb.append("  ") }
        }

        for (ch in s) {
            when {
                inString -> {
                    sb.append(ch)
                    if (escape) {
                        escape = false
                    } else if (ch == '\\') {
                        escape = true
                    } else if (ch == '"') {
                        inString = false
                    }
                }
                ch == '"' -> {
                    inString = true
                    sb.append(ch)
                }
                ch == '{' || ch == '[' -> {
                    sb.append(ch)
                    sb.append('\n')
                    indent++
                    appendIndent()
                }
                ch == '}' || ch == ']' -> {
                    sb.append('\n')
                    indent--
                    if (indent < 0) indent = 0
                    appendIndent()
                    sb.append(ch)
                }
                ch == ',' -> {
                    sb.append(ch)
                    sb.append('\n')
                    appendIndent()
                }
                ch == ':' -> {
                    sb.append(": ")
                }
                ch.isWhitespace() -> {
                    // skip
                }
                else -> {
                    sb.append(ch)
                }
            }
        }

        return sb.toString()
    }

    private fun updateResultHighlighter(project: Project, editor: EditorEx, isJson: Boolean) {
        val factory = EditorHighlighterFactory.getInstance()
        val highlighter = if (isJson) {
            try {
                val clazz = Class.forName("com.intellij.json.JsonFileType")
                val instanceField = clazz.getField("INSTANCE")
                val fileType = instanceField.get(null) as com.intellij.openapi.fileTypes.FileType
                factory.createEditorHighlighter(project, fileType)
            } catch (_: Throwable) {
                factory.createEditorHighlighter(project, JavaFileType.INSTANCE)
            }
        } else {
            factory.createEditorHighlighter(project, JavaFileType.INSTANCE)
        }
        editor.highlighter = highlighter
    }
}
