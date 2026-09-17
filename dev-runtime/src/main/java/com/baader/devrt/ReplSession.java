package com.baader.devrt;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * State owned by one connection session. Mutations and resource release use this
 * object's monitor; interruption remains independent of a running evaluation.
 */
final class ReplSession {
    final String id;
    final ExecutorService queue = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), runnable -> {
                Thread thread = new Thread(runnable, "sb-repl-session");
                thread.setDaemon(true);
                return thread;
            });
    final AtomicLong interruptions = new AtomicLong();
    final ObjectInspector inspector = new ObjectInspector();
    final SessionWatches watches = new SessionWatches();
    final Map<String, Map<String, Object>> caseResults = new LinkedHashMap<>();
    volatile JShellSession shell;
    volatile JShellSession caseShell;
    volatile ExecutionPolicy policy = ExecutionPolicy.from(Map.of());
    volatile Object context;
    volatile boolean closing;
    volatile boolean expired;
    JShellSession.EvalResult lastEvaluation;
    String lastCode = "";
    private boolean resourcesReleased;

    ReplSession(String id) {
        this.id = id;
        context = SpringContextHolder.get();
        shell = new JShellSession(context);
    }

    void interrupt() {
        interruptions.incrementAndGet();
        shell.interrupt();
        JShellSession running = caseShell;
        if (running != null) running.interrupt();
    }

    synchronized void cleanup() {
        if (resourcesReleased) return;
        shell.close();
        inspector.clear();
        watches.clear();
        caseResults.clear();
        lastEvaluation = null;
        lastCode = "";
        context = null;
        SnapshotManager.release(id);
        RuntimeEvents.release(id);
        resourcesReleased = true;
    }

    synchronized void invalidate() {
        if (expired) cleanup();
    }

    synchronized void reset(Object current) {
        if (closing) throw new IllegalStateException("Session closed; reconnect");
        cleanup();
        // A failed replacement must not make the closed shell available again.
        expired = true;
        shell = new JShellSession(current);
        context = current;
        RuntimeEvents.register(id);
        resourcesReleased = false;
        expired = false;
    }
}
