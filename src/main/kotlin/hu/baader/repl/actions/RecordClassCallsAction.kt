package hu.baader.repl.actions

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.util.PsiTreeUtil
import hu.baader.repl.trace.RecordingController
import hu.baader.repl.trace.RecordingSource

class RecordClassCallsAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible=e.project!=null && e.getData(CommonDataKeys.PSI_FILE) is PsiJavaFile }
    override fun actionPerformed(e: AnActionEvent) {
        val project=e.project ?: return
        val file=e.getData(CommonDataKeys.PSI_FILE) as? PsiJavaFile ?: return
        val editor=e.getData(CommonDataKeys.EDITOR) ?: return
        val element=file.findElementAt(editor.caretModel.offset.coerceIn(0,(file.textLength-1).coerceAtLeast(0)))
        val owner=element?.let { PsiTreeUtil.getParentOfType(it,PsiClass::class.java,false) } ?: file.classes.firstOrNull() ?: return
        val name=RecordingSource.binaryName(owner) ?: return
        ToolWindowManager.getInstance(project).getToolWindow("Spring Boot REPL")?.activate {
            val controller=RecordingController.get(project);controller.showPanel?.invoke();controller.configurePanel?.invoke(name)
        }
    }
}
