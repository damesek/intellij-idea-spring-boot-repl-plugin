package hu.baader.repl.editor

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.ui.JBColor
import java.awt.*

/** A bounded preview and mouse actions; painting never reads the target JVM. */
class InlineResultRenderer(var title: String, var preview: String, var detail: String, var failed: Boolean,
                           var stale: Boolean, val actions: List<Link>) : EditorCustomElementRenderer {
    data class Link(val text: String, val available: () -> Boolean = { true }, val run: () -> Unit)
    private val hitAreas = mutableListOf<Pair<Rectangle, Link>>()
    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val editor = inlay.editor
        val width = editor.scrollingModel.visibleArea.width.takeIf { it > 0 }
            ?: editor.contentComponent.parent?.width ?: 0
        return (width - 16).coerceAtLeast(200)
    }
    override fun calcHeightInPixels(inlay: Inlay<*>) = inlay.editor.lineHeight * (if (detail.isBlank()) 3 else 4) + 10
    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val editor = inlay.editor
        val font = Font(editor.colorsScheme.editorFontName, Font.PLAIN, editor.colorsScheme.editorFontSize.coerceAtLeast(11))
        paintPreview(g, targetRegion, font, editor.lineHeight)
    }
    fun paintPreview(g: Graphics, area: Rectangle, font: Font, lineHeight: Int) {
        val graphics = g.create() as Graphics2D
        try {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            graphics.clip(area); graphics.font = font
            graphics.color = JBColor(Color(242, 246, 250), Color(43, 47, 53)); graphics.fillRoundRect(area.x, area.y + 2, area.width, area.height - 4, 6, 6)
            val color = when { failed -> JBColor(Color(168, 50, 50), Color(239, 132, 132)); stale -> JBColor(Color(128, 88, 15), Color(224, 184, 95)); else -> JBColor(Color(32, 113, 82), Color(107, 191, 152)) }
            graphics.color = color; graphics.fillRect(area.x, area.y + 4, 3, area.height - 8)
            val baseline = area.y + lineHeight
            val fm = graphics.fontMetrics
            // Keep the execution identity visible; mouse actions have their own row.
            val visibleLinks = if (area.width < 360 && actions.size > 2) listOf(actions.first(), actions.last()) else actions
            val linksWidth = visibleLinks.sumOf { fm.stringWidth(it.text) + 18 }
            graphics.drawString(fit(title, fm, area.width - 22), area.x + 10, baseline)
            hitAreas.clear()
            var x = area.x + area.width - linksWidth + 8
            for (link in visibleLinks) {
                val w = fm.stringWidth(link.text)
                graphics.color = if (link.available()) JBColor(Color(34, 96, 159), Color(117, 173, 232)) else JBColor.GRAY
                graphics.drawString(link.text, x, baseline + lineHeight * 2)
                hitAreas += Rectangle(x - area.x - 4, lineHeight * 2 + 1, w + 8, lineHeight + 5) to link
                x += w + 18
            }
            graphics.color = JBColor(Color(66, 73, 81), Color(194, 201, 211))
            graphics.drawString(fit(preview, fm, area.width - 22), area.x + 10, baseline + lineHeight)
            if (detail.isNotBlank()) { graphics.color = color; graphics.drawString(fit(detail, fm, area.width - 22), area.x + 10, baseline + lineHeight * 3) }
        } finally { graphics.dispose() }
    }
    fun click(x: Int, y: Int): Boolean {
        val link = hitAreas.firstOrNull { it.first.contains(x, y) }?.second ?: return false
        if (link.available()) link.run()
        return true
    }
    fun isLink(x: Int, y: Int) = hitAreas.any { it.first.contains(x, y) && it.second.available() }
    internal fun bounds(label: String): Rectangle? = hitAreas.firstOrNull { it.second.text == label }?.first?.let(::Rectangle)
    override fun getContextMenuGroup(inlay: Inlay<*>): ActionGroup = menu()
    fun menu(): ActionGroup = DefaultActionGroup(actions.map { link -> object : DumbAwareAction(link.text) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) { e.presentation.isEnabled = link.available() }
        override fun actionPerformed(e: AnActionEvent) { if (link.available()) link.run() }
    } })
    companion object {
        fun text(value: String) = value.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().map { if (it.isISOControl()) ' ' else it }.joinToString("").take(500)
        private fun fit(value: String, fm: FontMetrics, width: Int): String {
            val clean = text(value)
            if (fm.stringWidth(clean) <= width) return clean
            var end = clean.length
            while (end > 0 && fm.stringWidth(clean.substring(0, end) + "…") > width) end--
            return clean.substring(0, end) + "…"
        }
    }
}
