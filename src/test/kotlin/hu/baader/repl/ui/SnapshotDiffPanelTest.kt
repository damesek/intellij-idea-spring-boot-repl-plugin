package hu.baader.repl.ui

import org.junit.Assert.*
import org.junit.Test

class SnapshotDiffPanelTest {
    @Test fun narrowWorkbookToolbarKeepsAllActionRowsVisible() {
        val toolbar = WorkbookToolbar()
        repeat(10) { toolbar.add(javax.swing.JButton("Action $it")) }
        toolbar.setSize(1000, 40)
        val wide = toolbar.preferredSize.height
        toolbar.setSize(280, 40)
        assertTrue(toolbar.preferredSize.height > wide)
    }
    @Test fun rowsPreserveEscapedContentAndRejectInvalidReplies() {
        val rows = SnapshotDiffPanel.rows("/name\tCHANGED\t\"árvíz\\t🧪\"\t\"new\\nline\"\n/root\tADDED\t<missing>\tnull\nbad\n/x\tINVALID\t1\t2")
        assertEquals(2, rows.size)
        assertEquals("\"árvíz\\t🧪\"", rows[0][2])
        assertEquals("<missing>", rows[1][2])
        assertEquals("null", rows[1][3])
    }
}
