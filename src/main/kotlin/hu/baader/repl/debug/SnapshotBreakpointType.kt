package hu.baader.repl.debug

import com.intellij.debugger.ui.breakpoints.JavaLineBreakpointType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import org.jetbrains.java.debugger.breakpoints.properties.JavaLineBreakpointProperties
import java.awt.Component
import java.awt.Graphics
import javax.swing.Icon

/** A Java line logpoint with its own snapshot marker and configuration action. */
class SnapshotBreakpointType : JavaLineBreakpointType("sb-repl-snapshot", "REPL Snapshot") {
    override fun getPriority() = -100
    override fun isAddBreakpointButtonVisible() = false
    override fun getDefaultSuspendPolicy() = SuspendPolicy.NONE
    override fun getEnabledIcon(): Icon = SnapshotDot(true)
    override fun getSuspendNoneIcon(): Icon = SnapshotDot(true)
    override fun getDisabledIcon(): Icon = SnapshotDot(false)
    override fun getDisplayText(breakpoint: XLineBreakpoint<JavaLineBreakpointProperties>): String {
        val spec = SnapshotPointSpec.read(breakpoint.logExpressionObject?.expression)
        return "Snapshot ${spec?.name ?: "(configure)"} · ${breakpoint.shortFilePath}:${breakpoint.line + 1}"
    }
    override fun getAdditionalPopupMenuActions(breakpoint: XLineBreakpoint<JavaLineBreakpointProperties>, session: XDebugSession?): List<com.intellij.openapi.actionSystem.AnAction> =
        listOf(object : DumbAwareAction("Configure snapshot / rearm…") {
            override fun actionPerformed(e: AnActionEvent) {
                val project = e.project ?: session?.project ?: return
                hu.baader.repl.actions.SnapshotPointAction.configure(project, breakpoint)
            }
        })
    private class SnapshotDot(private val enabled: Boolean) : Icon {
        override fun getIconWidth() = 16
        override fun getIconHeight() = 16
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val copy = g.create() as java.awt.Graphics2D
            try {
                copy.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                copy.color = if (enabled) com.intellij.ui.JBColor(java.awt.Color(127, 69, 184), java.awt.Color(191, 141, 237)) else com.intellij.ui.JBColor.GRAY
                copy.fillOval(x + 1, y + 1, 14, 14)
                copy.color = java.awt.Color.WHITE; copy.drawRect(x + 4, y + 5, 7, 6); copy.drawOval(x + 6, y + 6, 3, 3)
            } finally { copy.dispose() }
        }
    }
}
