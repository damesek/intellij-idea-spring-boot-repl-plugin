package hu.baader.repl.debug

import org.junit.Assert.*
import org.junit.Test

class SnapshotPointSpecTest {
    @Test fun nativeLogpointRoundTripsAndDoesNotRequireAgentTypesInSourceClasspath() {
        val point = SnapshotPointSpec("Árvíz \"input\"", "request.getItems().get(0)", "java.util.Map<String, Object>", 3)
        assertEquals(point, SnapshotPointSpec.read(point.logExpression()))
        assertTrue(point.condition().contains("Class.forName")); assertTrue(point.logExpression().contains("request.getItems().get(0)"))
        assertFalse(point.logExpression().contains("SnapshotBreakpoint.capture("))
        assertNotEquals(point.id, point.copy(id = java.util.UUID.randomUUID().toString()).id)
    }
    @Test fun malformedManuallyEditedOrUnboundedPointsAreNotSilentlyReconfigured() {
        val point = SnapshotPointSpec("input", "request")
        assertNull(SnapshotPointSpec.read(point.logExpression().replace("(request)", "(other)")))
        assertNull(SnapshotPointSpec.read("/*sb-repl-snapshot-v1:bad*/"))
        assertThrows(IllegalArgumentException::class.java) { point.copy(name = "../outside").validate() }
        assertThrows(IllegalArgumentException::class.java) { point.copy(expression = " ").validate() }
        assertThrows(IllegalArgumentException::class.java) { point.copy(count = 101).validate() }
    }
}
