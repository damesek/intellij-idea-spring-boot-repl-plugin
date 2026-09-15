package hu.baader.repl.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import hu.baader.repl.help.ReplHelp

class OpenReplHelpAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) = ReplHelp.open(e.project)
}
