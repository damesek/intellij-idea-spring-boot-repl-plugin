package hu.baader.repl.trace

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import hu.baader.repl.editor.InlineResultRenderer
import hu.baader.repl.nrepl.NreplService
import hu.baader.repl.protocol.RecordedCall
import hu.baader.repl.protocol.ValueTree
import hu.baader.repl.ui.RuntimeEventsPanel
import java.awt.Container
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.imageio.ImageIO
import javax.swing.*

class RecordingIdeTest : BasePlatformTestCase() {
    private val id=UUID.randomUUID().toString()
    private fun call(n: Long=1,parent: Long=0,descriptor: String="(I)I") = RecordedCall(id,n,parent,1,"example.Service","run",descriptor,"amount",1,"http-worker",1700000000000,1734000,"SUCCESS","42",
        ValueTree.leaf("amount","NUMBER","int","41").encode(),ValueTree.leaf("result","NUMBER","int","42").encode(),"",-1,n)
    private fun source(): CapturedSource {
        val file=myFixture.addFileToProject("example/Service.java","package example;\npublic class Service {\n public int run(int amount) { return amount+1; }\n public int run(int a,int b) { return a+b; }\n}\n")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        return RecordingSource.capture(project,listOf("example.Service")).single()
    }
    fun testSourceNavigationMatchesOverloadAndPreservesEditedFiles() {
        assertNotNull(ActionManager.getInstance().getAction("hu.baader.repl.RecordClassCalls"))
        val source=source(); val original=myFixture.editor.document.text
        assertTrue(RecordingSource.capture(project,listOf("missing.LibraryClass")).isEmpty())
        assertEquals(original.indexOf("public int run(int a,int b)"),source.methods["run(II)I"])
        val overlays=RecordedCallInlays.get(project)
        overlays.show(call(descriptor="(II)I"),source,true) {}
        val first=myFixture.editor.inlayModel.getBlockElementsInRange(0,myFixture.editor.document.textLength).single { it.renderer is InlineResultRenderer }
        assertEquals(source.methods["run(II)I"],first.offset)
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.setText("// changed but not committed\n$original") }
        val changed=myFixture.editor.document.text
        val note=overlays.show(call(),source,true) {}
        assertTrue(note.contains("read-only")); assertEquals(changed,myFixture.editor.document.text)
        val virtual=FileEditorManager.getInstance(project).selectedFiles.single { it.name.startsWith("Recorded-") }
        assertFalse(virtual.isWritable)
        val editor=EditorFactory.getInstance().allEditors.single { it.project==project && it.document.text==original }
        assertTrue(editor.inlayModel.getBlockElementsInRange(0,editor.document.textLength).any { it.offset==source.methods["run(I)I"] })
        overlays.clear()
        assertTrue(editor.inlayModel.getBlockElementsInRange(0,editor.document.textLength).isEmpty())
    }
    fun testRecordedGraphSelectionOpensSourceAndExpandableValuesOffline() {
        val source=source();val controller=RecordingController.get(project)
        val panel=RecordingPanel(project) { fail("Offline selection must not inspect live objects") }
        try {
            controller.open(CallRecording(id,listOf(call(),call(2,1)),listOf(source)))
            val graph=descendants(panel).filterIsInstance<CallGraph>().single()
            val point=graph.nodes[1].bounds.let { java.awt.Point(it.x+40,it.y+20) }
            graph.dispatchEvent(MouseEvent(graph,MouseEvent.MOUSE_CLICKED,System.currentTimeMillis(),0,point.x,point.y,1,false,MouseEvent.BUTTON1))
            assertEquals(2L,controller.selected);assertFalse(controller.live(controller.selectedCall()!!))
            assertTrue(myFixture.editor.inlayModel.getBlockElementsInRange(0,myFixture.editor.document.textLength).isNotEmpty())
            assertTrue(descendants(panel).filterIsInstance<JButton>().any { it.text=="Expand" })
            for(width in listOf(700,1200)) render(panel,"RecordedCalls",width)
            assertFalse(NreplService.getInstance(project).isConnected())
        } finally { Disposer.dispose(panel) }
    }
    fun testBothTapTraceViewsLoadAndDisposeInRealIde() {
        val panel=RuntimeEventsPanel(project,NreplService.getInstance(project),{})
        try {
            val tabs=panel.components.filterIsInstance<JTabbedPane>().single()
            assertEquals(2,tabs.tabCount)
            for(tab in 0 until tabs.tabCount) { tabs.selectedIndex=tab;render(panel,"TapTrace-$tab",900) }
        } finally { Disposer.dispose(panel) }
    }
    private fun descendants(root: Container): List<java.awt.Component> = root.components.flatMap { listOf(it)+if(it is Container) descendants(it) else emptyList() }
    private fun layout(root: Container) { root.doLayout();root.components.filterIsInstance<Container>().forEach(::layout) }
    private fun render(panel: JPanel,name: String,width: Int) {
        panel.setSize(width,760);repeat(8) { layout(panel) }
        val image=BufferedImage(width,760,BufferedImage.TYPE_INT_RGB);val g=image.createGraphics()
        try { panel.printAll(g) } finally { g.dispose() }
        System.getProperty("sb.repl.uiRenderDir")?.let { directory -> Files.createDirectories(Path.of(directory));ImageIO.write(image,"png",Path.of(directory,"$name-$width.png").toFile()) }
    }
}
