package hu.baader.repl.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import hu.baader.repl.nrepl.NreplService
import org.junit.Assert.assertTrue
import org.junit.Test
import hu.baader.repl.protocol.ValueTree
import java.awt.Container
import java.awt.image.BufferedImage
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingUtilities

class InteractivePanelsTest {
    @Test fun structuredJsonAndObjectViewsPaintWithExpandableTrees() {
        SwingUtilities.invokeAndWait {
            val panel = StructuredValuePanel()
            val json = "{\"customer\":{\"name\":\"Árvíz 東京\",\"active\":true},\"items\":[{\"id\":42,\"amount\":12345.67},null]}"
            panel.showValue(mapOf("view-data" to ValueTree.leaf("result","STRING","String",json).encode()))
            val tree = descendants(panel).filterIsInstance<javax.swing.JTree>().single()
            val initial = tree.rowCount
            descendants(panel).filterIsInstance<JButton>().single { it.text == "Expand" }.doClick()
            assertTrue(tree.rowCount > initial)
            panel.setSize(700, 600); repeat(8) { layoutTree(panel) }
            val image = BufferedImage(700, 600, BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            try { panel.printAll(graphics) } finally { graphics.dispose() }
            System.getProperty("sb.repl.uiRenderDir")?.let { directory ->
                val path=Path.of(directory); Files.createDirectories(path)
                ImageIO.write(image,"png",path.resolve("StructuredValuePanel-json.png").toFile())
            }
            descendants(panel).filterIsInstance<JButton>().single { it.text == "Collapse" }.doClick()
            assertTrue(tree.rowCount <= initial)
            panel.clear()
        }
    }
    @Test fun disconnectedToolsConstructPaintAndDisposeWithReachableControls() {
        val project = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Project::class.java)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "toString", "getName" -> "REPL UI test"
                else -> if (method.returnType == Boolean::class.javaPrimitiveType) false else null
            }
        } as Project
        val service = NreplService(project)
        // Construct, lay out and dispose on one EDT turn; no real IDE/debugger or timer polling is needed.
        SwingUtilities.invokeAndWait {
            // Tap / Trace owns project services and is covered by RecordingIdeTest in a real IDE fixture.
            val panels = listOf<JPanel>(InspectorPanel(service) {},
                SnapshotCasesPanel(project, service) {}, InteractiveDebuggerPanel(project, service) {},
                ExecutionPolicyToolbar(service, { "1+1" }) {}, SnapshotTriggerPanel({service}) {}, SnapshotVersionsPanel({ service }) {})
            try {
                for (panel in panels) for (width in listOf(900, 700)) {
                    panel.setSize(width, 800)
                    repeat(8) { layoutTree(panel) }
                    val image = BufferedImage(width, 800, BufferedImage.TYPE_INT_RGB)
                    val graphics = image.createGraphics()
                    try { panel.printAll(graphics) } finally { graphics.dispose() }
                    for (button in descendants(panel).filterIsInstance<JButton>().filter { it.text?.isNotEmpty() == true }) {
                        val bounds = SwingUtilities.convertRectangle(button.parent, button.bounds, panel)
                        assertTrue("${panel.javaClass.simpleName}: ${button.text} must remain reachable at width $width ($bounds)",
                            bounds.width > 0 && bounds.height > 0 && panel.bounds.contains(bounds))
                    }
                    System.getProperty("sb.repl.uiRenderDir")?.let { directory ->
                        val path = Path.of(directory); Files.createDirectories(path)
                        ImageIO.write(image, "png", path.resolve("${panel.javaClass.simpleName}-$width.png").toFile())
                    }
                }
            } finally { panels.forEach { (it as Disposable).dispose() } }
        }
    }
    @Test fun notebookPanelShowsIndividualEvidenceAtNarrowWidth() {
        SwingUtilities.invokeAndWait {
            val state = hu.baader.repl.editor.NotebookState()
            state.sync("// %% Input\nint amount = 1;\n// %% Result\namount + 1")
            val cell = state.cells[0]; state.finish(cell.id,state.begin(cell.id),cell.source,"amount ==> 1",false,12)
            state.sync(state.text.replace("amount = 1", "amount = 2"))
            val panel=NotebookPanel({ state }) {};panel.refresh();panel.setSize(700,600);repeat(8) { layoutTree(panel) }
            val table=descendants(panel).filterIsInstance<javax.swing.JTable>().single()
            assertTrue(table.getValueAt(0,2).toString().contains("modified"))
            val image=BufferedImage(700,600,BufferedImage.TYPE_INT_RGB);val graphics=image.createGraphics()
            try { panel.printAll(graphics) } finally { graphics.dispose() }
            System.getProperty("sb.repl.uiRenderDir")?.let { directory -> ImageIO.write(image,"png",Path.of(directory,"NotebookPanel-700.png").toFile()) }
        }
    }
    private fun descendants(root: Container): List<java.awt.Component> = root.components.flatMap {
        listOf(it) + if (it is Container) descendants(it) else emptyList()
    }
    private fun layoutTree(root: Container) { root.doLayout(); root.components.filterIsInstance<Container>().forEach(::layoutTree) }
}
