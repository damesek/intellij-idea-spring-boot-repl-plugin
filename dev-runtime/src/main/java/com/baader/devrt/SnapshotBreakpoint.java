package com.baader.devrt;

import hu.baader.repl.protocol.AuditTrail;
import hu.baader.repl.protocol.SensitiveValues;
import java.util.*;

/** Invoked reflectively by a Java line logpoint on its hit thread. No nREPL round trip or app source edit. */
public final class SnapshotBreakpoint {
    private static final Map<String,Integer> ATTEMPTS = new HashMap<>();
    private SnapshotBreakpoint() {}
    public static synchronized boolean ready(String id, int maximum) {
        validate(id, maximum);
        return ATTEMPTS.getOrDefault(id, 0) < maximum && (ATTEMPTS.containsKey(id) || ATTEMPTS.size() < 1024);
    }
    private static synchronized boolean claim(String id, int maximum) {
        if (!ready(id, maximum)) return false;
        ATTEMPTS.put(id, ATTEMPTS.getOrDefault(id, 0) + 1); return true;
    }
    public static String capture(String id, String name, String type, int maximum, Object value) {
        validate(id, maximum);
        if (!claim(id, maximum)) return "[REPL snapshot] Capture limit reached";
        String request = "", channel = "runtime-" + ProcessHandle.current().pid();
        long started = System.nanoTime(); boolean saved = false;
        try (var scope = SnapshotManager.scope("debugger-" + id);
             var metadata = ReproductionContext.metadata(Map.of("captureMethod", "debugger line snapshot point", "snapshotPointId", id))) {
            SnapshotManager.checkedName(name);
            request = AuditTrail.append(channel, Map.of("session", "debugger-"+id, "operation", "snapshot-point/save", "phase", "STARTED", "name", name, "point", id));
            SnapshotManager.save(name, value, type); saved = true;
            AuditTrail.append(channel, Map.of("session", "debugger-"+id, "operation", "snapshot-point/save", "phase", "COMPLETED", "request", request, "name", name,
                "durationMs", Long.toString((System.nanoTime()-started)/1_000_000)));
            return "[REPL snapshot] Saved DATA '" + name + "' (" + SnapshotManager.size(name) + " bytes). Open Snapshots > Saved.";
        } catch (Exception failure) {
            String message = SensitiveValues.redact(Objects.toString(failure.getMessage(), failure.getClass().getName()));
            if (message.length() > 1000) message = message.substring(0, 1000);
            try { AuditTrail.append(channel, Map.of("session", "debugger-"+id, "operation", "snapshot-point/save", "phase", "ERROR", "request", request, "detail", message)); }
            catch (Exception ignored) { /* The debugger log still receives the failure. */ }
            return "[REPL snapshot] " + (saved ? "DATA was saved, but follow-up failed: " : "Capture failed: ") + message + ". Configure/rearm the point to retry.";
        }
    }
    private static void validate(String id, int maximum) {
        if (id == null || !id.matches("[a-f0-9-]{36}") || maximum < 1 || maximum > 100) throw new IllegalArgumentException("Invalid snapshot point or capture limit");
    }
}
