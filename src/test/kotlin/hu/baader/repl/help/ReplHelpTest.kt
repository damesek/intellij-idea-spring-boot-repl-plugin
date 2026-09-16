package hu.baader.repl.help

import hu.baader.repl.actions.OpenReplHelpAction
import hu.baader.repl.actions.OpenEnglishReplHelpAction
import hu.baader.repl.actions.OpenHungarianReplHelpAction
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
        for (language in ReplHelp.Language.values()) {
            val file = ReplHelp.extract(directory, language)
            val bytes = javaClass.getResourceAsStream(language.resource)!!.use { it.readAllBytes() }
            assertArrayEquals(bytes, Files.readAllBytes(file))
            assertTrue(file.fileName.toString().startsWith("spring-boot-repl-guide-${language.code}-"))
            assertTrue(file.fileName.toString().endsWith(".pdf"))
            val modified = Files.getLastModifiedTime(file)
            assertEquals(file, ReplHelp.extract(directory, language))
            assertEquals(modified, Files.getLastModifiedTime(file))
        }
        assertEquals(2L, Files.list(directory).use { it.count() })
    }

    @Test fun damagedCachedCopyIsReplacedWithoutTouchingOtherFiles() {
        val directory = temp.root.toPath()
        val unrelated = Files.writeString(directory.resolve("notes.txt"), "keep")
        val otherLanguage = ReplHelp.extract(directory, ReplHelp.Language.HUNGARIAN)
        val otherBytes = Files.readAllBytes(otherLanguage)
        val otherModified = Files.getLastModifiedTime(otherLanguage)
        val file = ReplHelp.extract(directory, ReplHelp.Language.ENGLISH)
        val expected = Files.readAllBytes(file)
        Files.write(file, ByteArray(expected.size))
        assertEquals(file, ReplHelp.extract(directory, ReplHelp.Language.ENGLISH))
        assertArrayEquals(expected, Files.readAllBytes(file))
        assertArrayEquals(otherBytes, Files.readAllBytes(otherLanguage))
        assertEquals(otherModified, Files.getLastModifiedTime(otherLanguage))
        assertEquals("keep", Files.readString(unrelated))
        assertEquals(3L, Files.list(directory).use { it.count() })
    }

    @Test fun helpIsInTheExistingMenuAndAvailableDuringIndexingWithoutAConnection() {
        val xml = javaClass.getResourceAsStream("/META-INF/plugin.xml")!!.use {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it)
        }
        val actions = xml.getElementsByTagName("action")
        val action = (0 until actions.length).map { actions.item(it) as org.w3c.dom.Element }
            .single { it.getAttribute("id") == "hu.baader.repl.OpenHelp" }
        assertEquals(OpenReplHelpAction::class.java.name, action.getAttribute("class"))
        assertTrue(DumbAware::class.java.isAssignableFrom(OpenReplHelpAction::class.java))
        assertNotNull(OpenReplHelpAction())
        val groups = xml.getElementsByTagName("group")
        val group = (0 until groups.length).map { groups.item(it) as org.w3c.dom.Element }
            .single { it.getAttribute("id") == "hu.baader.repl.HelpGuides" }
        assertEquals("true", group.getAttribute("popup"))
        assertEquals("Help (PDF)", group.getAttribute("text"))
        assertEquals("hu.baader.repl.SpringReplGroup", (group.getElementsByTagName("add-to-group").item(0) as org.w3c.dom.Element).getAttribute("group-id"))
        val languageActions = group.getElementsByTagName("action")
        val classes = (0 until languageActions.length).map { (languageActions.item(it) as org.w3c.dom.Element).getAttribute("class") }.toSet()
        assertEquals(setOf(OpenEnglishReplHelpAction::class.java.name, OpenHungarianReplHelpAction::class.java.name), classes)
        assertTrue(DumbAware::class.java.isAssignableFrom(OpenEnglishReplHelpAction::class.java))
        assertTrue(DumbAware::class.java.isAssignableFrom(OpenHungarianReplHelpAction::class.java))
        assertNotNull(OpenEnglishReplHelpAction())
        assertNotNull(OpenHungarianReplHelpAction())
    }
}
