package hu.baader.repl.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import hu.baader.repl.nrepl.NreplService
import java.awt.BorderLayout
import javax.swing.JPanel
import com.intellij.openapi.editor.ex.EditorEx

data class SnapshotRow(val name: String, val type: String, val mode: String, val ts: Long, val size: Long)

class SnapshotsPanel(private val project: Project, private val replEditor: EditorEx) : JPanel(BorderLayout()) {
    private val svc = NreplService.getInstance(project)

    private val columns: Array<ColumnInfo<SnapshotRow, *>> = arrayOf(
        object : ColumnInfo<SnapshotRow, String>("Name") { override fun valueOf(it: SnapshotRow) = it.name },
        object : ColumnInfo<SnapshotRow, String>("Type") { override fun valueOf(it: SnapshotRow) = it.type },
        object : ColumnInfo<SnapshotRow, String>("Mode") { override fun valueOf(it: SnapshotRow) = it.mode },
        object : ColumnInfo<SnapshotRow, String>("TS") { override fun valueOf(it: SnapshotRow) = it.ts.toString() },
        object : ColumnInfo<SnapshotRow, String>("~Size") { override fun valueOf(it: SnapshotRow) = it.size.toString() }
    )

    private val model = ListTableModel<SnapshotRow>(columns, mutableListOf())
    private val table = JBTable(model)

    init {
        val toolbar = ActionManager.getInstance().createActionToolbar(
            "SnapshotsToolbar",
            DefaultActionGroup(
                object : AnAction("Refresh", "Reload snapshots", AllIcons.Actions.Refresh) {
                    override fun actionPerformed(e: AnActionEvent) { reload() }
                },
                object : AnAction("Bind", "Insert binding for selected snapshot", AllIcons.Actions.Execute) {
                    override fun actionPerformed(e: AnActionEvent) { bindSelected() }
                },
                object : AnAction("Unbind", "Remove binding from editor", AllIcons.Actions.Undo) {
                    override fun actionPerformed(e: AnActionEvent) { unbindSelected() }
                },
                object : AnAction("Delete", "Delete selected snapshot", AllIcons.Actions.GC) {
                    override fun actionPerformed(e: AnActionEvent) { deleteSelected() }
                },
                Separator.create(),
                object : AnAction("Pin (LIVE)", "Pin expression result as LIVE snapshot", AllIcons.General.Add) {
                    override fun actionPerformed(e: AnActionEvent) { pinExpr() }
                },
                object : AnAction("Save JSON", "Save expression as JSON snapshot", AllIcons.Actions.MenuSaveall) {
                    override fun actionPerformed(e: AnActionEvent) { saveJsonExpr() }
                }
            ), true
        )
        toolbar.targetComponent = this
        add(toolbar.component, BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)
    }

    fun reload() {
        svc.listAgentSnapshots(onResult = { tsv ->
            val rows = parseTsv(tsv)
            ApplicationManager.getApplication().invokeLater {
                model.setItems(rows)
            }
        }, onError = { err ->
            ApplicationManager.getApplication().invokeLater {
                Messages.showErrorDialog(project, "Snapshots load failed: $err", "Snapshots")
            }
        })
    }

    private fun parseTsv(tsv: String): MutableList<SnapshotRow> {
        val list = mutableListOf<SnapshotRow>()
        tsv.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            val parts = line.split('\t')
            val name = parts.getOrNull(0) ?: return@forEach
            val type = parts.getOrNull(1) ?: ""
            val mode = parts.getOrNull(2) ?: ""
            val ts = parts.getOrNull(3)?.toLongOrNull() ?: 0L
            val size = parts.getOrNull(4)?.toLongOrNull() ?: 0L
            list.add(SnapshotRow(name, type, mode, ts, size))
        }
        return list
    }

    private fun selected(): SnapshotRow? {
        val row = table.selectedRow
        return if (row >= 0) model.items[row] else null
    }

    private fun bindSelected() {
        val s = selected() ?: return
        val varName = Messages.showInputDialog(project, "Variable name:", "Bind Snapshot", null, s.name, null) ?: return
        val fqnDefault = if (s.type.isNotBlank() && s.type != "null") s.type else ""
        val fqn = Messages.showInputDialog(project, "Target type FQN (optional)", "Bind Snapshot", null, fqnDefault, null)
        val snippet = if (s.mode.equals("JSON", ignoreCase = true)) {
            if (fqn.isNullOrBlank()) {
                Messages.showInfoMessage(project, "Type FQN required for JSON snapshot.", "Bind Snapshot")
                return
            }
            """
            try {
              Class<?> ss = Class.forName("com.baader.devrt.SnapshotStore");
              java.lang.reflect.Method gj;
              try { gj = ss.getMethod("getJson", String.class); }
              catch (NoSuchMethodException ex) { gj = ss.getDeclaredMethod("getJson", String.class); gj.setAccessible(true); }
              String json = (String) gj.invoke(null, "${s.name}");
              com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
              var $varName = mapper.readValue(json, ${fqn.trim()}.class);
            } catch (Throwable t) { t.printStackTrace(); }
            """.trimIndent()
        } else {
            if (!fqn.isNullOrBlank())
                """
                try {
                  Class<?> ss = Class.forName("com.baader.devrt.SnapshotStore");
                  java.lang.reflect.Method gm;
                  try { gm = ss.getMethod("get", String.class); }
                  catch (NoSuchMethodException ex) { gm = ss.getDeclaredMethod("get", String.class); gm.setAccessible(true); }
                  Object tmp = gm.invoke(null, "${s.name}");
                  var $varName = (${fqn.trim()}) tmp;
                } catch (Throwable t) { t.printStackTrace(); }
                """.trimIndent()
            else
                """
                try {
                  Class<?> ss = Class.forName("com.baader.devrt.SnapshotStore");
                  java.lang.reflect.Method gm;
                  try { gm = ss.getMethod("get", String.class); }
                  catch (NoSuchMethodException ex) { gm = ss.getDeclaredMethod("get", String.class); gm.setAccessible(true); }
                  Object $varName = gm.invoke(null, "${s.name}");
                } catch (Throwable t) { t.printStackTrace(); }
                """.trimIndent()
        }
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            val doc = replEditor.document
            doc.insertString(0, snippet)
        }
    }

    private fun unbindSelected() {
        val s = selected() ?: return
        val varName = Messages.showInputDialog(project, "Variable name to unbind:", "Unbind Snapshot", null, s.name, null) ?: return
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            val doc = replEditor.document
            val text = doc.text
            val lines = text.lines().toMutableList()
            val iter = lines.listIterator()
            while (iter.hasNext()) {
                val l = iter.next()
                if (l.contains(" $varName = ") && (l.contains("com.baader.devrt.SnapshotStore.get(") || l.contains("getJson(\"${s.name}\")"))) {
                    iter.remove()
                }
            }
            doc.setText(lines.joinToString("\n"))
        }
    }

    private fun deleteSelected() {
        val s = selected() ?: return
        val ok = Messages.showYesNoDialog(project, "Delete snapshot '${s.name}'?", "Delete Snapshot", null)
        if (ok != Messages.YES) return
        svc.deleteSnapshot(s.name, onResult = {
            reload()
        }, onError = { err ->
            Messages.showErrorDialog(project, "Delete failed: $err", "Snapshots")
        })
    }

    private fun pinExpr() {
        val name = Messages.showInputDialog(project, "Snapshot name (LIVE)", "Pin LIVE", null) ?: return
        val expr = Messages.showInputDialog(project, "Java expr to evaluate (return obj;)", "Pin LIVE", null) ?: return
        svc.snapshotPin(name, expr, onResult = { ApplicationManager.getApplication().invokeLater { reload() } }, onError = { err ->
            ApplicationManager.getApplication().invokeLater {
                Messages.showErrorDialog(project, "Pin failed: $err", "Snapshots")
            }
        })
    }

    private fun saveJsonExpr() {
        val name = Messages.showInputDialog(project, "Snapshot name (JSON)", "Save JSON", null) ?: return
        val expr = Messages.showInputDialog(project, "Java expr to evaluate (return obj;)", "Save JSON", null) ?: return
        svc.snapshotSaveJson(name, expr, onResult = { ApplicationManager.getApplication().invokeLater { reload() } }, onError = { err ->
            ApplicationManager.getApplication().invokeLater {
                Messages.showErrorDialog(project, "Save JSON failed: $err", "Snapshots")
            }
        })
    }
}
