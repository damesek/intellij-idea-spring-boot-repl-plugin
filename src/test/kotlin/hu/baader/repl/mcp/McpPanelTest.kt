package hu.baader.repl.mcp

import org.junit.Assert.*
import org.junit.Test
import java.awt.Container
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.*

class McpPanelTest {
    @Test fun rememberedAndAllocatedPortsRemainVisibleAfterStop() {
        SwingUtilities.invokeAndWait {
            var requested = -1
            val panel = McpPanel({ port, _ -> requested = port }, {}, { null }, initialPort = 54321)
            fun descendants(root: Container): List<java.awt.Component> = root.components.flatMap {
                listOf(it) + if (it is Container) descendants(it) else emptyList()
            }
            try {
                val start = descendants(panel).filterIsInstance<JButton>().single { it.text == "Start MCP" }
                val port = descendants(panel).filterIsInstance<JSpinner>().first()
                start.doClick()
                assertEquals(54321, requested)
                port.value = 0
                start.doClick()
                assertEquals(0, requested)
                panel.showState(McpState(url = "http://127.0.0.1:54322/mcp", port = 54322))
                assertEquals(54322, port.value)
                panel.showState(McpState())
                start.doClick()
                assertEquals(54322, requested)
            } finally { panel.dispose() }
        }
    }

    @Test fun buttonsPassPermissionsCopyConfigAndStayReachable() {
        SwingUtilities.invokeAndWait {
            var selected: McpPermissions? = null
            var selectedPort = -1
            var stopped = false
            var copied = ""
            val panel = McpPanel({ port, permission -> selectedPort = port; selected = permission }, { stopped = true }, { "test-config" }, { copied = it })
            fun descendants(root: Container): List<java.awt.Component> = root.components.flatMap { listOf(it) + if (it is Container) descendants(it) else emptyList() }
            fun button(label: String) = descendants(panel).filterIsInstance<JButton>().first { it.text == label }
            fun layout(root: Container) { root.doLayout(); root.components.filterIsInstance<Container>().forEach(::layout) }
            try {
                assertFalse(button("Copy client config").isEnabled)
                button("Start MCP").doClick(); assertEquals(0, selectedPort); assertEquals(McpPermissions(), selected)
                val sharing = descendants(panel).filterIsInstance<JCheckBox>().single { it.text == "Share IDE recordings with MCP" }
                sharing.doClick(); button("Start MCP").doClick()
                assertEquals(McpPermissions(recordingAccess=true), selected)
                panel.showState(McpState(url = "http://127.0.0.1:1234/mcp", clients = 1, detail = "Running"))
                assertFalse(sharing.isEnabled)
                assertFalse(button("Start MCP").isEnabled)
                button("Copy client config").doClick(); assertEquals("test-config", copied)
                button("Stop MCP").doClick(); assertTrue(stopped)
                button("Advanced settings…").doClick()
                for (width in listOf(700, 1000)) {
                    panel.setSize(width, 720); repeat(5) { layout(panel) }
                    assertFalse("MCP settings must fit the available width", descendants(panel).filterIsInstance<JScrollPane>().single().horizontalScrollBar.isVisible)
                    val image = BufferedImage(width, 720, BufferedImage.TYPE_INT_RGB)
                    image.createGraphics().let { try { panel.printAll(it) } finally { it.dispose() } }
                    for (button in descendants(panel).filterIsInstance<AbstractButton>().filter { it.text?.isNotBlank() == true }) {
                        val bounds = SwingUtilities.convertRectangle(button.parent, button.bounds, panel)
                        assertTrue("${button.text} at $width: $bounds", bounds.width > 0 && bounds.height > 0 && panel.bounds.contains(bounds))
                    }
                    System.getProperty("sb.repl.uiRenderDir")?.let { directory ->
                        val path = Path.of(directory); Files.createDirectories(path)
                        ImageIO.write(image, "png", path.resolve("McpPanel-$width.png").toFile())
                    }
                }
            } finally { panel.dispose() }
        }
    }

    @Test fun collapsingAdvancedSettingsKeepsTheirValuesAndRunningServerLocksThem() {
        SwingUtilities.invokeAndWait {
            var selected: McpPermissions? = null
            val panel = McpPanel({ _, permission -> selected = permission }, {}, { null })
            fun descendants(root: Container): List<java.awt.Component> = root.components.flatMap { listOf(it) + if (it is Container) descendants(it) else emptyList() }
            fun button(label: String) = descendants(panel).filterIsInstance<JButton>().single { it.text == label }
            try {
                button("Advanced settings…").doClick()
                val timeout = descendants(panel).filterIsInstance<JSpinner>()[1]
                timeout.value = 15000
                button("Hide advanced settings").doClick()
                button("Start MCP").doClick()
                assertEquals(McpPermissions(timeoutMillis = 15000), selected)
                panel.showState(McpState(url = "http://127.0.0.1:1234/mcp"))
                button("Advanced settings…").doClick()
                assertFalse(timeout.isEnabled)
                assertEquals(15000, timeout.value)
                assertFalse(button("Choose allowed tools").isEnabled)
                panel.showState(McpState())
                assertTrue(timeout.isEnabled)
            } finally { panel.dispose() }
        }
    }
}
