package com.baader.devrt;

import hu.baader.repl.protocol.ReplProtocol;
import hu.baader.repl.protocol.AuditTrail;
import java.util.*;
import java.util.concurrent.*;

/** Per-connection session ownership; every session has its own bounded execution queue. */
public final class ReplHandler implements AutoCloseable {
    private final ConcurrentHashMap<String, ReplSession> sessions = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private final ReplBindings.Scope contextListener = SpringContextHolder.observe(() -> {
        Object current = SpringContextHolder.get();
        for (ReplSession session : sessions.values()) {
            ReplNotifications.publish(session.id, "context.changed", Map.of("ready", current != null));
            if (session.context == null || session.context == current) continue;
            session.expired = true;
            session.interrupt();
            try { session.queue.execute(() -> { synchronized (session) { session.invalidate(); } }); }
            catch (RejectedExecutionException ignored) { /* The running task also releases expired state. */ }
        }
    });
    public synchronized String createSession() {
        if (closed || sessions.size() >= 8) throw new IllegalStateException("Session limit reached");
        String id = UUID.randomUUID().toString();
        ReplSession session = new ReplSession(id);
        RuntimeEvents.register(id);
        ReplNotifications.register(id);
        sessions.put(id, session);
        return id;
    }
    public void submit(String sessionId, Runnable task) {
        ReplSession session = required(sessionId);
        session.queue.execute(() -> {
            try { task.run(); }
            finally {
                synchronized (session) {
                    session.invalidate();
                    if (session.closing) session.cleanup();
                }
                Thread.interrupted();
            }
        });
    }
    private ReplSession required(String id) {
        ReplSession session = sessions.get(id);
        if (session == null) throw new IllegalArgumentException("Session missing or expired; reconnect");
        return session;
    }
    public void interrupt(String id) { required(id).interrupt(); }
    public void closeSession(String id) {
        ReplSession session = sessions.remove(id);
        if (session != null) {
            ReplNotifications.release(id);
            session.closing = true;
            SnapshotTriggers.release(id);
            RuntimeEvents.release(id);
            session.interrupt();
            // Cleanup is serialized after any running eval; it never blocks the transport/UI thread.
            try { session.queue.execute(session::cleanup); }
            catch (RejectedExecutionException ignored) { /* An accepted task releases state in submit's finally block. */ }
            session.queue.shutdown();
        }
    }
    public Map<String, Object> handle(String operation, Map<String, String> message) {
        String op = canonical(operation);
        if(op.equals("notifications/poll")){
            try{String id=message.getOrDefault("session","");required(id);return done(ReplNotifications.poll(id,Long.parseLong(message.getOrDefault("cursor","0"))));}
            catch(Exception failure){return error(failure.getMessage());}
        }
        if (op.equals("audit/events")) {
            try {
                String id = message.getOrDefault("session", ""); required(id);
                return done(Map.of("value", AuditTrail.recent("runtime-" + ProcessHandle.current().pid(), id, 100)));
            } catch (Exception failure) { return error(failure.getMessage()); }
        }
        String channel = "runtime-" + ProcessHandle.current().pid();
        String session = message.getOrDefault("session", "");
        String code = message.getOrDefault("code", message.getOrDefault("expr", ""));
        Map<String,String> entry = new LinkedHashMap<>();
        entry.put("session", session); entry.put("actor", message.getOrDefault("audit-actor", "IDE/nREPL"));
        entry.put("operation", op); entry.put("pid", String.valueOf(ProcessHandle.current().pid()));
        entry.put("contextEpoch", String.valueOf(SpringContextHolder.epoch())); entry.put("phase", "STARTED");
        entry.put("code", code); entry.put("codeSha256", AuditTrail.hash(code));
        for (String field : List.of("name", "point", "execution-mode", "transaction-manager", "class", "method", "version", "prefix"))
            if (message.containsKey(field)) entry.put(field, message.get(field));
        ReplSession currentSession = sessions.get(session);
        if (currentSession != null && Set.of("eval", "java-eval", "case/run", "case/run-batch").contains(op))
            currentSession.policy.arguments().forEach(entry::putIfAbsent);
        String audit;
        try { audit = AuditTrail.append(channel, entry); }
        catch (Exception failure) { return error("Audit unavailable; operation not started: " + failure.getMessage()); }
        long start = System.nanoTime();
        Map<String,String> auditedMessage = new LinkedHashMap<>(message); auditedMessage.put("audit-request", audit);
        Map<String,Object> result = handleOperation(operation, auditedMessage);
        try {
            AuditTrail.append(channel, Map.of("session", session, "request", audit, "operation", op,
                    "phase", ReplProtocol.error(result) ? "ERROR" : "COMPLETED", "durationMs", String.valueOf((System.nanoTime()-start)/1_000_000),
                    "detail", Objects.toString(result.get("err"), Objects.toString(result.get("outcome"), ""))));
        } catch (Exception failure) {
            return error("Operation finished, but its audit completion could not be saved. Effects may have occurred; do not retry automatically.");
        }
        if(Set.of("eval","class-reload","case/run","case/run-batch","watch/refresh","session/reset","snapshot/edit-copy","case/variants","trace/case-create").contains(op))
            ReplNotifications.publish(session,"operation.completed",Map.of("operation",op,"status",ReplProtocol.error(result)?"ERROR":"COMPLETED"));
        result = new LinkedHashMap<>(result); result.put("audit-id", audit); return result;
    }
    private Map<String, Object> handleOperation(String operation, Map<String, String> message) {
        String op = canonical(operation);
        try {
            if (op.equals("describe")) {
                Map<String, Object> ops = new TreeMap<>(); ReplProtocol.OPS.forEach(name -> ops.put(name, Map.of()));
                return done(Map.of("ops", ops, "protocol-version", ReplProtocol.VERSION, "pid", ProcessHandle.current().pid(),
                        "context-epoch", SpringContextHolder.epoch(), "context-ready", SpringContextHolder.get() != null));
            }
            if (op.equals("clone")) return done(Map.of("new-session", createSession()));
            String id = message.getOrDefault("session", "");
            if (op.equals("close")) { closeSession(id); return done(Map.of("value", "Session closed")); }
            if (op.equals("interrupt")) { interrupt(id); return done(Map.of("value", "Interrupt requested; application side effects are not rolled back")); }
            ReplSession session = required(id);
            if (Set.of("trace/history", "trace/call", "trace/stop").contains(op)) {
                if (session.expired || session.closing) return error("Session expired; recording ended");
                return switch (op) {
                    case "trace/history" -> done(TraceRecorder.history(id));
                    case "trace/call" -> done(TraceRecorder.recordedCall(id,message.get("recording"),Long.parseLong(message.get("call-id"))));
                    case "trace/stop" -> { TraceRecorder.stopRecording(id); yield done(TraceRecorder.history(id)); }
                    default -> throw new IllegalStateException(op);
                };
            }
            if (op.startsWith("events/")) {
                if(session.expired || session.closing) return error("Session expired; reset first");
                return switch(op) {
                    case "events/start" -> { RuntimeEvents.subscribe(id,true,message.getOrDefault("label","")); yield done(RuntimeEvents.list(id)); }
                    case "events/stop" -> { RuntimeEvents.subscribe(id,false,""); yield done(RuntimeEvents.list(id)); }
                    case "events/list" -> done(RuntimeEvents.list(id));
                    case "events/clear" -> { RuntimeEvents.clear(id); yield done(RuntimeEvents.list(id)); }
                    default -> error("Unknown operation: "+op);
                };
            }
            if (op.startsWith("capture/")) {
                if (session.expired || session.closing) return error("Session expired; reset before arming a capture");
                return switch (op) {
                    case "capture/arm" -> done(SnapshotTriggers.arm(id, message.get("point"), message.get("name"), message.getOrDefault("case", ""),
                            message.getOrDefault("type", ""), Long.parseLong(message.getOrDefault("ttl-ms", "300000")),
                            Integer.parseInt(message.getOrDefault("count", "1")), Integer.parseInt(message.getOrDefault("sample-every", "1"))));
                    case "capture/status" -> done(SnapshotTriggers.status(id,message.getOrDefault("rule-id", "")));
                    case "capture/disarm" -> done(SnapshotTriggers.disarm(id,message.getOrDefault("rule-id", "")));
                    case "capture/list" -> done(SnapshotTriggers.list(id));
                    default -> error("Unknown operation: " + op);
                };
            }
            synchronized (session) {
                if (session.closing) return error("Session closed; reconnect");
                try (var ignored = SnapshotManager.scope(id)) {
                    Object current = SpringContextHolder.get();
                    if (op.equals("session/reset")) {
                        session.reset(current);
                        return done(Map.of("reset", true, "value", "Session reset", "context-ready", current != null, "context-epoch", SpringContextHolder.epoch()));
                    }
                    if (session.expired || (session.context != null && session.context != current)) {
                        session.expired = true; session.invalidate();
                        return error("Spring context changed or closed. Reset the session to discard old object references.");
                    }
                    if (op.equals("bind-spring")) {
                        if (message.containsKey("expr") && !message.get("expr").isBlank()) {
                            var evaluated = session.shell.eval(message.get("expr"));
                            if (!evaluated.error().isEmpty()) return error(evaluated.error());
                            current = session.shell.value(evaluated.handle(), null);
                            if (current == null || !hasContextApi(current)) return error("The expression did not return an active ApplicationContext");
                            SpringContextHolder.set(current);
                        }
                        if (current == null) { AutoBinder.tryBindOnce(); current = SpringContextHolder.get(); }
                        if (current == null) return done(Map.of("value", "false", "context-ready", false));
                        if (session.context != current) { session.shell.bindContext(current); session.context = current; RuntimeEvents.register(id); }
                        return done(Map.of("value", "true", "context-ready", true, "context-epoch", SpringContextHolder.epoch()));
                    }
                    if (CaseOperations.supports(op)) return CaseOperations.handle(op, message, session, current);
                    return switch (op) {
                        case "execution/policy" -> {
                            Map<String,Object> value = new LinkedHashMap<>(session.policy.arguments());
                            value.put("managers", ExecutionPolicy.managers(current)); value.put("limits", ExecutionPolicy.LIMITS);
                            yield done(value);
                        }
                        case "execution/configure" -> {
                            ExecutionPolicy next = ExecutionPolicy.from(message);
                            if (next.mode != ExecutionPolicy.Mode.LIVE) {
                                List<String> managers = ExecutionPolicy.managers(current);
                                if (next.manager.isBlank() ? managers.size() != 1 : !managers.contains(next.manager))
                                    throw new IllegalArgumentException("Select a transaction manager; available: " + managers);
                            }
                            session.policy = next;
                            yield done(new LinkedHashMap<>(next.arguments()));
                        }
                        case "execution/preflight" -> done(Map.of("warnings", ExecutionPolicy.warnings(message.getOrDefault("code", "")), "limits", ExecutionPolicy.LIMITS));
                        case "eval", "java-eval" -> {
                            Map<String,String> settings = new LinkedHashMap<>(session.policy.arguments()); settings.putAll(message);
                            String code = message.getOrDefault("code", "");
                            yield ExecutionPolicy.from(settings).execute(current, session::interrupt, code+"\n"+session.watches.javaSource(), () -> {
                                session.lastCode = code; session.lastEvaluation = null;
                                session.lastEvaluation = session.shell.eval(code);
                                if(!session.lastEvaluation.interrupted())session.watches.sample(session.shell);
                                return evaluation(session.lastEvaluation);
                            });
                        }
                        case "watch/add" -> done(session.watches.add(message.get("expression"),"true".equals(message.get("allow-java"))));
                        case "watch/list" -> done(session.watches.list());
                        case "watch/get" -> done(session.watches.get(message.get("watch-id")));
                        case "watch/remove" -> {session.watches.remove(message.get("watch-id"));yield done(Map.of("value","Watch removed"));}
                        case "watch/refresh" -> {
                            Map<String,String> settings=new LinkedHashMap<>(session.policy.arguments());settings.putAll(message);
                            yield ExecutionPolicy.from(settings).execute(current,session::interrupt,session.watches.javaSource(),()->{session.watches.sample(session.shell);return done(session.watches.list());});
                        }
                        case "reproduction/create" -> done(ReproductionBundle.create(message,session.shell,session.lastEvaluation,session.lastCode));
                        case "reproduction/export-file" -> { ReproductionBundle.exportFile(message.get("name"),java.nio.file.Path.of(message.get("path"))); yield done(Map.of("value","Reproduction bundle exported")); }
                        case "reproduction/import-file" -> done(ReproductionBundle.importFile(java.nio.file.Path.of(message.get("path")),message.getOrDefault("name","imported")));
                        case "imports/get" -> done(Map.of("imports", session.shell.getImports()));
                        case "notebook/symbols" -> done(session.shell.symbols(message.getOrDefault("code", "")));
                        case "workspace/context" -> done(Map.of("value", CaseJson.write(ReproductionContext.capture()), "imports", session.shell.getImports()));
                        case "workspace/export-file" -> done(WorkspaceBundle.exportFile(java.nio.file.Path.of(message.get("path")), java.nio.file.Path.of(message.get("state-path")), session.shell.getImports(), session.shell.variableTypes()));
                        case "workspace/import-file" -> done(WorkspaceBundle.importFile(java.nio.file.Path.of(message.get("path")), message.getOrDefault("prefix", "workspace")));
                        case "imports/add" -> { session.shell.addImports(Arrays.asList(message.getOrDefault("imports", "").split("\\R"))); yield done(Map.of("imports", session.shell.getImports())); }
                        case "vars/list" -> done(Map.of("value", session.shell.variables()));
                        case "vars/drop" -> { session.shell.drop(message.get("name")); yield done(Map.of("value", "Variable dropped")); }
                        case "inspect" -> done(Map.of("value", session.shell.inspect(message.get("handle"), message.get("var"), Integer.parseInt(message.getOrDefault("offset", "0")))));
                        case "inspector/start" -> done(session.inspector.start(selectedValue(session,message),message.getOrDefault("var",message.containsKey("event")?"Event "+message.get("event"):"Result")));
                        case "inspector/page" -> done(session.inspector.page(Integer.parseInt(message.getOrDefault("offset","0"))));
                        case "inspector/push" -> done(session.inspector.push(message.get("revision"),Integer.parseInt(message.get("index"))));
                        case "inspector/back" -> done(session.inspector.back());
                        case "inspector/restore-path" -> {
                            String path = message.getOrDefault("path", "");
                            if (path.length() > 16384) throw new IllegalArgumentException("Invalid bookmark path");
                            List<String> labels = path.isBlank() ? List.of() : Arrays.stream(path.split("\\.", -1)).map(part -> new String(Base64.getUrlDecoder().decode(part), java.nio.charset.StandardCharsets.UTF_8)).toList();
                            yield done(session.inspector.restorePath(session.shell.value(null, message.get("var")), message.get("var"), labels));
                        }
                        case "inspector/bind" -> {
                            Object value=session.inspector.current(); String variable=message.get("var");
                            String type=message.getOrDefault("type",""); if(type.isBlank()) type=ObjectInspector.sourceType(value);
                            session.shell.bindValue(variable,value,type); yield done(Map.of("value","Bound to "+variable,"var",variable,"type",type));
                        }
                        case "debug/claim" -> {
                            Object value=RuntimeEvents.claimDebug(id,message.get("ticket"));
                            session.inspector.start(value,"Debugger");
                            String variable=message.getOrDefault("var","debugValue");
                            session.shell.bindValue(variable,value,ObjectInspector.sourceType(value));
                            yield done(Map.of("value","Debugger value imported into "+variable,"var",variable));
                        }
                        case "trace/configure" -> done(TraceRecorder.configure(id,message.get("class"),message.get("method"),Boolean.parseBoolean(message.getOrDefault("enabled","true"))));
                        case "trace/record" -> done(TraceRecorder.record(id,message.get("classes"),"true".equals(message.get("sql")),Integer.parseInt(message.getOrDefault("n-plus-one-threshold","5")),"true".equals(message.get("hibernate")),"true".equals(message.get("capture-data")),"true".equals(message.get("async"))));
                        case "trace/case-info", "trace/case-create" -> {
                            ExecutionHistory history=ExecutionHistory.get(id);if(history==null)throw new IllegalArgumentException("No recording in this session");
                            long call=Long.parseLong(message.get("call-id"));history.call(message.get("recording"),call);
                            yield done(op.equals("trace/case-info")?history.experiments.info(call):history.experiments.create(call,message.get("name"),message.getOrDefault("bean","")));
                        }
                        case "trace/list" -> done(TraceRecorder.list(id));
                        case "trace/clear" -> { TraceRecorder.release(id); yield done(Map.of("value","Tracing stopped")); }
                        case "complete" -> done(Map.of("completions", session.shell.complete(message.getOrDefault("code", ""), Integer.parseInt(message.getOrDefault("cursor", "0")))));
                        case "analyze" -> done(session.shell.analyze(message.getOrDefault("code", "")));
                        case "list-beans" -> done(Map.of("value", beans(current)));
                        case "beans/list" -> done(BeanExplorer.list(current,message.getOrDefault("query",""),Integer.parseInt(message.getOrDefault("offset","0"))));
                        case "beans/info" -> done(BeanExplorer.info(current,message.get("bean")));
                        case "beans/prepare" -> done(BeanExplorer.prepare(current,message));
                        case "beans/compatible-data" -> done(BeanExplorer.compatible(current,message));
                        case "snapshot/edit-read" -> done(DataCopies.read(message.get("name")));
                        case "snapshot/edit-validate" -> done(DataCopies.edit(message,false));
                        case "snapshot/edit-copy" -> done(DataCopies.edit(message,true));
                        case "case/variants" -> done(DataCopies.variants(message));
                        case "case/affected" -> done(CaseRegression.affected(message.get("code")));
                        case "snapshot/diff" -> done(SnapshotManager.diff(message.get("before"), message.get("after"),
                                Integer.parseInt(message.getOrDefault("offset", "0")), Integer.parseInt(message.getOrDefault("limit", "100"))));
                        case "snapshot/list" -> done(Map.of("value", String.join("\n", SnapshotManager.list())));
                        case "snapshot/save" -> {
                            Object value = selectedValue(session,message);
                            SnapshotManager.save(message.get("name"), value, message.getOrDefault("type", ""));
                            yield done(Map.of("value", "DATA saved: " + message.get("name")));
                        }
                        case "snapshot/pin" -> {
                            SnapshotManager.pin(message.get("name"), selectedValue(session,message));
                            yield done(Map.of("value", "LIVE pinned: " + message.get("name")));
                        }
                        case "snapshot/load" -> {
                            String type = message.getOrDefault("type", "");
                            String version = message.getOrDefault("version", "");
                            Object value = !version.isBlank() ? SnapshotManager.loadVersion(message.get("name"), version, type) : type.isBlank() ? SnapshotManager.load(message.get("name")) : SnapshotManager.loadTyped(message.get("name"), type);
                            String variable = message.getOrDefault("var", message.getOrDefault("target", "loadedSnapshot"));
                            String javaType = type.isBlank() ? SnapshotManager.sourceType(message.get("name"), value, version) : type;
                            session.shell.bindValue(variable, value, javaType);
                            String name = message.get("name");
                            boolean persistent = java.nio.file.Files.exists(SnapshotManager.path(name));
                            yield done(Map.of("value", "Loaded into " + variable, "var", variable, "type", javaType, "snapshot", name,
                                    "snapshot-version", !version.isBlank() ? version : persistent ? SnapshotVersions.checksum(SnapshotManager.path(name)) : "", "restorable", persistent));
                        }
                        case "snapshot/delete" -> { SnapshotManager.delete(message.get("name")); yield done(Map.of("value", "Deleted")); }
                        case "snapshot/export" -> done(Map.of("value", SnapshotManager.json(message.get("name"))));
                        case "snapshot/export-file" -> {
                            SnapshotManager.exportFile(message.get("name"), java.nio.file.Path.of(message.get("path")));
                            yield done(Map.of("value", "Snapshot file exported"));
                        }
                        case "snapshot/import-file" -> {
                            SnapshotManager.importFile(message.get("name"), java.nio.file.Path.of(message.get("path")));
                            yield done(Map.of("value", "DATA file imported"));
                        }
                        case "snapshot/info" -> done(Map.of("value", SnapshotManager.info(message.get("name"))));
                        case "snapshot/versions" -> {
                            var versions = SnapshotManager.versions(message.get("name"));
                            int offset = Integer.parseInt(message.getOrDefault("offset", "0")), limit = Integer.parseInt(message.getOrDefault("limit", "20"));
                            if (offset < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid version page");
                            yield done(Map.of("versions-json", CaseJson.write(versions.stream().skip(offset).limit(limit).toList()), "total", versions.size()));
                        }
                        case "snapshot/provenance" -> done(Map.of("value", CaseJson.write(SnapshotManager.provenance(message.get("name"), message.getOrDefault("version", "")))));
                        case "snapshot/restore-version" -> { SnapshotManager.restoreVersion(message.get("name"), message.get("version")); yield done(Map.of("value", "Snapshot restored as a new version; no Java executed")); }
                        case "snapshot/import" -> { SnapshotManager.importJson(message.get("name"), message.get("json")); yield done(Map.of("value", "DATA imported")); }
                        case "recipe/save" -> { SnapshotManager.saveRecipe(message.get("name"), message.get("code")); yield done(Map.of("value", "Recipe saved; not executed")); }
                        case "recipe/load" -> done(Map.of("value", SnapshotManager.loadRecipe(message.get("name"))));
                        case "class-reload" -> {
                            var result = JavaCodeEvaluator.hotSwap(message.getOrDefault("code", ""));
                            yield result.success ? done(Map.of("value", result.message)) : error(result.error);
                        }
                        default -> error("Unknown operation: " + op);
                    };
                }
            }
        } catch (Exception exception) { return error(exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage()); }
    }
    private static Object selectedValue(ReplSession session,Map<String,String> message) {
        if("true".equals(message.get("inspected"))) return session.inspector.current();
        if(message.containsKey("event")) return RuntimeEvents.value(session.id,Long.parseLong(message.get("event")));
        return session.shell.value(message.get("handle"),message.getOrDefault("expr",message.get("var")));
    }
    private static boolean hasContextApi(Object context) {
        try { return Boolean.TRUE.equals(context.getClass().getMethod("isActive").invoke(context)); }
        catch (ReflectiveOperationException exception) { return false; }
    }
    private static String beans(Object context) throws Exception {
        if (context == null) throw new IllegalStateException("Spring context is not ready");
        String[] names = (String[]) context.getClass().getMethod("getBeanDefinitionNames").invoke(context);
        Arrays.sort(names);
        StringBuilder result = new StringBuilder();
        var typeMethod = context.getClass().getMethod("getType", String.class, boolean.class);
        for (String name : names) {
            Object type;
            try { type = typeMethod.invoke(context, name, false); } catch (ReflectiveOperationException ignored) { type = null; }
            result.append(name.replace('\t', ' ').replace('\n', ' ')).append('\t').append(type instanceof Class<?> clazz ? clazz.getName() : "").append('\n');
        }
        return JShellSession.limit(result.toString());
    }
    private static Map<String, Object> evaluation(JShellSession.EvalResult result) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("values", result.values()); values.put("imports", result.imports());
        values.putAll(result.presentation());
        if (!result.values().isEmpty()) values.put("value", result.values().get(result.values().size() - 1));
        if (!result.output().isEmpty()) values.put("out", result.output());
        if (!result.stderr().isEmpty()) values.put("stderr", result.stderr());
        if (!result.handle().isEmpty()) values.put("handle", result.handle());
        if (!result.error().isEmpty()) { values.put("err", result.error()); values.put("status", List.of("error", "done")); }
        if (!result.exceptionType().isEmpty()) { values.put("exception-type",result.exceptionType()); values.put("exception-message",result.exceptionMessage()); }
        return done(values);
    }
    public static Map<String, Object> done(Map<String, ?> values) {
        Map<String, Object> result = new LinkedHashMap<>(values); result.putIfAbsent("status", List.of("done")); return result;
    }
    public static Map<String, Object> error(String text) { return Map.of("err", text == null ? "Operation failed" : text, "status", List.of("error", "done")); }
    static String canonical(String op) {
        return switch (op == null ? "" : op) {
            case "imports-get" -> "imports/get"; case "imports-add" -> "imports/add"; case "session-reset" -> "session/reset";
            case "snapshots", "snapshot-list-simple", "snapshot-list" -> "snapshot/list";
            case "snapshot-save", "snapshot-save-json" -> "snapshot/save"; case "snapshot-pin" -> "snapshot/pin";
            case "snapshot-load", "snapshot-materialize" -> "snapshot/load"; case "snapshot-delete" -> "snapshot/delete"; case "snapshot-info" -> "snapshot/info";
            default -> op == null ? "" : op;
        };
    }
    @Override public synchronized void close() {
        closed = true;
        contextListener.close();
        for (String id : sessions.keySet()) closeSession(id);
    }
}
