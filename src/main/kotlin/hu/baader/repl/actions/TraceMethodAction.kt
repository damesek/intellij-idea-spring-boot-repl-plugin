package hu.baader.repl.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import hu.baader.repl.nrepl.NreplService

class TraceMethodAction : AnAction("Trace Method") {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE) as? PsiJavaFile ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val element = file.findElementAt(editor.caretModel.offset.coerceAtMost((file.textLength - 1).coerceAtLeast(0))) ?: return
        val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false) ?: return
        val owner = method.containingClass ?: return
        fun binaryName(type: PsiClass): String? {
            val name = type.name ?: return null
            return type.containingClass?.let { binaryName(it)?.plus("$" + name) }
                ?: type.qualifiedName
        }
        val className = binaryName(owner) ?: return
        fun notify(message: String, type: NotificationType) {
            NotificationGroupManager.getInstance().getNotificationGroup("Spring Boot REPL").createNotification(message, type).notify(project)
        }
        NreplService.getInstance(project).request("trace/configure", mapOf("class" to className, "method" to method.name, "enabled" to "true"), {
            notify("Tracing $className.${method.name}. Open Tap / Trace to inspect calls and stop tracing.", NotificationType.INFORMATION)
            ToolWindowManager.getInstance(project).getToolWindow("Spring Boot REPL")?.activate(null)
        }, { notify(it, NotificationType.ERROR) })
    }
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.PSI_FILE) is PsiJavaFile
    }
}
