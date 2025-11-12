package com.baader.devrt;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Handles the logic for all REPL operations, managing the JShell session state.
 */
public class ReplHandler {

    private final AtomicReference<JShellSession> sessionRef = new AtomicReference<>();

    public ReplHandler() {
        // Initialize with a session that has no Spring context
        this.sessionRef.set(new JShellSession(null));
    }

    public Map<String, Object> handle(String op, Map<String, String> message) {
        return switch (op) {
            case ReplOps.EVAL, ReplOps.JAVA_EVAL -> handleEval(message);
            case ReplOps.IMPORTS_GET -> handleGetImports();
            case ReplOps.IMPORTS_ADD -> handleAddImports(message);
            case ReplOps.SESSION_RESET -> handleResetSession();
            case ReplOps.BIND_SPRING -> handleBindSpring(message);
            // Snapshot ops can be added here later
            default -> Map.of("status", "error", "message", "Unknown op: " + op);
        };
    }

    private Map<String, Object> handleEval(Map<String, String> msg) {
        String code = msg.getOrDefault("code", "");
        JShellSession.EvalResult res = sessionRef.get().eval(code);
        
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("values", res.values());
        response.put("output", res.output());
        response.put("imports", res.imports());
        return response;
    }

    private Map<String, Object> handleGetImports() {
        return Map.of("imports", sessionRef.get().getImports());
    }

    private Map<String, Object> handleAddImports(Map<String, String> msg) {
        String importsStr = msg.getOrDefault("imports", "");
        List<String> imports = List.of(importsStr.split("\\R"));
        sessionRef.get().addImports(imports);
        return Map.of("imports", sessionRef.get().getImports());
    }

    private Map<String, Object> handleResetSession() {
        // Re-create the session, preserving the current ApplicationContext from ReplBindings
        Object currentCtx = ReplBindings.applicationContext();
        JShellSession oldSession = sessionRef.getAndSet(new JShellSession(currentCtx));
        if (oldSession != null) {
            oldSession.close();
        }
        return Map.of("reset", true);
    }

    private Map<String, Object> handleBindSpring(Map<String, String> msg) {
        try {
            boolean bound = AutoBinder.tryBindOnce();
            if (bound) {
                // If bound, reset the session to include the ApplicationContext
                Object ctx = SpringContextHolder.get();
                JShellSession oldSession = sessionRef.getAndSet(new JShellSession(ctx));
                if (oldSession != null) {
                    oldSession.close();
                }
                return Map.of("value", "true", "message", "Spring context bound and session updated.");
            }
            return Map.of("value", "false", "message", "Spring context could not be bound.");
        } catch (Throwable t) {
            return Map.of("status", "error", "err", t.toString());
        }
    }
}
