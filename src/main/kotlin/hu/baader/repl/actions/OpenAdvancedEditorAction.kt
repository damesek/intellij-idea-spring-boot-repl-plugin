package hu.baader.repl.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager

class OpenAdvancedEditorAction : AnAction("Open REPL Workbook") {
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let { ToolWindowManager.getInstance(it).getToolWindow("Spring Boot REPL")?.activate(null) }
    }
}
