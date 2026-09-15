package hu.baader.repl.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import hu.baader.repl.nrepl.NreplService
import hu.baader.repl.workflow.CaseWorkflow
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ui.Messages
import java.awt.GridLayout
import java.awt.BorderLayout
import javax.swing.*
import javax.swing.table.DefaultTableModel

class SnapshotCasesPanel(private val project: Project, private val service: NreplService,
                         private val inspect: (Map<String,String>) -> Unit) : JPanel(BorderLayout()), Disposable {
    private val model = object : DefaultTableModel(arrayOf("Case", "Outcome", "ms"), 0) { override fun isCellEditable(row: Int, column: Int) = false }
    private val table = JTable(model)
    private val name = JTextField(16)
    private val input = JComboBox<String>()
    private val expected = JComboBox<String>()
    private val type = JTextField(28)
    private val variable = JTextField("input", 12)
    private val expectedException = JTextField(24)
    private val expectedMessage = JTextField(24)
    private val source = JTextArea("// Use input and ctx; return the value to compare.\ninput", 7, 70)
    private val resultExpression = JTextField(30)
    private val assertions = JTextArea("{}", 9, 60)
    private val parameters = JTextArea("", 8, 60)
    private val setupCode = JTextArea("", 4, 60)
    private val teardownCode = JTextArea("", 4, 60)
    private val importsCode = JTextArea("", 3, 60)
    private val tags = JTextField(20)
    private val disabled = JCheckBox("Disabled")
    private val maxDuration = JTextField(8)
    private val report = JTextArea().apply { isEditable = false; lineWrap = true }
    private val status = JLabel("Save a case, then explicitly run it against the application. Service calls can have side effects.")
    private val reloadLabel = JLabel("No Java source selected for reload")
    private var reloadDocument: Document? = null
    private var reloadFile: VirtualFile? = null
    private var loadingSource = false
    private val outcomes = linkedMapOf<String, Map<String,String>>()
    private var generation = 0
    private var disposed = false
    private val workflow = CaseWorkflow(CaseWorkflow.Transport { op, fields, ok, failure -> service.request(op, fields, ok, failure) },
        { status.text = it }, { case, result ->
            outcomes[case] = result
            for (row in 0 until model.rowCount) if (model.getValueAt(row, 0) == case) {
                model.setValueAt(result["outcome"], row, 1); model.setValueAt(result["duration-ms"], row, 2)
            }
            showResult(case, result)
        }, {})
    private val listener = service.onMessage {
        if (it["op"] == "session/reset" || it["op"] == "connection" && it["state"] in setOf("CONNECTING", "DISCONNECTED", "FAILED")) {
            generation++; workflow.invalidate(); loadingSource = false; outcomes.clear(); model.rowCount = 0; report.text = ""
        }
    }
    init {
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
        val fields = WorkbookToolbar()
        fields.add(JLabel("Case name:")); fields.add(name); fields.add(JLabel("Input DATA:")); fields.add(input)
        fields.add(JLabel("Expected DATA:")); fields.add(expected); fields.add(JLabel("Variable:")); fields.add(variable)
        fields.add(JLabel("Optional input type:")); fields.add(type)
        fields.add(JLabel("Expected exception (empty = compare DATA):")); fields.add(expectedException)
        fields.add(JLabel("Exact exception message:")); fields.add(expectedMessage)
        fields.add(JLabel("Result expression (optional; required for JUnit):")); fields.add(resultExpression)
        val controls = WorkbookToolbar()
        fun button(label: String, action: () -> Unit) { controls.add(JButton(label).apply { addActionListener { action() } }) }
        button("Refresh", ::refresh)
        button("Save case") {
            if (workflow.busy || loadingSource) { error("Wait for the running workflow before editing cases"); return@button }
            service.request("case/save", mapOf("name" to name.text.trim(), "input" to input.selectedItem?.toString().orEmpty(),
                "expected" to expected.selectedItem?.toString().orEmpty(), "type" to type.text.trim(), "variable" to variable.text.trim(), "code" to source.text, "expected-exception" to expectedException.text.trim(), "expected-message" to expectedMessage.text,
                "result-expression" to resultExpression.text, "assertions-json" to assertions.text, "parameters-json" to parameters.text,
                "setup" to setupCode.text, "teardown" to teardownCode.text, "imports" to importsCode.text,
                "tags" to tags.text.trim(), "disabled" to disabled.isSelected.toString(), "max-duration-ms" to maxDuration.text.trim()),
                { status.text = it["value"]; outcomes.remove(name.text.trim()); refresh() }, ::error)
        }
        button("Load selected", ::loadSelected)
        button("Export JUnit ZIP", ::exportJUnit)
        button("Run saved cases") { run(selectedNames()) }
        button("Rerun failed") { run(outcomes.filterValues { it["outcome"] in setOf("FAILED", "ERROR", "INCONCLUSIVE") }.keys.toList()) }
        button("Stop") {
            if (loadingSource) { generation++; loadingSource = false; status.text = "Stopped before code reload" }
            else workflow.cancel()
        }
        button("Inspect actual") {
            selectedNames().firstOrNull()?.let { selected -> outcomes[selected]?.get("event")?.let { inspect(mapOf("event" to it)) } }
        }
        button("Use open Java editor") {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            val file = editor?.let { PsiDocumentManager.getInstance(project).getPsiFile(it.document) }
            if (editor == null || file !is PsiJavaFile || file.virtualFile?.extension != "java") error("Open the application's Java source in the main editor first")
            else { reloadDocument = editor.document; reloadFile = file.virtualFile; reloadLabel.text = "Reload: ${file.name} · latest editor contents, including unsaved changes" }
        }
        button("Choose Java file") {
            FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFileDescriptor("java"), project, null)?.let { file ->
                reloadDocument = null; reloadFile = file; reloadLabel.text = "Reload: ${file.name} · latest contents at each run"
            }
        }
        button("Reload + run selected", ::reloadAndRun)
        table.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) selectedNames().firstOrNull()?.let { selected ->
            outcomes[selected]?.let { value -> showResult(selected, value) }
        } }
        fun editor(label: String, area: JTextArea) = JPanel(BorderLayout()).apply {
            add(JTextArea(label).apply { isEditable = false; isOpaque = false; lineWrap = true; wrapStyleWord = true }, BorderLayout.NORTH)
            add(JScrollPane(area), BorderLayout.CENTER)
        }
        val codeTabs = JTabbedPane().apply {
            addTab("Code", JScrollPane(source))
            addTab("Assertions", editor("JSON options: include / ignore / unordered paths, numericTolerance / timeToleranceMs, checks. Paths use /items/*/id; empty path means root. Example: {\"ignore\":[\"/id\"],\"numericTolerance\":{\"/total\":0.01}}", assertions))
            addTab("Parameters", editor("Optional JSON array (1–20 rows): [{\"id\":\"small\",\"input\":\"input-small\",\"expected\":\"expected-small\"}]. Empty uses the selected DATA pair. Each row has a fresh session and transaction.", parameters))
            addTab("Setup / cleanup", JPanel(GridLayout(3, 1)).apply {
                add(editor("Imports (Java import statements):", importsCode))
                add(editor("Setup (runs after input binding, before code):", setupCode))
                add(editor("Cleanup (best effort; skipped after interruption):", teardownCode))
            })
            addTab("Options", WorkbookToolbar().apply {
                add(JLabel("Tags (comma-separated):")); add(tags); add(disabled)
                add(JLabel("Maximum code duration (ms, optional):")); add(maxDuration)
            })
        }
        val top = JPanel(BorderLayout()).apply { add(fields, BorderLayout.NORTH); add(codeTabs, BorderLayout.CENTER); add(controls, BorderLayout.SOUTH) }
        val results = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, JScrollPane(table), JScrollPane(report)).apply { resizeWeight = 0.4 }
        add(JSplitPane(JSplitPane.VERTICAL_SPLIT, top, results).apply { resizeWeight = 0.45 }, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply { add(reloadLabel, BorderLayout.NORTH); add(status, BorderLayout.SOUTH) }, BorderLayout.SOUTH)
    }
    private fun exportJUnit() {
        if (workflow.busy || loadingSource) { error("Wait for the running workflow"); return }
        val selected = selectedNames().singleOrNull() ?: run { error("Select one saved case to export"); return }
        val pkg = JTextField("reproduction"); val cls = JTextField("ReproductionTest")
        val mode = JComboBox(arrayOf("ROLLBACK", "READ_ONLY", "LIVE")); val manager = JTextField()
        val fields = JPanel(GridLayout(0,2,6,6)).apply {
            add(JLabel("Java package:")); add(pkg); add(JLabel("Test class:")); add(cls)
            add(JLabel("Test execution mode:")); add(mode); add(JLabel("Transaction manager (optional):")); add(manager)
        }
        val panel = JPanel(BorderLayout()).apply {
            add(fields, BorderLayout.CENTER)
            add(JTextArea("""Exports the saved CASE; save editor changes first.
Set Input type and Result expression before export.
Includes DATA fixtures: review before sharing. No code is run.""").apply { isEditable=false; isOpaque=false }, BorderLayout.SOUTH)
        }
        if (JOptionPane.showConfirmDialog(null, panel, "Export JUnit 5 + AssertJ", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return
        val target = FileChooserFactory.getInstance().createSaveFileDialog(FileSaverDescriptor("Export JUnit sources", "Java test and JSON resources", "zip"), project)
            .save(null as VirtualFile?, "${cls.text.trim()}.zip") ?: return
        if (target.file.exists() && Messages.showYesNoDialog(project, "Replace the existing ZIP file?", "Export JUnit", null) != Messages.YES) return
        service.request("case/export-junit-file", mapOf("name" to selected, "package" to pkg.text.trim(), "class" to cls.text.trim(),
            "execution-mode" to mode.selectedItem.toString(), "transaction-manager" to manager.text.trim(), "path" to target.file.absolutePath),
            { status.text = "JUnit sources exported: ${target.file.absolutePath}" }, ::error)
    }
    private fun selectedNames() = table.selectedRows.map { model.getValueAt(table.convertRowIndexToModel(it), 0).toString() }
    private fun reloadAndRun() {
        if (workflow.busy || loadingSource) { error("Wait for the active workflow"); return }
        val names = selectedNames()
        if (names.isEmpty() || names.size > 20) { error("Select 1–20 saved cases first"); return }
        val file = reloadFile
        val document = reloadDocument ?: file?.let { FileDocumentManager.getInstance().getCachedDocument(it) }
        if (document != null) { run(names, document.text); return }
        if (file == null) { error("Select the Java source to reload first"); return }
        loadingSource = true; val requestGeneration = generation
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                require(file.length <= 1_000_000) { "Java source exceeds 1 MB" }
                val text = String(file.contentsToByteArray(), Charsets.UTF_8)
                ApplicationManager.getApplication().invokeLater {
                    if (!disposed && requestGeneration == generation) { loadingSource = false; run(names, text) }
                }
            } catch (failure: Exception) { ApplicationManager.getApplication().invokeLater {
                if (!disposed && requestGeneration == generation) { loadingSource = false; error(failure.message.orEmpty()) }
            } }
        }
    }
    private fun showResult(case: String, result: Map<String, String>) {
        report.text = buildString {
            appendLine(case); appendLine(result["detail"].orEmpty())
            if (result.containsKey("execution-mode")) {
                appendLine("Execution: ${result["execution-mode"]}; manager: ${result["transaction-manager"].orEmpty()}")
                appendLine("Transaction rolled back: ${result["transaction-rolled-back"] ?: "not confirmed"}; duration: ${result["execution-duration-ms"]} ms")
            }
            appendLine(result["out"].orEmpty()); appendLine(result["value"].orEmpty())
            result["rows-json"]?.let { appendLine("Row details:"); appendLine(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(com.google.gson.JsonParser.parseString(it))) }
        }
    }
    private fun run(names: List<String>, javaSource: String? = null) {
        if (loadingSource) { error("Wait for the Java source to load, or stop the workflow"); return }
        try { workflow.start(names, javaSource) } catch (failure: IllegalArgumentException) { error(failure.message.orEmpty()) }
        catch (failure: IllegalStateException) { error(failure.message.orEmpty()) }
    }
    private fun loadSelected() {
        if (workflow.busy || loadingSource) { error("Wait for the running workflow"); return }
        val selected = selectedNames().firstOrNull() ?: return
        val requestGeneration = generation
        service.request("case/load", mapOf("name" to selected), {
            if (disposed || requestGeneration != generation) return@request
            name.text = selected; input.selectedItem = it["input"]; expected.selectedItem = it["expected"]
            variable.text = it["variable"]; type.text = it["type"]; source.text = it["code"]
            expectedException.text = it["expected-exception"].orEmpty(); expectedMessage.text = it["expected-message"].orEmpty()
            resultExpression.text = it["result-expression"].orEmpty(); assertions.text = it["assertions-json"].orEmpty().ifBlank { "{}" }
            parameters.text = it["parameters-json"].orEmpty(); setupCode.text = it["setup"].orEmpty(); teardownCode.text = it["teardown"].orEmpty()
            importsCode.text = it["imports"].orEmpty(); tags.text = it["tags"].orEmpty(); disabled.isSelected = it["disabled"] == "true"
            maxDuration.text = it["max-duration-ms"].orEmpty()
            status.text = "Case loaded for review; no code executed"
        }, ::error)
    }
    fun refresh() {
        if (workflow.busy || loadingSource || disposed) return
        val requestGeneration = generation
        service.request("snapshot/list", onResult = {
            if (disposed || requestGeneration != generation || workflow.busy) return@request
            val selected = selectedNames().toSet(); val oldInput = input.selectedItem; val oldExpected = expected.selectedItem
            model.rowCount = 0; input.removeAllItems(); expected.removeAllItems()
            for (line in it["value"].orEmpty().lines().filter(String::isNotBlank)) {
                val fields = line.split('\t'); if (fields.size < 3) continue
                when (fields[2]) {
                    "DATA" -> { input.addItem(fields[0]); expected.addItem(fields[0]) }
                    "CASE" -> model.addRow(arrayOf(fields[0], outcomes[fields[0]]?.get("outcome") ?: "NOT RUN", outcomes[fields[0]]?.get("duration-ms").orEmpty()))
                }
            }
            for (row in 0 until model.rowCount) if (model.getValueAt(row, 0) in selected) table.addRowSelectionInterval(row, row)
            if (oldInput != null) input.selectedItem = oldInput
            if (oldExpected != null) expected.selectedItem = oldExpected
        }, onError = ::error)
    }
    private fun error(message: String) { if (!disposed) status.text = message }
    override fun dispose() { disposed = true; generation++; workflow.invalidate(); listener.dispose() }
}
