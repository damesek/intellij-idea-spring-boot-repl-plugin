package hu.baader.repl.editor

import org.junit.Assert.*
import org.junit.Test

class CellResultsTest {
    @Test fun resultsBelongToOneRunAndAreLostOnResetWithoutPersistingHandles() {
        val state = NotebookState(); state.sync("1 + 1")
        val cell = state.cells.single(); val results = CellResults()
        state.finish(cell.id, state.begin(cell.id), cell.source, "2", false, 12)
        results.record(cell.id, cell.sequence, mapOf("handle" to "h-1")); assertEquals("h-1", results.handle(cell))
        state.sync("1 + 2"); assertTrue(cell.modified); assertEquals("h-1", results.handle(cell)) // Inspect the explicitly labelled old result.
        state.begin(cell.id); assertNull(results.handle(cell))
        state.finish(cell.id, cell.sequence, cell.source, "3", false, 15)
        results.record(cell.id, cell.sequence, mapOf("handle" to "h-2")); results.clear(); assertNull(results.handle(cell))
        val doc = hu.baader.repl.workspace.WorkspaceDocument(notebook = state)
        assertFalse(doc.encode().contains("h-2"))
    }
    @Test fun editingAnInputNamesTheChangedCell() {
        val state = NotebookState(); state.sync("// %% A\nint n = 1;\n// %% B\nn + 1")
        val a = state.cells[0]; val b = state.cells[1]
        state.analyze(a.id,a.source,listOf("n"),false); state.analyze(b.id,b.source,emptyList(),false)
        assertTrue(state.cells.all { it.stale.isBlank() })
        for (cell in state.cells) state.finish(cell.id, state.begin(cell.id), cell.source, "2", false, 1)
        state.sync(state.text.replace("n = 1", "n = 2")); assertTrue(b.stale.contains("1")); assertTrue(b.stale.contains("changed"))
    }
}
