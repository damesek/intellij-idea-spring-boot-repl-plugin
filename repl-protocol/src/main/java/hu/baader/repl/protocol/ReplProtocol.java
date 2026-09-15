package hu.baader.repl.protocol;

import java.util.*;

public final class ReplProtocol {
    public static final String VERSION = "1";
    public static final Set<String> CONTROL_OPS = Set.of("clone", "describe", "interrupt", "close", "capture/arm", "capture/status", "capture/disarm", "capture/list", "events/start", "events/stop", "events/list", "events/clear", "trace/history", "trace/call", "trace/stop");
    public static final Set<String> OPS = Set.of("clone", "describe", "close", "interrupt", "eval", "java-eval", "analyze",
            "imports/get", "imports/add", "session/reset", "bind-spring", "list-beans", "class-reload",
            "vars/list", "vars/drop", "inspect", "complete", "snapshot/list", "snapshot/save", "snapshot/pin",
            "snapshot/load", "snapshot/delete", "snapshot/info", "snapshot/import", "snapshot/export", "snapshot/import-file", "snapshot/export-file", "snapshot/diff", "capture/arm", "capture/status", "capture/disarm", "recipe/save", "recipe/load",
            "inspector/start", "inspector/page", "inspector/push", "inspector/back", "inspector/bind", "events/start", "events/stop", "events/list", "events/clear",
            "trace/configure", "trace/list", "trace/clear", "trace/record", "trace/history", "trace/call", "trace/stop", "debug/claim", "case/save", "case/load", "case/run", "case/run-batch", "case/list", "case/result", "case/export-junit", "case/export-junit-file",
            "execution/policy", "execution/configure", "execution/preflight", "audit/events", "capture/list",
            "reproduction/create", "reproduction/export-file", "reproduction/import-file",
            "snapshot/versions", "snapshot/provenance", "snapshot/restore-version", "workspace/export-file", "workspace/import-file", "workspace/context", "notebook/symbols", "inspector/restore-path");
    private ReplProtocol() {}

    public static boolean done(Map<String, ?> response) { return status(response, "done"); }
    public static boolean error(Map<String, ?> response) { return response.containsKey("err") || status(response, "error"); }
    private static boolean status(Map<String, ?> response, String wanted) {
        Object status = response.get("status");
        return status instanceof Collection<?> values ? values.contains(wanted) : wanted.equals(status);
    }
    /** Compatibility view for the IDE UI; the wire retains lists and dictionaries. */
    public static Map<String, String> strings(Map<String, ?> values) {
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (value instanceof Collection<?> items) result.put(key, String.join("\n", items.stream().map(String::valueOf).toList()));
            else if (value instanceof Map<?, ?> map && key.equals("ops")) result.put(key, String.join(",", map.keySet().stream().map(String::valueOf).toList()));
            else if (value != null) result.put(key, value.toString());
        });
        return result;
    }
}
