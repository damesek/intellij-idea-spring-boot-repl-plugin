package hu.baader.repl.editor

import org.junit.Assert.*
import org.junit.Test
import java.awt.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

class InlineResultRendererTest {
    @Test fun resultActionsUseTheStoredResultAndRemainInsideNarrowEditors() {
        var runs=0; var snapshots=0; var enabled=true
        val renderer=InlineResultRenderer("Cell 2 · [12] · success · 18 ms", "{customer: Árvíz 東京, items: [1, 2, 3]}", "Input cell(s) 1 changed", false, true,
            listOf(InlineResultRenderer.Link("Run") { runs++ }, InlineResultRenderer.Link("Inspect") {},
                InlineResultRenderer.Link("Snapshot", {enabled}) { snapshots++ }, InlineResultRenderer.Link("Output") {}))
        for(width in listOf(260,450,700)) {
            val image=BufferedImage(width,110,BufferedImage.TYPE_INT_RGB); val graphics=image.createGraphics()
            try { graphics.color=Color.WHITE; graphics.fillRect(0,0,width,110); renderer.paintPreview(graphics,Rectangle(0,0,width,105),Font(Font.MONOSPACED,Font.PLAIN,13),22) }
            finally { graphics.dispose() }
            for(label in listOf("Run","Inspect","Snapshot","Output")) renderer.bounds(label)?.let { assertTrue("$label exceeds $width px: $it",Rectangle(0,0,width,110).contains(it)) }
            renderer.bounds("Snapshot")?.let { rect ->
                val point=Point(rect.x+rect.width/2,rect.y+rect.height/2)
                assertTrue(renderer.click(point.x,point.y)); enabled=false; renderer.click(point.x,point.y); enabled=true
            }
            System.getProperty("sb.repl.uiRenderDir")?.let { directory -> Files.createDirectories(Path.of(directory)); ImageIO.write(image,"png",Path.of(directory,"InlineResult-$width.png").toFile()) }
        }
        assertEquals(0,runs); assertEquals(2,snapshots)
    }
}
