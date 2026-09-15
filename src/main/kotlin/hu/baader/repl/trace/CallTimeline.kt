package hu.baader.repl.trace

import com.intellij.ui.JBColor
import hu.baader.repl.protocol.RecordedCall
import java.awt.*
import java.awt.event.*
import java.util.Locale
import javax.swing.*
import kotlin.math.abs
import kotlin.math.roundToInt

object CallTimelineLayout {
    const val LEFT = 174
    data class Bar(val call: RecordedCall, val bounds: Rectangle, val startMs: Double, val endMs: Double)
    data class Lane(val thread: Long, val name: String, val y: Int)
    data class Layout(val origin: Long, val endMs: Double, val bars: List<Bar>, val lanes: List<Lane>, val height: Int, val plotWidth: Int) {
        fun timeAt(x: Int) = ((x - LEFT).toDouble() / plotWidth * endMs).coerceIn(0.0, endMs)
        fun xAt(ms: Double) = LEFT + (ms / endMs * plotWidth).roundToInt()
    }
    fun layout(calls: List<RecordedCall>, width: Int): Layout {
        val origin = calls.minOfOrNull { it.startedAt() } ?: 0
        val end = (calls.maxOfOrNull { (it.startedAt()-origin).toDouble() + it.durationNanos()/1_000_000.0 } ?: 1.0).coerceAtLeast(1.0)
        val plotWidth = (width - LEFT - 20).coerceAtLeast(120)
        val bars = mutableListOf<Bar>(); val lanes = mutableListOf<Lane>()
        var y = 44
        calls.groupBy { it.threadId() }.toSortedMap().forEach { (thread, group) ->
            lanes += Lane(thread, group.first().threadName(), y)
            val occupied = mutableMapOf<Int,Double>()
            group.sortedBy { it.id() }.forEach { call ->
                val start = (call.startedAt()-origin).toDouble()
                val finish = start + call.durationNanos()/1_000_000.0
                var row = (CallNavigation.path(calls,call.id()).size - 1).coerceAtLeast(0)
                // Separate overlapping/recursive calls even when millisecond timestamps coincide.
                while ((occupied[row] ?: -1.0) > start) row++
                occupied[row] = maxOf(finish, start + end * 4 / plotWidth)
                val x = LEFT + (start/end*plotWidth).roundToInt()
                val right = LEFT + (finish/end*plotWidth).roundToInt()
                bars += Bar(call,Rectangle(x,y+row*30,(right-x).coerceAtLeast(4),24),start,finish)
            }
            y += ((occupied.keys.maxOrNull() ?: 0) + 1) * 30 + 24
        }
        return Layout(origin,end,bars,lanes,y+24,plotWidth)
    }
}

/** Time is recorded wall-clock start + measured elapsed duration; no inferred async links. */
class CallTimeline(private val select: (Long) -> Unit, private val selectRange: (CallTimeRange?) -> Unit) : JComponent(), Scrollable {
    var layoutData = CallTimelineLayout.layout(emptyList(),800); private set
    private var calls = emptyList<RecordedCall>()
    private var presentations = emptyMap<Long,CallPresentation>()
    private var selected: Long? = null
    private var range: CallTimeRange? = null
    private var matches = emptySet<Long>()
    private var dragStart: Int? = null
    private var dragEnd: Int? = null
    init {
        isOpaque = true; toolTipText = "Click a call; drag across time to filter the call tree."
        accessibleContext?.accessibleName = "Recorded calls timeline by thread"
        val mouse = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e) && e.x >= CallTimelineLayout.LEFT) { dragStart=e.x;dragEnd=e.x }
            }
            override fun mouseDragged(e: MouseEvent) { if (dragStart != null) { dragEnd=e.x;repaint() } }
            override fun mouseReleased(e: MouseEvent) {
                val start = dragStart ?: return
                dragStart=null;dragEnd=null
                if (abs(e.x-start) >= 5) {
                    val a=layoutData.timeAt(start);val b=layoutData.timeAt(e.x)
                    selectRange(CallTimeRange(minOf(a,b),maxOf(a,b)))
                } else layoutData.bars.lastOrNull { it.bounds.contains(e.point) }?.let { select(it.call.id()) }
                repaint()
            }
        }
        addMouseListener(mouse);addMouseMotionListener(mouse)
    }
    fun display(calls: List<RecordedCall>, selected: Long?, range: CallTimeRange?, matches: Set<Long>, presentations: Map<Long,CallPresentation>) {
        this.calls=calls;this.selected=selected;this.range=range;this.matches=matches;this.presentations=presentations
        rebuild()
    }
    private fun rebuild() {
        layoutData=CallTimelineLayout.layout(calls,width.coerceAtLeast(320))
        preferredSize=Dimension(800,layoutData.height.coerceAtLeast(180))
        revalidate();repaint()
    }
    override fun doLayout() { super.doLayout(); if (layoutData.plotWidth != (width-CallTimelineLayout.LEFT-20).coerceAtLeast(120)) rebuild() }
    override fun getToolTipText(event: MouseEvent): String? = layoutData.bars.lastOrNull { it.bounds.contains(event.point) }?.let {
        "<html>#${it.call.id()} · ${CallPresentation.html(presentations[it.call.id()]?.signature.orEmpty())}<br>" +
            "${"%.3f".format(Locale.ROOT,it.startMs)}–${"%.3f".format(Locale.ROOT,it.endMs)} ms from recording start<br>" +
            "${CallPresentation.html(it.call.status())} · ${CallPresentation.duration(it.call)} ms<br>Click to open source; drag to select a time range.</html>"
    }
    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g=graphics.create() as Graphics2D
        try {
            g.color=JBColor.PanelBackground;g.fillRect(0,0,width,height)
            g.font=(UIManager.getFont("Label.font") ?: font).deriveFont(11f)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON)
            g.color=JBColor.GRAY
            for(i in 0..4) {
                val t=layoutData.endMs*i/4;val x=layoutData.xAt(t)
                g.drawLine(x,30,x,height-12)
                val label="${"%.1f".format(Locale.ROOT,t)} ms"
                g.drawString(label, (x-g.fontMetrics.stringWidth(label)/2).coerceIn(0,(width-g.fontMetrics.stringWidth(label)).coerceAtLeast(0)),20)
            }
            if(calls.isEmpty()) g.drawString("No recorded calls",16,60)
            layoutData.lanes.forEach {
                g.color=JBColor.foreground();CallGraph.drawText(g,"Thread ${it.thread}",8,it.y+13,156)
                g.color=JBColor.GRAY;CallGraph.drawText(g,it.name,8,it.y+27,156)
            }
            layoutData.bars.forEach {
                val b=it.bounds;val call=it.call
                g.composite=AlphaComposite.getInstance(AlphaComposite.SRC_OVER,if(call.id() in matches) 0.9f else 0.25f)
                g.color=CallGraph.statusColor(call);g.fillRoundRect(b.x,b.y,b.width,b.height,5,5)
                g.composite=AlphaComposite.SrcOver
                if(call.id()==selected) { g.color=JBColor.foreground();g.stroke=BasicStroke(2f);g.drawRoundRect(b.x-1,b.y-1,b.width+2,b.height+2,5,5);g.stroke=BasicStroke(1f) }
                if(b.width>38) { g.color=Color.WHITE;CallGraph.drawText(g,"${CallGraph.statusIcon(call)} #${call.id()} ${call.method()}",b.x+5,b.y+16,b.width-10) }
                else { g.color=JBColor.foreground();g.drawString(CallGraph.statusIcon(call),b.x,b.y+17) }
            }
            val selectedRange=if(dragStart != null && dragEnd != null) {
                val a=layoutData.timeAt(dragStart!!);val b=layoutData.timeAt(dragEnd!!)
                CallTimeRange(minOf(a,b),maxOf(a,b))
            } else range
            selectedRange?.let {
                val a=layoutData.xAt(it.startMs);val b=layoutData.xAt(it.endMs)
                g.color=JBColor(Color(60,112,196),Color(116,163,238));g.composite=AlphaComposite.getInstance(AlphaComposite.SRC_OVER,0.18f)
                g.fillRect(a,30,(b-a).coerceAtLeast(1),height-42)
                g.composite=AlphaComposite.SrcOver;g.drawRect(a,30,(b-a).coerceAtLeast(1),height-42)
            }
        } finally { g.dispose() }
    }
    override fun getPreferredScrollableViewportSize() = Dimension(800,240)
    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = 30
    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = (visibleRect.height-30).coerceAtLeast(30)
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = false
}
