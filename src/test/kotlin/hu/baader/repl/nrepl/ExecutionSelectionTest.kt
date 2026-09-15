package hu.baader.repl.nrepl

import org.junit.Assert.*
import org.junit.Test

class ExecutionSelectionTest {
    private val live = ExecutionSelection.Policy("LIVE", "", 30000)
    private val rollback = ExecutionSelection.Policy("ROLLBACK", "orders", 5000)
    @Test fun executionWaitsForAcknowledgementAndFailedChangesAreNotAssumedApplied() {
        val state = ExecutionSelection(); assertFalse(state.ready)
        state.accept(state.begin(), live.arguments()); assertTrue(state.ready)
        val request = state.begin(rollback)
        assertFalse(state.ready); assertEquals(live, state.confirmed); assertEquals(rollback, state.requested)
        state.fail(request, "Connection timed out"); assertFalse(state.ready); assertNull(state.confirmed)
        state.accept(state.begin(), live.arguments()); assertTrue(state.ready); assertEquals("LIVE", state.confirmed!!.mode)
    }
    @Test fun lateRepliesCannotEnableExecutionAfterReconnectOrSupersedeNewerSettings() {
        val state = ExecutionSelection(); val old = state.begin(rollback)
        state.reset(); assertFalse(state.accept(old, rollback.arguments())); assertFalse(state.ready)
        val first = state.begin(); val latest = state.begin(rollback)
        assertFalse(state.accept(first, live.arguments())); assertFalse(state.ready)
        assertTrue(state.accept(latest, rollback.arguments())); assertEquals(rollback, state.confirmed)
        assertFalse(state.fail(first, "old error")); assertTrue(state.ready)
    }
    @Test fun malformedConfirmationCannotSilentlySelectLive() {
        val state = ExecutionSelection(); val ticket = state.begin()
        assertThrows(Exception::class.java) { state.accept(ticket, mapOf("execution-mode" to "LIVE")) }
        assertFalse(state.ready)
        assertThrows(IllegalArgumentException::class.java) { ExecutionSelection.Policy("SAFE", "", 30000) }
    }
}
