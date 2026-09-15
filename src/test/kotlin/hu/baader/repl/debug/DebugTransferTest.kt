package hu.baader.repl.debug

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class DebugTransferTest {
    @Test fun captureChecksTargetBeforeEvaluatingTheUsersExpression() {
        val session = UUID.randomUUID().toString(); val transfer = DebugTransfer(session, 42, "debugInput")
        val source = transfer.expression("input.transform()")
        assertTrue(source.indexOf("checkTarget") < source.indexOf("input.transform()"))
        assertEquals(1, Regex("input\\.transform\\(\\)").findAll(source).count())
        assertTrue(transfer.matches(session to 42L)); assertFalse(transfer.matches(session to 43L)); assertFalse(transfer.matches(null))
    }
    @Test fun invalidTargetsAndReservedVariablesCannotBeInterpolatedIntoCode() {
        val id = UUID.randomUUID().toString()
        for (variable in listOf("ctx", "last1", "class", "x);evil()")) {
            try { DebugTransfer(id, 1, variable); fail(variable) } catch (_: IllegalArgumentException) {}
        }
        try { DebugTransfer("\";evil()", 1, "value"); fail() } catch (_: IllegalArgumentException) {}
    }
}
