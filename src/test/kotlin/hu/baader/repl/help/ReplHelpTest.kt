package hu.baader.repl.help

import hu.baader.repl.actions.OpenReplHelpAction
import com.intellij.openapi.project.DumbAware
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory

class ReplHelpTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun bundledPdfExtractsOfflineAndReusesAnUnchangedCopy() {
        val directory = temp.root.toPath().resolve("IDE cache with spaces/árvíztűrő")
        val file = ReplHelp.extract(directory)
        val bytes = javaClass.getResourceAsStream(ReplHelp.RESOURCE)!!.use { it.readAllBytes() }
        assertArrayEquals(bytes, Files.readAllBytes(file))
        assertTrue(file.fileName.toString().endsWith(".pdf"))
        val modified = Files.getLastModifiedTime(file)
        assertEquals(file, ReplHelp.extract(directory))
        assertEquals(modified, Files.getLastModifiedTime(file))
        assertEquals(1L, Files.list(directory).use { it.count() })
    }

    @Test fun damagedCachedCopyIsReplacedWithoutTouchingOtherFiles() {
        val directory = temp.root.toPath()
        val unrelated = Files.writeString(directory.resolve("notes.txt"), "keep")
        val file = ReplHelp.extract(directory)
        val expected = Files.readAllBytes(file)
        Files.write(file, ByteArray(expected.size))
        assertEquals(file, ReplHelp.extract(directory))
        assertArrayEquals(expected, Files.readAllBytes(file))
        assertEquals("keep", Files.readString(unrelated))
        assertEquals(2L, Files.list(directory).use { it.count() })
    }

    @Test fun helpIsInTheExistingMenuAndAvailableDuringIndexingWithoutAConnection() {
        val xml = javaClass.getResourceAsStream("/META-INF/plugin.xml")!!.use {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it)
        }
        val actions = xml.getElementsByTagName("action")
        val action = (0 until actions.length).map { actions.item(it) as org.w3c.dom.Element }
            .single { it.getAttribute("id") == "hu.baader.repl.OpenHelp" }
        assertEquals(OpenReplHelpAction::class.java.name, action.getAttribute("class"))
        assertEquals("hu.baader.repl.SpringReplGroup", (action.getElementsByTagName("add-to-group").item(0) as org.w3c.dom.Element).getAttribute("group-id"))
        assertTrue(DumbAware::class.java.isAssignableFrom(OpenReplHelpAction::class.java))
        assertNotNull(OpenReplHelpAction())
    }
}
