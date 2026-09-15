package hu.baader.repl.workspace

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.google.gson.JsonParser
import hu.baader.repl.editor.NotebookController
import hu.baader.repl.nrepl.NreplService
import hu.baader.repl.protocol.SensitiveValues
import hu.baader.repl.ui.HttpRequestsPanel
import java.nio.file.Files
import javax.swing.*

class WorkspaceActions(
    private val project: Project, private val service: NreplService, private val store: WorkspaceStore,
    private val notebook: NotebookController, private val http: () -> HttpRequestsPanel,
    private val transcript: () -> String, private val showTranscript: (String) -> Unit, private val changed: () -> Unit,
    private val status: (String) -> Unit, private val busy: () -> Boolean
) {
    private fun ui(block: () -> Unit) = ApplicationManager.getApplication().invokeLater { if (!project.isDisposed) block() }
    private fun background(block: () -> Unit) = ApplicationManager.getApplication().executeOnPooledThread { try { block() } catch (e: Exception) { ui { status(e.message.orEmpty()) } } }
    private fun chooser(title: String) = JFileChooser().apply { dialogTitle = title; fileFilter = javax.swing.filechooser.FileNameExtensionFilter("REPL workspace", "sbrepl-workspace") }
    fun save() {
        if (busy() || notebook.suspended) { status("Wait for the running cell before saving a workspace"); return }
        try { notebook.synchronizeSource() } catch (e: Exception) { status(e.message.orEmpty()); return }
        val includeTranscript = JCheckBox("Include transcript (may contain application data)", false)
        val includeHttp = JCheckBox("Include HTTP requests with detected secrets removed", true)
        val dialog = chooser(if (service.isConnected()) "Save workspace with application DATA / CASE / RECIPE (200 MiB total)" else "Save workspace source only (application disconnected)")
        dialog.selectedFile = java.io.File("workspace.sbrepl-workspace")
        dialog.accessory = JPanel(java.awt.GridLayout(0, 1)).apply { add(includeTranscript); add(includeHttp); add(JLabel("Source and DATA can contain sensitive application data.")) }
        if (dialog.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return
        val target = dialog.selectedFile.toPath()
        if (Files.exists(target) && Messages.showYesNoDialog(project, "Replace ${target.fileName}?", "Save Workspace", null) != Messages.YES) return
        val doc = WorkspaceDocument.decode(store.document.encode())
        doc.transcript = if (includeTranscript.isSelected) SensitiveValues.redact(transcript().take(300000)) else ""
        doc.httpRequests = if (includeHttp.isSelected) http().workspaceRequests().map { request -> request.deepCopy().apply {
            url = SensitiveValues.redact(url); body = SensitiveValues.redact(body); description = SensitiveValues.redact(description)
            headers.forEach { it.value = if (SensitiveValues.sensitiveName(it.name)) "[REDACTED]" else SensitiveValues.redact(it.value) }
        } }.toMutableList() else mutableListOf()
        // Cell output and source are intentionally preserved. DATA is a faithful snapshot, not a redacted fixture.
        val json = try { doc.encode() } catch (e: Exception) { status(e.message.orEmpty()); return }
        if (!service.isConnected()) { background { WorkspaceFiles.writeOffline(target, doc); ui { status("Workspace source saved; application snapshots were unavailable") } }; return }
        status("Saving workspace…")
        background {
            val temp = Files.createTempFile("sbrepl-workspace-state-", ".json")
            WorkspaceStore.write(temp, json)
            ui {
                service.request("workspace/export-file", mapOf("path" to target.toAbsolutePath().toString(), "state-path" to temp.toString()), {
                    background { Files.deleteIfExists(temp) }; status(it["value"].orEmpty())
                }, { message -> background { Files.deleteIfExists(temp) }; status(message) })
            }
        }
    }
    fun open() {
        if (busy() || notebook.suspended) { status("Wait for the running cell before opening a workspace"); return }
        val dialog = chooser("Open workspace — source only; no Java, HTTP or DATA materialization")
        if (dialog.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return
        val source = dialog.selectedFile.toPath(); status("Validating workspace…")
        background {
            val opened = WorkspaceFiles.read(source)
            ui {
                if (busy()) { status("An execution started; finish it before opening the workspace"); return@ui }
                val doc = opened.document
                val message = "Replace the workbook with ${doc.notebook.cells.size} saved cells, ${doc.httpRequests.size} HTTP requests and ${opened.snapshots} snapshot entries?\nCurrent workbook is retained in a recovery checkpoint.\nImports and DATA bindings will require explicit restoration. Saved profiles are informational."
                if (Messages.showYesNoDialog(project, message, "Open Workspace", null) != Messages.YES) return@ui
                val previousSource = store.document.notebook.text
                notebook.suspended = true
                fun apply() {
                    try {
                        check(store.document.notebook.text == previousSource) { "Workbook changed during import; new snapshot names are available, but the edited source was kept" }
                        // Keep the previous editor state recoverable even after the automatic checkpoint advances.
                        val recovery = Files.createTempFile("sbrepl-workspace-before-import-", ".json")
                        WorkspaceStore.write(recovery, store.document.encode())
                        http().importWorkspaceRequests(doc.httpRequests)
                        notebook.replace(doc); showTranscript(doc.transcript); changed()
                        status("Workspace opened; no code executed. Previous workbook: $recovery")
                    } catch (e: Exception) { status(e.message.orEmpty()) } finally { notebook.suspended = false }
                }
                if (opened.snapshots == 0 || !service.isConnected()) { apply(); if (!service.isConnected() && opened.snapshots > 0) status("Workbook opened offline. Connect and reopen to import its DATA / CASE / RECIPE files."); return@ui }
                val prefix = Messages.showInputDialog(project, "New snapshot name prefix (no existing snapshot will be overwritten)", "Import Workspace Snapshots", null, "ws-" + java.util.UUID.randomUUID().toString().take(6), null) ?: run { notebook.suspended = false; return@ui }
                service.request("workspace/import-file", mapOf("path" to source.toAbsolutePath().toString(), "prefix" to prefix), { response ->
                    fun mapping(key: String) = JsonParser.parseString(response[key].orEmpty()).asJsonObject.entrySet().associate { it.key to it.value.asString }
                    val names = mapping("names-json"); val bindings = mapping("bindings-json"); val versions = mapping("versions-json")
                    doc.bindings.forEach { binding ->
                        val name = bindings["${binding.snapshot}\n${binding.version}"] ?: names[binding.snapshot]
                        if (name == null) binding.restorable = false else { binding.snapshot = name; binding.version = versions[name].orEmpty() }
                    }
                    apply()
                }, { notebook.suspended = false; status(it) })
            }
        }
    }
    fun info() {
        val doc = store.document
        val details = "Cells: ${doc.notebook.cells.size}\nSaved DATA origins: ${doc.bindings.size}\nBookmarks: ${doc.bookmarks.size}\n\nDATA bindings (frozen origin, not a guarantee of the current object value):\n" +
            doc.bindings.joinToString("\n") { "${it.variable} ← ${it.snapshot} @ ${it.version.take(12)} (${if (it.restorable) "DATA" else "LIVE / unavailable"})" } +
            "\n\nVariables without a DATA origin (cannot be restored automatically):\n" + doc.unrestorableVariables.joinToString("\n") +
            "\n\nSaved environment (informational, never applied):\n" + doc.environment
        Messages.showInfoMessage(project, details, "Workspace Contents")
    }
    fun restoreData() {
        val entries = store.document.bindings.filter { it.restorable }
        if (entries.isEmpty()) { status("No saved DATA bindings; LIVE objects cannot be restored"); return }
        if (busy() || notebook.suspended) { status("Wait for the active cell"); return }
        val labels = entries.map { "${it.variable} ← ${it.snapshot} @ ${it.version.take(12)}" }.toTypedArray()
        val i = Messages.showChooseDialog("Restore one frozen DATA version. This may execute DTO constructors and overwrite its Java variable.", "Restore DATA Binding", labels, labels[0], null)
        if (i < 0) return
        val binding = entries[i]
        service.request("snapshot/load", mapOf("name" to binding.snapshot, "var" to binding.variable, "type" to binding.type, "version" to binding.version), { status(it["value"].orEmpty()); changed() }, status)
    }
    fun insertImports(insert: (String) -> Unit) { if (store.document.imports.isNotEmpty()) insert(store.document.imports.joinToString("\n")) else status("No saved imports") }
}
