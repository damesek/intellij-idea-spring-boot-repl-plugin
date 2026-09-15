package hu.baader.repl.actions

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.ex.EditorGutterComponentEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.evaluation.EvaluationMode
import hu.baader.repl.debug.SnapshotBreakpointType
import hu.baader.repl.debug.SnapshotPointSpec
import hu.baader.repl.editor.JavaReplEditorProvider
import org.jetbrains.java.debugger.breakpoints.properties.JavaLineBreakpointProperties
import javax.swing.*

class SnapshotPointAction : AnAction("Snapshot point…") {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.PSI_FILE)
        e.presentation.isEnabledAndVisible = file is PsiJavaFile && file.virtualFile?.getUserData(JavaReplEditorProvider.REPL_FILE) != true && e.getData(CommonDataKeys.EDITOR) != null
    }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE) as? PsiJavaFile ?: return
        val virtual = file.virtualFile ?: return
        val line = e.getData(EditorGutterComponentEx.LOGICAL_LINE_AT_CURSOR) ?: editor.caretModel.logicalPosition.line
        val type = XDebuggerUtil.getInstance().findBreakpointType(SnapshotBreakpointType::class.java) ?: return
        val manager = XDebuggerManager.getInstance(project).breakpointManager
        val existing = manager.findBreakpointsAtLine(type, virtual, line).firstOrNull()
        if (existing != null) { configure(project, existing); return }
        if (!type.canPutAt(virtual, line, project)) { Messages.showInfoMessage(project, "Choose an executable Java line. A local variable must already be initialized before this line.", "Snapshot point"); return }
        if (manager.getBreakpoints(type).size >= 64) { Messages.showInfoMessage(project, "At most 64 snapshot points are supported per project. Remove an unused point first.", "Snapshot point"); return }
        if (manager.allBreakpoints.filterIsInstance<XLineBreakpoint<*>>().any { it.fileUrl == virtual.url && it.line == line }) {
            Messages.showInfoMessage(project, "This line already has another breakpoint. Choose another executable line, or remove that breakpoint first.", "Snapshot point"); return
        }
        val suggested = editor.selectionModel.selectedText?.trim()?.takeIf { it.length <= 4096 }
            ?: PsiTreeUtil.getParentOfType(file.findElementAt(editor.caretModel.offset), PsiReferenceExpression::class.java)?.text.orEmpty()
        val dialog = PointDialog(project, "${virtual.name}:${line + 1}", SnapshotPointSpec("${virtual.nameWithoutExtension}-${line + 1}", suggested), false)
        if (!dialog.showAndGet()) return
        WriteCommandAction.runWriteCommandAction(project) {
            val point = manager.addLineBreakpoint(type, virtual.url, line, type.createBreakpointProperties(virtual, line))
            apply(point, dialog.spec())
        }
    }
    companion object {
        fun configure(project: Project, point: XLineBreakpoint<JavaLineBreakpointProperties>) {
            val old = SnapshotPointSpec.read(point.logExpressionObject?.expression)
            if (old == null) { Messages.showInfoMessage(project, "This snapshot point was edited outside its configuration dialog. Remove it and create a new point to configure its destination.", "Snapshot point"); return }
            val dialog = PointDialog(project, "${point.shortFilePath}:${point.line + 1}", old, true)
            if (!dialog.showAndGet()) return
            WriteCommandAction.runWriteCommandAction(project) {
                if (dialog.removeRequested) XDebuggerManager.getInstance(project).breakpointManager.removeBreakpoint(point)
                else apply(point, dialog.spec())
            }
        }
        private fun apply(point: XLineBreakpoint<JavaLineBreakpointProperties>, spec: SnapshotPointSpec) {
            fun expression(text: String) = XDebuggerUtil.getInstance().createExpression(text, com.intellij.lang.java.JavaLanguage.INSTANCE, null, EvaluationMode.EXPRESSION)
            point.suspendPolicy = SuspendPolicy.NONE; point.isLogMessage = false; point.isLogStack = false
            point.conditionExpression = expression(spec.condition()); point.logExpressionObject = expression(spec.logExpression())
            point.isEnabled = true
        }
    }
    private class PointDialog(project: Project, location: String, previous: SnapshotPointSpec, editing: Boolean) : DialogWrapper(project) {
        private val destination = JTextField(previous.name)
        private val valueExpression = JTextField(previous.expression)
        private val declaredType = JTextField(previous.type)
        private val count = JSpinner(SpinnerNumberModel(previous.count, 1, 100, 1))
        private val locationText = location
        private val canRemove = editing
        var removeRequested = false; private set
        init { title = "REPL Snapshot Point"; setOKButtonText(if (editing) "Save and rearm" else "Create snapshot point"); init() }
        fun spec() = SnapshotPointSpec(destination.text.trim(), valueExpression.text.trim(), declaredType.text.trim(), count.value as Int)
        override fun getPreferredFocusedComponent(): JComponent = valueExpression
        override fun createCenterPanel(): JComponent = JPanel(java.awt.GridBagLayout()).apply {
            var row = 0
            fun item(component: JComponent) = add(component, java.awt.GridBagConstraints().apply {
                gridx = 0; gridy = row++; weightx = 1.0; fill = java.awt.GridBagConstraints.HORIZONTAL
                insets = java.awt.Insets(3, 0, 3, 0)
            })
            item(JLabel(locationText))
            item(JLabel("Value expression (local variable, field or Java expression)").apply { labelFor = valueExpression }); item(valueExpression)
            item(JLabel("Snapshot name · an existing name creates a new version").apply { labelFor = destination }); item(destination)
            item(JLabel("Declared Java type (optional)").apply { labelFor = declaredType }); item(declaredType)
            item(JLabel("Capture attempts per application JVM (including serialization failures)").apply { labelFor = count }); item(count)
            item(JTextArea("Requires Debug + Enable Spring Boot REPL. Captures before this line executes. Serialization briefly runs on the hit thread; the debugger then continues. Prefer a variable over a method call.", 3, 56).apply {
                isEditable = false; lineWrap = true; wrapStyleWord = true; isOpaque = false
            })
        }
        override fun doValidate(): ValidationInfo? = try { count.commitEdit(); spec().validate(); null } catch (e: Exception) { ValidationInfo(e.message ?: "Invalid snapshot point", valueExpression) }
        override fun createActions(): Array<Action> {
            if (!canRemove) return super.createActions()
            return arrayOf(okAction, object : AbstractAction("Remove point") {
                override fun actionPerformed(e: java.awt.event.ActionEvent) { removeRequested = true; close(OK_EXIT_CODE) }
            }, cancelAction)
        }
    }
}
