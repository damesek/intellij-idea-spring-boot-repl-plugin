package hu.baader.repl.ui

import java.awt.*
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JPanel

/** Keep workbook actions reachable when the tool window is narrow. */
class WorkbookToolbar : JPanel(object : FlowLayout(LEFT, 4, 2) {
    override fun preferredLayoutSize(target: Container): Dimension {
        if (target.width <= 0) return super.preferredLayoutSize(target)
        val insets = target.insets
        val available = (target.width - insets.left - insets.right - 2 * hgap).coerceAtLeast(1)
        var width = 0; var height = 0; var rowWidth = 0; var rowHeight = 0
        for (component in target.components.filter { it.isVisible }) {
            val size = component.preferredSize
            val gap = if (rowWidth == 0) 0 else hgap
            if (rowWidth > 0 && rowWidth + gap + size.width > available) {
                width = maxOf(width, rowWidth); height += rowHeight + vgap; rowWidth = 0; rowHeight = 0
            }
            rowWidth += (if (rowWidth == 0) 0 else hgap) + size.width
            rowHeight = maxOf(rowHeight, size.height)
        }
        return Dimension(maxOf(width, rowWidth) + insets.left + insets.right + 2 * hgap,
            height + rowHeight + insets.top + insets.bottom + 2 * vgap)
    }
}) {
    init { addComponentListener(object : ComponentAdapter() {
        override fun componentResized(e: ComponentEvent) { parent?.revalidate() }
    }) }
}
