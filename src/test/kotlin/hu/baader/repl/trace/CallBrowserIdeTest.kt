package hu.baader.repl.trace

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import hu.baader.repl.editor.InlineResultRenderer
import hu.baader.repl.nrepl.NreplService
import java.awt.Component
import java.awt.Container
import java.awt.Point
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.*
import kotlin.math.roundToInt

class CallBrowserIdeTest : BasePlatformTestCase() {
    private fun descendants(root: Container): List<Component> = root.components.flatMap { listOf(it)+if(it is Container) descendants(it) else emptyList() }
    private fun layout(root: Container) { root.doLayout();root.components.filterIsInstance<Container>().forEach(::layout) }
    private fun size(panel: Container,width: Int,height: Int=900) { panel.setSize(width,height);repeat(12) { layout(panel) } }
    private fun button(panel: Container,label: String) = descendants(panel).filterIsInstance<JButton>().single { it.text==label }
    private fun source(): CapturedSource {
        val file=myFixture.addFileToProject("example/Service.java","package example;\npublic class Service {\n public int run(int amount) { return amount+1; }\n}\n")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        return RecordingSource.capture(project,listOf("example.Service")).single()
    }
    private fun render(panel: JPanel,name: String,width: Int,height: Int=900) {
        size(panel,width,height)
        val image=BufferedImage(width,height,BufferedImage.TYPE_INT_RGB)
        val g=image.createGraphics()
        try { panel.printAll(g) } finally { g.dispose() }
        System.getProperty("sb.repl.uiRenderDir")?.let {
            Files.createDirectories(Path.of(it));ImageIO.write(image,"png",Path.of(it,"$name-$width.png").toFile())
        }
    }
    fun testZoomHitTestingPanRefreshAndFitWithTwoHundredCalls() {
        var selected: Long?=null
        val graph=CallGraph({ selected=it.id() })
        val pane=JScrollPane(graph)
        val calls=(1L..200).map { BrowserFixture.call(it,if(it==1L) 0 else it-1,1) }
        graph.display(calls,null,null);size(pane,600,380)
        pane.viewport.viewPosition=Point(60,200)
        val cursor=Point(210,300)
        graph.setZoom(2.0,cursor)
        assertEquals(Point(270,500),pane.viewport.viewPosition)
        val position=Point(pane.viewport.viewPosition)
        graph.display(calls.toList(),null,null);repeat(3) { layout(pane) }
        assertEquals(2.0,graph.zoom);assertEquals(position,pane.viewport.viewPosition)
        val node=graph.nodes[2]
        val p=Point(((node.bounds.x+100)*graph.zoom).roundToInt(),((node.bounds.y+50)*graph.zoom).roundToInt())
        graph.dispatchEvent(MouseEvent(graph,MouseEvent.MOUSE_CLICKED,1,0,p.x,p.y,1,false,MouseEvent.BUTTON1))
        assertEquals(3L,selected)
        assertTrue(pane.viewport.viewRect.intersects(java.awt.Rectangle((node.bounds.x*2),(node.bounds.y*2),920,216)))
        val old=Point(pane.viewport.viewPosition)
        val press=Point(old.x+100,old.y+100)
        graph.dispatchEvent(MouseEvent(graph,MouseEvent.MOUSE_PRESSED,1,InputEvent.BUTTON1_DOWN_MASK,press.x,press.y,1,false,MouseEvent.BUTTON1))
        graph.dispatchEvent(MouseEvent(graph,MouseEvent.MOUSE_DRAGGED,2,InputEvent.BUTTON1_DOWN_MASK,press.x-40,press.y-50,0,false,MouseEvent.NOBUTTON))
        graph.dispatchEvent(MouseEvent(graph,MouseEvent.MOUSE_RELEASED,3,0,press.x-40,press.y-50,1,false,MouseEvent.BUTTON1))
        assertEquals(Point(old.x+40,old.y+50),pane.viewport.viewPosition)
        graph.fitAll()
        assertTrue(graph.preferredSize.height<=pane.viewport.extentSize.height)
        assertTrue(graph.preferredSize.width<=pane.viewport.extentSize.width)
        assertEquals(Point(0,0),pane.viewport.viewPosition)
        val oldZoom=graph.zoom
        graph.dispatchEvent(MouseWheelEvent(graph,MouseEvent.MOUSE_WHEEL,4,InputEvent.CTRL_DOWN_MASK,100,100,0,false,MouseWheelEvent.WHEEL_UNIT_SCROLL,3,-1))
        assertTrue(graph.zoom>oldZoom)
        graph.collapseAll();assertEquals(1,graph.nodes.size);assertEquals(199,graph.nodes.single().hiddenCalls)
        graph.revealSelection(true);assertTrue(graph.nodes.any { it.call.id()==3L })
    }
    fun testNavigationComparisonFilteringTimelineAndResponsiveControlsUseCapturedCalls() {
        val source=source();val controller=RecordingController.get(project)
        val panel=RecordingPanel(project) { fail("History browsing must not inspect live values") }
        try {
            controller.open(CallRecording(BrowserFixture.id,BrowserFixture.calls(),listOf(source)))
            size(panel,1280)
            val graph=descendants(panel).filterIsInstance<CallGraph>().single()
            val timeline=descendants(panel).filterIsInstance<CallTimeline>().single()
            controller.followLatest=true
            graph.setZoom(1.2)
            assertFalse("Manual navigation must suspend auto-follow",controller.followLatest)
            graph.setZoom(1.0)
            button(panel,"Next").doClick();assertEquals(2L,controller.selected)
            button(panel,"Pin reference").doClick()
            button(panel,"Next error").doClick();assertEquals(3L,controller.selected)
            assertFalse(controller.followLatest)
            assertTrue(myFixture.editor.inlayModel.getBlockElementsInRange(0,myFixture.editor.document.textLength).any { it.renderer is InlineResultRenderer })
            val table=descendants(panel).filterIsInstance<JTable>().single()
            assertTrue(table.rowCount>0)
            assertTrue((0 until table.rowCount).any { table.getValueAt(it,0).toString().startsWith("input") })
            render(panel,"CallBrowser-compare",1280)
            button(panel,"Caller").doClick();assertEquals(2L,controller.selected)
            button(panel,"Previous").doClick();assertEquals(1L,controller.selected)
            button(panel,"Next error").doClick();assertEquals(3L,controller.selected)
            val errors=descendants(panel).filterIsInstance<JCheckBox>().single { it.text=="Errors only" }
            errors.doClick();assertEquals(listOf(1L,2L,3L),graph.nodes.map { it.call.id() })
            assertTrue(graph.nodes.first().contextOnly)
            render(panel,"CallBrowser-errors",1280)
            button(panel,"Clear filters").doClick()
            val graphTabs=descendants(panel).filterIsInstance<JTabbedPane>().single { (0 until it.tabCount).any { i -> it.getTitleAt(i)=="Timeline" } }
            graphTabs.selectedIndex=1;size(panel,1280)
            val bar=timeline.layoutData.bars.single { it.call.id()==4L }
            val p=Point(bar.bounds.x+2,bar.bounds.y+8)
            timeline.dispatchEvent(MouseEvent(timeline,MouseEvent.MOUSE_PRESSED,1,0,p.x,p.y,1,false,MouseEvent.BUTTON1))
            timeline.dispatchEvent(MouseEvent(timeline,MouseEvent.MOUSE_RELEASED,2,0,p.x,p.y,1,false,MouseEvent.BUTTON1))
            assertEquals(4L,controller.selected)
            val a=timeline.layoutData.xAt(100.0);val b=timeline.layoutData.xAt(120.0)
            timeline.dispatchEvent(MouseEvent(timeline,MouseEvent.MOUSE_PRESSED,3,0,a,40,1,false,MouseEvent.BUTTON1))
            timeline.dispatchEvent(MouseEvent(timeline,MouseEvent.MOUSE_DRAGGED,4,InputEvent.BUTTON1_DOWN_MASK,b,40,0,false,MouseEvent.NOBUTTON))
            timeline.dispatchEvent(MouseEvent(timeline,MouseEvent.MOUSE_RELEASED,5,0,b,40,1,false,MouseEvent.BUTTON1))
            assertTrue(button(panel,"Clear time range").isEnabled)
            assertEquals(listOf(1L,4L),graph.nodes.map { it.call.id() })
            render(panel,"CallBrowser-timeline",1280)
            button(panel,"Clear time range").doClick();assertEquals(6,graph.nodes.size)
            graphTabs.selectedIndex=0
            val detailTabs=descendants(panel).filterIsInstance<JTabbedPane>().single { (0 until it.tabCount).any { i -> it.getTitleAt(i)=="Compare calls" } }
            detailTabs.selectedIndex=0
            controller.select(1);graph.collapseAll()
            render(panel,"CallBrowser-folded",1280)
            graph.expandAll();controller.select(3)
            for(width in listOf(700,1280)) {
                render(panel,"CallBrowser",width)
                assertTrue(graph.parent.height>=100)
                val detach=button(panel,"Detach window…")
                assertTrue("Detach control clipped at $width",detach.y+detach.height<=detach.parent.height)
                val thread=descendants(panel).filterIsInstance<JComboBox<*>>().single { it.getItemAt(0).toString()=="All threads" }
                assertTrue(thread.y+thread.height<=thread.parent.height)
            }
            assertFalse(NreplService.getInstance(project).isConnected())
        } finally { Disposer.dispose(panel) }
    }
    fun testSearchIsDebouncedAndRevealsCapturedValuesAcrossClosedBranches() {
        val controller=RecordingController.get(project);val panel=RecordingPanel(project) {}
        try {
            controller.open(CallRecording(BrowserFixture.id,BrowserFixture.calls()))
            size(panel,1100)
            button(panel,"Collapse all").doClick()
            val search=descendants(panel).filterIsInstance<JTextField>().single { it.accessibleContext.accessibleName=="Search recorded calls" }
            search.text="Árvíz"
            val until=System.nanoTime()+2_000_000_000
            val graph=descendants(panel).filterIsInstance<CallGraph>().single()
            while(graph.nodes.size!=3 && System.nanoTime()<until) { PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();Thread.sleep(10) }
            assertEquals(listOf(1L,2L,3L),graph.nodes.map { it.call.id() })
            assertFalse(graph.nodes.any { it.collapsed })
        } finally { Disposer.dispose(panel) }
    }
}
