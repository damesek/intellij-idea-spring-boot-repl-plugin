package hu.baader.repl.trace

import com.intellij.ui.JBColor
import hu.baader.repl.protocol.RecordedCall
import java.awt.*
import java.awt.event.*
import javax.swing.*
import kotlin.math.roundToInt

/** Filtered children retain their recorded ancestors. Search reveals matches inside folded branches. */
object CallGraphLayout {
    const val NODE_WIDTH = 460
    const val NODE_HEIGHT = 108
    data class Node(
        val call: RecordedCall, val depth: Int, val bounds: Rectangle,
        val contextOnly: Boolean = false, val hasChildren: Boolean = false,
        val collapsed: Boolean = false, val hiddenCalls: Int = 0, val hiddenErrors: Int = 0
    )
    fun nodes(
        calls: List<RecordedCall>, root: Long? = null, collapsed: Set<Long> = emptySet(),
        filter: CallFilter = CallFilter(),
        presentations: Map<Long, CallPresentation> = calls.associate { it.id() to CallPresentation(it) }
    ): List<Node> {
        require(calls.size <= RecordedCall.MAX_CALLS)
        val groups = calls.groupBy { it.parent() }
        val origin = calls.minOfOrNull { it.startedAt() } ?: 0L
        val base = calls.filter { root == null || it.root() == root }.map { it.id() }.toSet()
        val focus = if (filter.focus == null) base else calls.filter {
            CallNavigation.path(calls, it.id()).any { ancestor -> ancestor.id() == filter.focus }
        }.map { it.id() }.toSet()
        val matches = calls.filter {
            it.id() in base && it.id() in focus && filter.matches(it, presentations.getValue(it.id()), origin)
        }.map { it.id() }.toSet()
        val paths = matches.flatMap { CallNavigation.path(calls, it) }.map { it.id() }.filter { it in base }.toSet()
        val requiredParents = if (filter.active) matches.flatMap { CallNavigation.path(calls, it).dropLast(1) }.map { it.id() }.toSet() else emptySet()
        fun descendants(parent: Long): List<RecordedCall> = groups[parent].orEmpty().flatMap { listOf(it) + descendants(it.id()) }
        val result = mutableListOf<Node>()
        fun visit(parent: Long, depth: Int) {
            require(depth <= RecordedCall.MAX_CALLS)
            groups[parent].orEmpty().sortedBy { it.id() }.forEach { call ->
                if (call.id() !in paths) return@forEach
                val children = groups[call.id()].orEmpty().any { it.id() in paths }
                val folded = call.id() in collapsed && call.id() !in requiredParents && children
                val hidden = if (folded) descendants(call.id()).filter { it.id() in paths } else emptyList()
                result += Node(call, depth, Rectangle(24 + depth * 32, 18 + result.size * 124, NODE_WIDTH, NODE_HEIGHT),
                    call.id() !in matches, children, folded, hidden.size, hidden.count { it.status() == "ERROR" })
                if (!folded) visit(call.id(), depth + 1)
            }
        }
        visit(0, 0)
        return result
    }
}

class CallGraph(
    private val select: (RecordedCall) -> Unit,
    private val viewChanged: () -> Unit = {}
) : JComponent() {
    var nodes = emptyList<CallGraphLayout.Node>(); private set
    var selected: Long? = null; private set
    var zoom = 1.0; private set
    val collapsed = mutableSetOf<Long>()
    private var calls = emptyList<RecordedCall>()
    private var root: Long? = null
    private var filter = CallFilter()
    private var presentations = emptyMap<Long, CallPresentation>()
    private var content = Dimension(520, 160)
    private var panOrigin: Point? = null
    private var panPosition: Point? = null
    private var dragged = false
    private var pendingReveal = false
    private var pendingFit = false
    init {
        isOpaque = true; isFocusable = true
        accessibleContext?.accessibleName = "Recorded method call graph"
        toolTipText = ""
        val mouse = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e) || SwingUtilities.isMiddleMouseButton(e)) {
                    viewport()?.let { panOrigin = SwingUtilities.convertPoint(this@CallGraph, e.point, it); panPosition = it.viewPosition }
                    dragged = false
                }
            }
            override fun mouseDragged(e: MouseEvent) {
                val vp = viewport() ?: return
                val anchor = panOrigin ?: return
                val initial = panPosition ?: return
                val now = SwingUtilities.convertPoint(this@CallGraph, e.point, vp)
                if (anchor.distance(now) > 4) dragged = true
                if (dragged) { cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR); position(Point(initial.x + anchor.x - now.x, initial.y + anchor.y - now.y));viewChanged() }
            }
            override fun mouseReleased(e: MouseEvent) { panOrigin = null; panPosition = null; cursor = Cursor.getDefaultCursor() }
            override fun mouseClicked(e: MouseEvent) {
                if (dragged || !SwingUtilities.isLeftMouseButton(e)) return
                val p = logical(e.point)
                val node = nodes.firstOrNull { it.bounds.contains(p) } ?: return
                if (node.hasChildren && p.x < node.bounds.x + 28 && p.y < node.bounds.y + 30) toggle(node.call.id())
                else choose(node.call)
            }
            override fun mouseWheelMoved(e: MouseWheelEvent) {
                if (e.isControlDown || e.isMetaDown) setZoom(zoom * Math.pow(1.15, -e.preciseWheelRotation), e.point)
                else {
                    val vp = viewport() ?: return
                    val p = vp.viewPosition
                    val step = (e.preciseWheelRotation * 42).roundToInt()
                    position(if (e.isShiftDown) Point(p.x + step, p.y) else Point(p.x, p.y + step))
                    viewChanged()
                }
                e.consume()
            }
        }
        addMouseListener(mouse); addMouseMotionListener(mouse); addMouseWheelListener(mouse)
        fun bind(key: String, name: String, action: () -> Unit) {
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), name)
            actionMap.put(name, object : AbstractAction() { override fun actionPerformed(e: ActionEvent?) = action() })
        }
        bind("DOWN", "next") { move(1) }; bind("UP", "previous") { move(-1) }
        bind("LEFT", "collapse") {
            selected?.let { id ->
                if (id !in collapsed && nodes.any { it.call.id()==id && it.hasChildren }) toggle(id)
                else calls.firstOrNull { it.id()==id }?.parent()?.let { parent -> calls.firstOrNull { it.id()==parent }?.let(::choose) }
            }
        }
        bind("RIGHT", "expand") { selected?.takeIf { it in collapsed }?.let(::toggle) }
        bind("ENTER", "open") { calls.firstOrNull { it.id() == selected }?.let(select) }
        bind("EQUALS", "zoomIn") { setZoom(zoom * 1.2) }; bind("MINUS", "zoomOut") { setZoom(zoom / 1.2) }
        bind("HOME", "fit") { fitAll() }
    }
    private fun viewport() = parent as? JViewport
    private fun logical(point: Point) = Point((point.x / zoom).toInt(), (point.y / zoom).toInt())
    private fun scaled(bounds: Rectangle) = Rectangle((bounds.x * zoom).roundToInt(), (bounds.y * zoom).roundToInt(),
        (bounds.width * zoom).roundToInt().coerceAtLeast(1), (bounds.height * zoom).roundToInt().coerceAtLeast(1))
    private fun position(p: Point) {
        val vp = viewport() ?: return
        vp.viewPosition = Point(p.x.coerceIn(0, (width - vp.extentSize.width).coerceAtLeast(0)),
            p.y.coerceIn(0, (height - vp.extentSize.height).coerceAtLeast(0)))
    }
    fun setZoom(value: Double, anchor: Point? = null) {
        if (!value.isFinite()) return
        val vp = viewport()
        val old = zoom
        val location = anchor ?: Point((vp?.viewPosition?.x ?: 0) + (vp?.extentSize?.width ?: width) / 2,
            (vp?.viewPosition?.y ?: 0) + (vp?.extentSize?.height ?: height) / 2)
        val screen = Point(location.x - (vp?.viewPosition?.x ?: 0), location.y - (vp?.viewPosition?.y ?: 0))
        zoom = value.coerceIn(0.001, 3.0)
        resizeContent()
        position(Point((location.x / old * zoom).roundToInt() - screen.x, (location.y / old * zoom).roundToInt() - screen.y))
        viewChanged()
    }
    private fun resizeContent() {
        preferredSize = Dimension((content.width * zoom).roundToInt(), (content.height * zoom).roundToInt())
        viewport()?.let { size = Dimension(maxOf(preferredSize.width, it.extentSize.width), maxOf(preferredSize.height, it.extentSize.height)) }
        revalidate(); repaint()
    }
    fun fitAll() {
        val extent = viewport()?.extentSize ?: size
        setZoom(minOf((extent.width - 12).coerceAtLeast(1).toDouble() / content.width,
            (extent.height - 12).coerceAtLeast(1).toDouble() / content.height, 1.0))
        position(Point(0, 0))
    }
    fun revealSelection(fit: Boolean = false) {
        val extent=viewport()?.extentSize
        if(extent==null || extent.width<=0 || extent.height<=0) { pendingReveal=true;pendingFit=fit;return }
        pendingReveal=false
        val call = calls.firstOrNull { it.id() == selected } ?: return
        val ancestors = CallNavigation.path(calls, call.id()).dropLast(1).map { it.id() }
        if (collapsed.removeAll(ancestors.toSet())) rebuild()
        val node = nodes.firstOrNull { it.call.id() == selected } ?: return
        if (fit) setZoom(minOf(1.0, ((viewport()?.extentSize?.width ?: width) - 32).coerceAtLeast(1).toDouble() / node.bounds.width))
        scrollRectToVisible(scaled(node.bounds))
    }
    override fun doLayout() {
        super.doLayout()
        if(pendingReveal) revealSelection(pendingFit)
    }
    fun resetView() { collapsed.clear(); zoom = 1.0; position(Point(0, 0)); viewChanged() }
    fun toggle(id: Long) { if (!collapsed.add(id)) collapsed.remove(id); rebuild(); viewChanged() }
    fun collapseAll() { collapsed.addAll(calls.map { it.parent() }.filter { it > 0 }); rebuild(); viewChanged() }
    fun expandAll() { collapsed.clear(); rebuild(); viewChanged() }
    private fun move(direction: Int) {
        if (nodes.isNotEmpty()) choose(nodes[(nodes.indexOfFirst { it.call.id() == selected } + direction).coerceIn(0, nodes.lastIndex)].call)
    }
    private fun choose(call: RecordedCall) {
        selected = call.id(); repaint(); requestFocusInWindow(); revealSelection(); select(call)
    }
    fun display(calls: List<RecordedCall>, root: Long?, selection: Long?, filter: CallFilter = CallFilter(),
                presentations: Map<Long, CallPresentation> = calls.associate { it.id() to CallPresentation(it) }) {
        this.calls = calls; this.root = root; selected = selection; this.filter = filter; this.presentations = presentations
        rebuild()
    }
    private fun rebuild() {
        nodes = CallGraphLayout.nodes(calls, root, collapsed, filter, presentations)
        content = Dimension(nodes.maxOfOrNull { it.bounds.x + it.bounds.width + 24 } ?: 520,
            nodes.lastOrNull()?.bounds?.let { it.y + it.height + 18 } ?: 160)
        resizeContent()
    }
    override fun getToolTipText(event: MouseEvent): String? = nodes.firstOrNull { it.bounds.contains(logical(event.point)) }
        ?.let { presentations[it.call.id()]?.tooltip }
    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g = graphics.create() as Graphics2D
        try {
            g.color = JBColor.PanelBackground; g.fillRect(0, 0, width, height)
            g.scale(zoom, zoom)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            if (nodes.isEmpty()) {
                g.color = JBColor.GRAY; g.font = UIManager.getFont("Label.font")
                g.drawString(if (calls.isEmpty()) "Record a class to capture calls." else "No matching calls. Clear filters to show all calls.", 24, 42)
                return
            }
            val index = nodes.associateBy { it.call.id() }
            g.color = JBColor.GRAY
            nodes.forEach { node -> index[node.call.parent()]?.let { p ->
                val x = p.bounds.x + 12; val y = node.bounds.y + 22
                g.drawLine(x, p.bounds.y + p.bounds.height, x, y); g.drawLine(x, y, node.bounds.x, y)
            } }
            val clip = g.clipBounds
            for (node in nodes) {
                val b = node.bounds
                if (clip != null && !clip.intersects(b)) continue
                val call = node.call; val data = presentations.getValue(call.id())
                g.color = if (call.id() == selected) JBColor(Color(223,236,255),Color(52,72,99)) else JBColor(Color.WHITE,Color(47,49,53))
                g.fillRoundRect(b.x,b.y,b.width,b.height,12,12)
                g.color = statusColor(call)
                g.stroke = if (node.contextOnly) BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(4f,4f), 0f)
                    else BasicStroke(if (call.id() == selected) 2f else 1f)
                g.drawRoundRect(b.x,b.y,b.width,b.height,12,12); g.stroke = BasicStroke(1f)
                g.font = (UIManager.getFont("Label.font") ?: font).deriveFont(Font.BOLD,12f)
                if (node.hasChildren) g.drawString(if (node.collapsed) "+" else "−", b.x+10, b.y+21)
                drawText(g, "#${call.id()}  ${call.className().substringAfterLast('.')}.${call.method()}", b.x+30,b.y+21,b.width-42)
                g.font = g.font.deriveFont(Font.PLAIN,11f); g.color = JBColor.foreground()
                drawText(g, "${statusIcon(call)} ${call.status()} · ${CallPresentation.duration(call)} ms" +
                    (if (node.contextOnly) " · caller path" else ""), b.x+12,b.y+40,b.width-24)
                drawText(g, "In: ${data.inputSummary}", b.x+12,b.y+58,b.width-24)
                drawText(g, "Out: ${data.resultSummary}", b.x+12,b.y+76,b.width-24)
                g.color = JBColor.GRAY
                val footer = if (node.collapsed) "${node.hiddenCalls} hidden calls · ${node.hiddenErrors} errors"
                    else (if (data.badge.isEmpty()) "" else "${data.badge} · ") + "thread ${call.threadId()} · ${call.threadName()}"
                drawText(g,footer,b.x+12,b.y+96,b.width-24)
            }
        } finally { g.dispose() }
    }
    companion object {
        fun statusColor(call: RecordedCall): Color = when (call.status()) {
            "ERROR" -> JBColor(Color(174,52,52),Color(245,128,128))
            "SUCCESS" -> JBColor(Color(37,114,78),Color(97,185,142))
            "INCOMPLETE" -> JBColor(Color(147,100,15),Color(230,181,79))
            else -> JBColor(Color(55,99,154),Color(128,173,232))
        }
        fun statusIcon(call: RecordedCall) = when (call.status()) { "SUCCESS" -> "✓"; "ERROR" -> "!"; "INCOMPLETE" -> "?"; else -> "◷" }
        fun drawText(g: Graphics2D, text: String, x: Int, y: Int, width: Int) {
            val metrics = g.fontMetrics
            val singleLine = text.replace('\n',' ').replace('\r',' ')
            var end = singleLine.length
            if (metrics.stringWidth(singleLine) > width) {
                val limit = width - metrics.stringWidth("…")
                var used = 0; end = 0
                while (end < singleLine.length && used + metrics.charWidth(singleLine[end]) <= limit) { used += metrics.charWidth(singleLine[end]); end++ }
            }
            g.drawString(singleLine.take(end) + if (end < singleLine.length) "…" else "", x, y)
        }
    }
}
