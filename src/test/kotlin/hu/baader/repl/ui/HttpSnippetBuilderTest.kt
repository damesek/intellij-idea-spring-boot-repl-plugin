package hu.baader.repl.ui

import com.baader.devrt.JShellSession
import org.junit.Assert.*
import org.junit.Test

class HttpSnippetBuilderTest {
    @Test fun helperCompilesAsJavaAndPreservesQuotedUnicodeData() {
        val case = HttpRequestCase(id = "quotes\"\\árvíz", method = "POST", url = "https://localhost/", body = "{\"text\":\"árvíz\"}\n\t")
        JShellSession(null).use { shell ->
            val definitions = shell.eval(HttpSnippetBuilder.definitions(listOf(case)))
            assertEquals("", definitions.error())
            val full = shell.eval(HttpSnippetBuilder.build(emptyList(), case.id))
            assertTrue(full.error(), full.error().contains("Unknown HTTP case"))
            assertFalse(full.error().contains("not a statement"))
        }
    }
    @Test fun environmentPlaceholderIsResolvedOnlyWhenTheWorkbookRuns() {
        val placeholder = "$" + "{SB_REPL_MISSING_TEST_SECRET_4719}"
        val code = HttpSnippetBuilder.build(listOf(HttpRequestCase(id="env",url=placeholder)), "env")
        assertTrue(code.contains(placeholder))
        JShellSession(null).use { shell ->
            val result = shell.eval(code)
            assertTrue(result.error(), result.error().contains("Missing environment variable"))
        }
    }
    @Test fun duplicateCasesAndInvalidMethodsAreRejectedBeforeInsertion() {
        assertThrows(IllegalArgumentException::class.java) { HttpSnippetBuilder.definitions(listOf(HttpRequestCase(id="same"),HttpRequestCase(id="same"))) }
        assertThrows(IllegalArgumentException::class.java) { HttpSnippetBuilder.definitions(listOf(HttpRequestCase(id="x",method="GET\"bad"))) }
    }
}
