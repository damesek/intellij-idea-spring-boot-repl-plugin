package com.baader.devrt;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Transaction boundaries live on the same thread as DirectExecutionControl. This is not a Java sandbox. */
final class ExecutionPolicy {
    enum Mode { LIVE, ROLLBACK, READ_ONLY }
    static final String LIMITS = "Rollback covers synchronous work participating in the selected transaction only. "
            + "REQUIRES_NEW, other transaction managers, async/reactive work, HTTP, messaging, files and object mutations are not undone. "
            + "Read-only is a transaction-manager/driver hint, not a write firewall. Timeout requests cooperative interruption.";
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "sb-repl-execution-timeout"); thread.setDaemon(true); return thread;
    });
    final Mode mode;
    final String manager;
    final int timeoutMillis;

    private ExecutionPolicy(Mode mode, String manager, int timeoutMillis) {
        this.mode = mode; this.manager = manager; this.timeoutMillis = timeoutMillis;
    }
    static ExecutionPolicy from(Map<String,String> request) {
        Mode mode;
        try { mode = Mode.valueOf(request.getOrDefault("execution-mode", "LIVE")); }
        catch (IllegalArgumentException failure) { throw new IllegalArgumentException("Execution mode must be LIVE, ROLLBACK or READ_ONLY"); }
        String manager = request.getOrDefault("transaction-manager", "");
        if (manager.length() > 256 || manager.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid transaction manager name");
        int timeout = Integer.parseInt(request.getOrDefault("timeout-ms", "30000"));
        if (timeout < 100 || timeout > 120000) throw new IllegalArgumentException("Execution timeout must be 100–120000 ms");
        return new ExecutionPolicy(mode, manager, timeout);
    }
    static List<String> managers(Object context) {
        if (context == null) return List.of();
        try {
            Class<?> api = Class.forName("org.springframework.transaction.PlatformTransactionManager", false, AppClassPath.loader(context));
            String[] names = (String[]) context.getClass().getMethod("getBeanNamesForType", Class.class, boolean.class, boolean.class)
                    .invoke(context, api, false, false);
            return Arrays.stream(names).sorted().toList();
        } catch (ClassNotFoundException absent) { return List.of(); }
        catch (ReflectiveOperationException failure) { throw failure("Cannot discover transaction managers", failure); }
    }
    Map<String,String> arguments() {
        return Map.of("execution-mode", mode.name(), "transaction-manager", manager, "timeout-ms", String.valueOf(timeoutMillis));
    }
    Map<String,Object> execute(Object context, Runnable interrupt, String source, Supplier<Map<String,Object>> work) {
        Transaction transaction = begin(context); // Fail before any user code when transaction setup is unavailable.
        Object gate = new Object();
        boolean[] state = { false, false }; // completed, timed out; protected by gate
        ScheduledFuture<?> timeout = TIMER.schedule(() -> {
            synchronized (gate) { if (!state[0]) { state[1] = true; interrupt.run(); } }
        }, timeoutMillis, TimeUnit.MILLISECONDS);
        long started = System.nanoTime();
        Map<String,Object> result;
        try (transaction) { result = new LinkedHashMap<>(work.get()); }
        finally {
            synchronized (gate) { state[0] = true; timeout.cancel(false); }
        }
        result.put("execution-mode", mode.name());
        result.put("transaction-manager", transaction.name);
        result.put("transaction-rolled-back", mode != Mode.LIVE);
        result.put("execution-duration-ms", (System.nanoTime() - started) / 1_000_000);
        result.put("execution-limits", LIMITS);
        result.put("side-effect-warnings", warnings(source));
        synchronized (gate) {
            if (state[1]) {
                result.put("execution-timed-out", true);
                if (result.containsKey("outcome")) result.put("outcome", "CANCELLED");
                result.put("err", "Execution deadline exceeded; interruption was requested. " + LIMITS);
                result.put("status", List.of("error", "done"));
            }
        }
        return result;
    }
    static List<String> warnings(String code) {
        String source = Objects.toString(code, "").toLowerCase(Locale.ROOT);
        List<String> warnings = new ArrayList<>();
        Map<String,List<String>> signals = new LinkedHashMap<>();
        signals.put("Possible outbound HTTP/network call", List.of("http", "resttemplate", "webclient", "socket", "urlconnection", "feign"));
        signals.put("Possible message publication", List.of("kafkatemplate", "rabbittemplate", "jmstemplate", ".send(", ".convertandsend("));
        signals.put("Possible file/process side effect", List.of("files.", "fileoutputstream", "filewriter", "processbuilder", "runtime.getruntime"));
        signals.put("Possible independent transaction or asynchronous work", List.of("requires_new", "transactiontemplate", "completablefuture", "new thread", "executor", "subscribe(", "parallelstream("));
        signals.forEach((warning, terms) -> { if (terms.stream().anyMatch(source::contains)) warnings.add(warning); });
        warnings.add("Source hints are incomplete: bean implementations and reflection can hide side effects. " + LIMITS);
        return List.copyOf(warnings);
    }
    private Transaction begin(Object context) {
        if (mode == Mode.LIVE) return new Transaction("", null, null, null, null);
        List<String> names = managers(context);
        String selected = manager;
        if (selected.isBlank()) {
            if (names.size() != 1) throw new IllegalArgumentException("Select a PlatformTransactionManager; available: " + names);
            selected = names.get(0);
        }
        if (!names.contains(selected)) throw new IllegalArgumentException("Unknown PlatformTransactionManager: " + selected);
        try {
            ClassLoader loader = AppClassPath.loader(context);
            Class<?> managerApi = Class.forName("org.springframework.transaction.PlatformTransactionManager", true, loader);
            Class<?> definitionApi = Class.forName("org.springframework.transaction.TransactionDefinition", true, loader);
            Class<?> statusApi = Class.forName("org.springframework.transaction.TransactionStatus", true, loader);
            Class<?> definitionType = Class.forName("org.springframework.transaction.support.DefaultTransactionDefinition", true, loader);
            Object definition = definitionType.getConstructor().newInstance();
            definitionType.getMethod("setName", String.class).invoke(definition, "sb-repl-" + mode.name().toLowerCase(Locale.ROOT));
            definitionType.getMethod("setPropagationBehavior", int.class).invoke(definition, 3); // REQUIRES_NEW: never rolls back an ambient caller transaction.
            definitionType.getMethod("setReadOnly", boolean.class).invoke(definition, mode == Mode.READ_ONLY);
            definitionType.getMethod("setTimeout", int.class).invoke(definition, (timeoutMillis + 999) / 1000);
            Object bean = context.getClass().getMethod("getBean", String.class, Class.class).invoke(context, selected, managerApi);
            Object status = managerApi.getMethod("getTransaction", definitionApi).invoke(bean, definition);
            return new Transaction(selected, bean, status, managerApi.getMethod("rollback", statusApi), statusApi.getMethod("isCompleted"));
        } catch (ReflectiveOperationException failure) { throw failure("Cannot start rollback transaction", failure); }
    }
    private static final class Transaction implements AutoCloseable {
        final String name;
        final Object manager, status;
        final Method rollback, completed;
        Transaction(String name, Object manager, Object status, Method rollback, Method completed) {
            this.name=name; this.manager=manager; this.status=status; this.rollback=rollback; this.completed=completed;
        }
        @Override public void close() {
            if (manager == null) return;
            try {
                if (Boolean.TRUE.equals(completed.invoke(status))) throw new IllegalStateException("Transaction was completed by application code; rollback cannot be confirmed");
                rollback.invoke(manager, status); // Also runs after successful evaluation, compilation/runtime errors and interruption.
            } catch (ReflectiveOperationException failure) { throw failure("Rollback failed; transaction outcome is unknown", failure); }
        }
    }
    private static IllegalStateException failure(String message, ReflectiveOperationException failure) {
        Throwable cause = failure instanceof InvocationTargetException invocation ? invocation.getTargetException() : failure;
        return new IllegalStateException(message + ": " + Objects.toString(cause.getMessage(), cause.getClass().getName()), cause);
    }
}
