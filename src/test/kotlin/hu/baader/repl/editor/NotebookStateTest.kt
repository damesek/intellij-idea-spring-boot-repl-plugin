package hu.baader.repl.editor

import org.junit.Assert.*
import org.junit.Test

class NotebookStateTest {
    private fun notebook(): NotebookState = NotebookState().apply {
        sync("// %% Input\nint x = 1;\n// %% Computed\nint y = x + 1;\n// %% Independent\nint z = 7;")
        cells.forEachIndexed { i, c -> analyze(c.id, c.source, listOf(listOf("x", "y", "z")[i]), false) }
    }
    private fun run(state: NotebookState, index: Int, failure: Boolean = false) {
        val c = state.cells[index]; state.finish(c.id, state.begin(c.id), c.source, "result", failure, 3)
    }
    @Test fun successfulRunsRecordPerCellEvidenceAndOnlyKnownDependentsAreAffected() {
        val state = notebook(); run(state, 0); run(state, 1); run(state, 2)
        assertEquals(listOf(1L,2L,3L), state.cells.map { it.sequence })
        assertEquals(listOf(state.cells[0].id, state.cells[1].id), state.affected(state.cells[0].id))
        run(state, 0)
        assertTrue(state.cells[1].stale.isNotBlank()); assertEquals("", state.cells[2].stale)
        assertEquals("result", state.cells[1].output); assertEquals(3, state.cells[1].durationMs)
    }
    @Test fun editsCannotMakeOldResultsAppearCurrentAndInsertingCellsPreservesIdentities() {
        val state = notebook(); run(state, 0); run(state, 1); val ids = state.cells.map { it.id }
        state.sync(state.text.replace("x = 1", "x = 2"))
        assertTrue(state.cells[0].modified); assertTrue(state.cells[1].stale.isNotBlank()); assertEquals(ids, state.cells.map { it.id })
        state.sync("// %% New\nint first = 0;\n" + state.text)
        assertEquals(ids, state.cells.drop(1).map { it.id })
    }
    @Test fun outOfOrderExecutionRetainsStaleInputWarning() {
        val state = notebook(); run(state, 1)
        assertTrue(state.cells[1].stale.contains("input cell")); run(state, 0); run(state, 1); assertEquals("", state.cells[1].stale)
    }
    @Test fun deletedDeclarationsAndExternalExecutionInvalidatePreviousEvidence() {
        val state = notebook(); run(state, 0); run(state, 1)
        state.sync(state.text.substring(state.text.indexOf("// %% Computed")))
        assertTrue(state.cells[0].stale.isNotBlank()); state.invalidate("External action"); assertEquals("External action", state.cells[0].stale)
    }
    @Test fun resetOrDisconnectCannotBeUndoneByALateSuccessCallback() {
        val state = notebook(); val c = state.cells[0]; val seq = state.begin(c.id)
        state.invalidate("Disconnected"); state.finish(c.id, seq, c.source, "late success", false, 100)
        assertEquals("INTERRUPTED", c.status); assertEquals("Disconnected", c.stale); assertEquals("", c.output)
    }
    @Test fun forwardReferencesAndCyclesHaveFiniteConservativeClosure() {
        val state = notebook(); val x = state.cells[0]; x.source = "int x = y;"
        state.analyze(x.id, x.source, listOf("x"), false)
        assertTrue(state.dependencies(x.id).contains(state.cells[1].id)); assertEquals(2, state.affected(x.id).size)
    }
    @Test fun JavaLiteralsAndCommentsDoNotCreateFalseSymbolReferences() {
        assertEquals(setOf("String","text","x"), NotebookState.identifiers("String text = \"y\"; /* z */ // q\nx"))
        assertFalse(NotebookState.identifiers("String s = \"\"\"\n// %% y\n\"\"\";").contains("y"))
    }
    @Test fun checkpointRestorePreservesOutputButNeverClaimsObjectsAreLive() {
        val state = notebook(); run(state, 0); state.restored()
        assertEquals("SUCCESS", state.cells[0].status); assertEquals("result", state.cells[0].output); assertTrue(state.cells[0].stale.contains("Previous session"))
    }
    @Test(timeout=4000) fun twoHundredCellEditsKeepDependencyPropagationBounded() {
        val state = NotebookState()
        val text = (0 until 200).joinToString("\n") { "// %% Cell $it\nint value$it = $it; /*" + "padding ".repeat(300) + "*/" }
        state.sync(text)
        state.sync(text.replace("padding", "changed"))
        assertEquals(200, state.cells.size)
        assertEquals(200, state.affected(state.cells.first().id).size)
    }
}
