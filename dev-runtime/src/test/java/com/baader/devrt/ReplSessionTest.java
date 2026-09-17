package com.baader.devrt;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ReplSessionTest {
    @Test void contextExpiryReleasesCachedResultsAndLiveReferences() {
        ReplSession session = new ReplSession(UUID.randomUUID().toString());
        try (var ignored = SnapshotManager.scope(session.id)) {
            session.lastCode = "new byte[1024]";
            session.lastEvaluation = session.shell.eval(session.lastCode);
            session.caseResults.put("case", Map.of("rows-json", "large cached report"));
            SnapshotManager.pin("retained", new Object());
            session.expired = true;
            session.invalidate();
            session.invalidate();
            assertNull(session.lastEvaluation);
            assertEquals("", session.lastCode);
            assertTrue(session.caseResults.isEmpty());
            assertNull(session.context);
            assertThrows(Exception.class, () -> SnapshotManager.load("retained"));
        } finally {
            session.cleanup();
            session.queue.shutdownNow();
        }
    }

    @Test void resetAfterExpiryCreatesAUsableShellAndClosingCannotResurrectIt() {
        ReplSession session = new ReplSession(UUID.randomUUID().toString());
        try {
            session.shell.eval("int beforeReset = 1;");
            session.expired = true;
            session.invalidate();
            session.reset(null);
            assertFalse(session.expired);
            assertFalse(session.shell.eval("beforeReset").error().isEmpty());
            assertEquals("42", session.shell.eval("40 + 2").values().get(0));
            session.closing = true;
            assertThrows(IllegalStateException.class, () -> session.reset(null));
        } finally {
            session.cleanup();
            session.queue.shutdownNow();
        }
    }
}
