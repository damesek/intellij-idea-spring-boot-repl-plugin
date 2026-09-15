package hu.baader.repl.editor

import com.baader.devrt.JShellSession
import org.junit.Assert.*
import org.junit.Test

class ReplCellsTest {
    @Test fun currentCellRunsOnceWithoutReplayingOtherCells() {
        val text = "// %% Setup\nint calls = 0;\n// %% Experiment\n++calls\n// %% Danger\ncalls += 100;"
        val setup = ReplCells.at(text, 0)
        val experiment = ReplCells.at(text, setup.next!!)
        assertEquals("++calls\n", experiment.source(text))
        JShellSession(null).use {
            assertEquals("", it.eval(setup.source(text)).error())
            assertEquals("", it.eval(experiment.source(text)).error())
            assertEquals(listOf("1"), it.eval("calls").values())
            assertEquals("", it.eval(experiment.source(text)).error())
            assertEquals(listOf("2"), it.eval("calls").values())
        }
    }
    @Test fun markersInsideTextBlocksStringsAndBlockCommentsAreIgnored() {
        val text = "// %% One\nString s = \"\"\"\n// %% literal\n\"\"\";\n/*\n// %% comment\n*/\nString escaped = \"\\\"// %%\";\n// %% Two\n42"
        val first = ReplCells.at(text, text.indexOf("literal"))
        assertTrue(first.source(text).contains("// %% comment"))
        assertEquals("42", ReplCells.at(text, first.next!!).source(text))
    }
    @Test fun preambleCrLfEmptyAndFinalCellsHaveStableBoundaries() {
        val text = "int pre = 1;\r\n  // %% first\r\n\r\n// %% second\r\n42\r\n// %%"
        assertEquals("int pre = 1;\r\n", ReplCells.at(text, 0).source(text))
        assertEquals("\r\n", ReplCells.at(text, text.indexOf("first")).source(text))
        assertEquals("42\r\n", ReplCells.at(text, text.indexOf("second")).source(text))
        assertEquals("", ReplCells.at(text, text.length).source(text))
        assertNull(ReplCells.at(text, text.length).next)
        assertEquals("", ReplCells.at("", 0).source(""))
        assertEquals("42", ReplCells.at("42", 0).source("42"))
    }
    @Test fun completionUsesCellRelativeAnchorsAndRejectsStaleReplies() {
        val text = "// %% Setup\nint ignored = 1;\n// %% Query\nctx.getB"
        val request = CompletionRequest.capture(text, text.length, 12, 3)!!
        assertEquals("ctx.getB", request.source)
        val choice = request.choices("4\tgetBean(\n-1\tbad\n999\tbad\n4\tgetBean(").single()
        assertEquals(text.indexOf("getB"), choice.anchor)
        assertEquals(text.substring(0, choice.anchor) + "getBean(", text.replaceRange(choice.anchor, text.length, choice.text))
        assertTrue(request.matches(12, text.length, 3))
        assertFalse(request.matches(13, text.length, 3))
        assertFalse(request.matches(12, text.length - 1, 3))
        assertFalse(request.matches(12, text.length, 4))
    }
    @Test fun noPopupInsideCommentsOrLiterals() {
        for (code in listOf("// ctx.getB", "/* ctx.getB", "String s = \"ctx.getB", "String s = \"\"\"\nctx.getB"))
            assertNull(CompletionRequest.capture(code, code.length, 0, 0))
        val code = "/* comment */\nctx.getB"
        assertNotNull(CompletionRequest.capture(code, code.length, 0, 0))
    }
}
