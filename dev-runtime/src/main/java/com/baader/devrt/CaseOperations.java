package com.baader.devrt;

import hu.baader.repl.protocol.AuditTrail;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static com.baader.devrt.ReplHandler.done;

/** CASE persistence, execution and export. Called inside the owning session's lock and snapshot scope. */
final class CaseOperations {
    private static final Set<String> OPERATIONS = Set.of(
            "case/save", "case/load", "case/list", "case/result", "case/run", "case/run-batch",
            "case/export-junit", "case/export-junit-file");
    private static final int MAX_CASES = 20;
    private static final int MAX_ROWS = 100;

    private CaseOperations() {}

    static boolean supports(String operation) {
        return OPERATIONS.contains(operation);
    }

    static Map<String, Object> handle(String operation, Map<String, String> message,
                                      ReplSession session, Object context) throws Exception {
        return switch (operation) {
            case "case/save" -> {
                SnapshotManager.saveCase(message.get("name"), SnapshotCases.definition(message));
                session.caseResults.remove(message.get("name"));
                yield done(Map.of("value", "Test case saved; no code executed"));
            }
            case "case/load" -> done(SnapshotManager.loadCase(message.get("name")));
            case "case/list" -> list();
            case "case/result" -> result(message, session);
            case "case/run", "case/run-batch" -> run(operation, message, session, context);
            case "case/export-junit", "case/export-junit-file" -> export(operation, message, session);
            default -> throw new IllegalArgumentException("Unknown CASE operation: " + operation);
        };
    }

    private static Map<String, Object> list() throws Exception {
        List<String> rows = new ArrayList<>();
        for (String entry : SnapshotManager.list()) {
            String[] fields = entry.split("\t");
            if (fields.length < 3 || !fields[2].equals("CASE")) continue;
            String name = fields[0];
            Map<String, String> saved = SnapshotManager.loadCase(name);
            rows.add(name + "\t" + saved.getOrDefault("tags", "") + "\t"
                    + saved.getOrDefault("disabled", "false") + "\t" + SnapshotCases.rows(saved).size());
        }
        return done(Map.of("value", String.join("\n", rows)));
    }

    private static Map<String, Object> result(Map<String, String> message, ReplSession session) {
        Map<String, Object> cached = session.caseResults.get(message.get("name"));
        if (cached == null) throw new IllegalArgumentException("No CASE result in this session");
        if (!message.containsKey("row")) return done(cached);
        List<?> rows = (List<?>) CaseJson.parse(cached.getOrDefault("rows-json", "[]").toString(), 2_000_000);
        int row = Integer.parseInt(message.get("row"));
        if (row < 0 || row >= rows.size()) throw new IllegalArgumentException("Invalid result row index");
        return done(Map.of("value", CaseJson.write(rows.get(row)), "run-id", cached.getOrDefault("run-id", "")));
    }

    private static Map<String, String> settings(ReplSession session, Map<String, String> message) {
        Map<String, String> settings = new LinkedHashMap<>(session.policy.arguments());
        settings.putAll(message);
        return settings;
    }

    private static Map<String, Object> run(String operation, Map<String, String> message,
                                           ReplSession session, Object context) throws Exception {
        ExecutionPolicy policy = ExecutionPolicy.from(settings(session, message));
        long deadline = System.nanoTime() + policy.timeoutMillis * 1_000_000L;
        long interruptions = session.interruptions.get();
        List<String> names = operation.equals("case/run") ? List.of(message.get("name"))
                : message.getOrDefault("names", "").lines().filter(name -> !name.isBlank()).toList();
        if (names.isEmpty() || names.size() > MAX_CASES || new HashSet<>(names).size() != names.size())
            throw new IllegalArgumentException("Select 1–20 distinct saved cases");

        // Validate the entire batch and audit its sources before executing the first case.
        Map<String, Map<String, String>> definitions = definitions(operation, message, session.id, names);
        List<Map<String, Object>> results = new ArrayList<>();
        for (var saved : definitions.entrySet()) {
            String baseline = "true".equals(saved.getValue().get("disabled")) ? ""
                    : CaseRegression.identity(saved.getValue(), policy);
            Map<String, Object> result = new LinkedHashMap<>(CaseRunner.run(
                    session.id, saved.getKey(), saved.getValue(), context, policy, deadline, session::interrupt,
                    () -> session.interruptions.get() != interruptions || session.expired || session.closing,
                    running -> session.caseShell = running));
            result.put("baseline-identity", baseline);
            result.put("regression-json", CaseRegression.compare(session.caseResults.get(saved.getKey()), result));
            remember(session, saved.getKey(), result);
            results.add(result);
            if (Set.of("ERROR", "CANCELLED").contains(result.get("outcome"))) break;
        }
        if (operation.equals("case/run")) return done(results.get(0));
        return done(Map.of(
                "outcome", CaseRunner.aggregate(results, results.size() < names.size()),
                "value", String.join("\n", IntStream.range(0, results.size())
                        .mapToObj(i -> names.get(i) + "\t" + results.get(i).get("outcome")).toList()),
                "cases-completed", results.size(), "cases-total", names.size()));
    }

    private static Map<String, Map<String, String>> definitions(String operation, Map<String, String> message,
                                                                String sessionId, List<String> names) throws Exception {
        Map<String, Map<String, String>> definitions = new LinkedHashMap<>();
        int rowCount = 0;
        for (String name : names) {
            Map<String, String> definition = SnapshotCases.definition(SnapshotManager.loadCase(name));
            if (!"true".equals(definition.get("disabled"))) SnapshotCases.requireData(definition);
            rowCount += SnapshotCases.rows(definition).size();
            definitions.put(name, definition);
            String code = SnapshotCases.source(definition);
            AuditTrail.append("runtime-" + ProcessHandle.current().pid(), Map.of(
                    "session", sessionId, "request", message.get("audit-request"), "operation", operation,
                    "name", name, "phase", "SOURCE", "code", code, "codeSha256", AuditTrail.hash(code)));
        }
        if (rowCount > MAX_ROWS) throw new IllegalArgumentException("At most 100 parameter rows per batch");
        return definitions;
    }

    private static void remember(ReplSession session, String name, Map<String, Object> result) {
        Map<String, Object> cached = new LinkedHashMap<>(CaseRunner.compact(result));
        cached.put("rows-json", result.getOrDefault("rows-json", "[]"));
        session.caseResults.remove(name);
        session.caseResults.put(name, cached);
        while (session.caseResults.size() > MAX_CASES)
            session.caseResults.remove(session.caseResults.keySet().iterator().next());
    }

    private static Map<String, Object> export(String operation, Map<String, String> message,
                                              ReplSession session) throws Exception {
        Map<String, String> policy = settings(session, message);
        String pkg = message.getOrDefault("package", "reproduction");
        String className = message.getOrDefault("class", "ReproductionTest");
        if (operation.equals("case/export-junit"))
            return done(CaseJUnitExport.preview(message.get("name"), pkg, className, policy, message.get("file")));
        CaseJUnitExport.export(message.get("name"), pkg, className, policy, Path.of(message.get("path")));
        return done(Map.of("value", "JUnit source and DATA resources exported; no code executed"));
    }
}
