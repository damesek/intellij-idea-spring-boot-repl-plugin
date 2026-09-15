package hu.baader.repl.ui

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import hu.baader.repl.nrepl.NreplService
import hu.baader.repl.protocol.SensitiveValues
import java.awt.BorderLayout
import java.awt.GridLayout
import javax.swing.*

/** Reuses existing result/snapshot/HTTP state. Creation and export never rerun the workbook. */
class ReproductionActions(private val project: Project, private val service: NreplService,
    private val changed: () -> Unit, private val report: (String) -> Unit) {
    private var lastBundle = ""
    fun create() {
        service.request("snapshot/list", onResult = { response ->
            if (project.isDisposed) return@request
            val names = response["value"].orEmpty().lines().map { it.split('\t') }.filter { it.size >= 3 && it[2] == "DATA" }.map { it[0] }
            if (names.isEmpty()) { report("Capture or save the original input as DATA first."); return@request }
            val name = JTextField("reproduction")
            val input = JComboBox(names.toTypedArray())
            val variable = JTextField("input")
            val type = JTextField()
            val metadata = JTextArea("{}", 4, 45)
            val http = JCheckBox("Include last selected saved HTTP request (recognized secrets redacted)", false)
            val fields = JPanel(GridLayout(0,2,6,6)).apply {
                add(JLabel("Bundle name")); add(name); add(JLabel("Original input DATA")); add(input)
                add(JLabel("Input variable in executed code")); add(variable); add(JLabel("Input type (optional)")); add(type)
            }
            val panel = JPanel(BorderLayout(6,6)).apply {
                add(fields,BorderLayout.NORTH)
                add(JPanel(BorderLayout()).apply { add(JLabel("Optional tenant / flag / business-clock metadata (JSON object):"),BorderLayout.NORTH); add(JScrollPane(metadata),BorderLayout.CENTER) },BorderLayout.CENTER)
                add(JPanel(BorderLayout()).apply {
                    add(http,BorderLayout.NORTH)
                    add(JTextArea("Copies the selected DATA and freezes the current result of the last executed cell.\nCreates CASE + RECIPE + context evidence; no code is rerun.\nThe original input must correspond to that execution. Metadata is not automatically restored.\nDATA payloads may contain personal data or credentials; review before sharing.").apply { isEditable=false; isOpaque=false },BorderLayout.SOUTH)
                },BorderLayout.SOUTH)
            }
            if (JOptionPane.showConfirmDialog(null,panel,"Create reproduction from last execution",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION) return@request
            val fieldsToSend = mutableMapOf("name" to name.text.trim(), "input" to input.selectedItem.toString(), "variable" to variable.text.trim(), "type" to type.text.trim())
            val rawMetadata = metadata.text
            val includeHttp = http.isSelected
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val extra = Gson().fromJson(rawMetadata,JsonObject::class.java) ?: JsonObject()
                    if (includeHttp) {
                        val requests = HttpRequestService.getInstance(project)
                        val selected = requests.getLastSelectedId()
                        val request = requests.getRequests().firstOrNull { it.id == selected }
                            ?: throw IllegalArgumentException("Select and save an HTTP request before including it.")
                        val safe = request.deepCopy()
                        safe.url = SensitiveValues.redact(safe.url); safe.body = SensitiveValues.redact(safe.body)
                        safe.headers.forEach { it.value = if (SensitiveValues.sensitiveName(it.name)) "[REDACTED]" else SensitiveValues.redact(it.value) }
                        extra.add("savedHttpRequest",Gson().toJsonTree(safe))
                    }
                    fieldsToSend["metadata-json"] = Gson().toJson(extra)
                    service.request("reproduction/create", fieldsToSend, {
                        if (!project.isDisposed) { lastBundle=it["bundle-id"].orEmpty(); changed(); report("Created ${it["case"]}. ${it["value"]}"); export(lastBundle) }
                    }, report)
                } catch (failure: Exception) {
                    ApplicationManager.getApplication().invokeLater { if(!project.isDisposed) report(failure.message.orEmpty()) }
                }
            }
        }, onError = report)
    }
    fun export(name: String = "") {
        val bundle = if(name.isNotBlank()) name else Messages.showInputDialog(project,"Bundle ID returned by reproduction creation", "Export reproduction",null,lastBundle,null) ?: return
        val target = FileChooserFactory.getInstance().createSaveFileDialog(FileSaverDescriptor("Export reproduction", "Shares captured data, source and context metadata; no execution", "sbrepl-bundle"),project)
            .save(null as VirtualFile?,"$bundle.sbrepl-bundle") ?: return
        if (target.file.exists() && Messages.showYesNoDialog(project,"Replace the existing bundle file?","Export reproduction",null)!=Messages.YES) return
        service.request("reproduction/export-file",mapOf("name" to bundle,"path" to target.file.absolutePath),{ report("Exported ${target.file.absolutePath}") },report)
    }
    fun importBundle() {
        FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFileDescriptor("sbrepl-bundle"),project,null)?.let { file ->
            service.request("reproduction/import-file",mapOf("path" to file.path,"name" to "imported"),{
                lastBundle=it["bundle-id"].orEmpty(); changed(); report("Imported ${it["case"]}. ${it["value"]}")
            },report)
        }
    }
}
