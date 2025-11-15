package hu.baader.repl.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBScrollPane
import hu.baader.repl.ui.JavaReplToolWindowFactory
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Opens an "Advanced Editor" popup: left side will host a PSI-backed Java editor,
 * right side a transcript view. For now this is a thin wrapper that delegates
 * to JavaReplToolWindowFactory to build the panel.
 */
class OpenAdvancedEditorAction : AnAction(
    "Advanced Editor",
    "Open advanced Java editor with session PSI and transcript",
    AllIcons.Actions.IntentionBulb
) {

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return

        // Ask the tool window factory for a lightweight advanced panel if available.
        // For now we rely on a static helper; this keeps the first iteration minimal.
        val panel = JavaReplToolWindowFactory.createAdvancedEditorPanel(project) ?: return

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(JBScrollPane(panel), panel)
            .setTitle("Spring Boot REPL – Advanced Editor")
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .createPopup()

        popup.showInFocusCenter()
    }
}

