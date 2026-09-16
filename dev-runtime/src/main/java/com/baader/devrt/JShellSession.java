package com.baader.devrt;

import jdk.jshell.*;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Array;
import java.util.*;

/** Compiler operations are serialized per session. Interrupt signals the executing thread. */
public final class JShellSession implements AutoCloseable {
    private final JShell shell;
    private final SessionExecution execution;
    private final String id = UUID.randomUUID().toString();
    private final LinkedHashMap<String, Object> handles = new LinkedHashMap<>();
    private final Set<String> compilationClasspath = new LinkedHashSet<>();
    private volatile Object context;
    private Object last1, last2, last3;
    private boolean hasResult;
    private volatile boolean closed;
    private volatile Thread analysisThread;

    public JShellSession(Object context) {
        this.context = context;
        execution = new SessionExecution(AppClassPath.loader(context));
        shell = JShell.builder().executionEngine(new jdk.jshell.spi.ExecutionControlProvider() {
            @Override public String name() { return "sb-repl-local"; }
            @Override public jdk.jshell.spi.ExecutionControl generate(jdk.jshell.spi.ExecutionEnv env, Map<String, String> parameters) { return execution; }
        }, Map.of()).build();
        compilationClasspath.addAll(AppClassPath.entries(context));
        for (String entry : compilationClasspath) shell.addToClasspath(entry);
        ReplOutput.install();
        evaluate("import java.util.*; import java.time.*;", false);
        bindContext(context);
    }

    public synchronized void bindContext(Object value) {
        if (context != null && context != value) throw new IllegalStateException("Context changed; reset the session before binding");
        context = value;
        if (value == null) return;
        for (String entry : AppClassPath.entries(value)) if (compilationClasspath.add(entry)) shell.addToClasspath(entry);
        execution.setParent(AppClassPath.loader(value));
        bindValue("ctx", value, "org.springframework.context.ApplicationContext");
    }

    public synchronized void addImports(Collection<String> imports) {
        for (String item : imports) {
            String code = item.trim();
            if (!code.startsWith("import ")) code = "import " + code;
            if (!code.endsWith(";")) code += ";";
            var info = shell.sourceCodeAnalysis().analyzeCompletion(code);
            if (!info.remaining().isBlank() || !info.completeness().isComplete()) throw new IllegalArgumentException("Expected one complete import");
            List<Snippet> snippets = shell.sourceCodeAnalysis().sourceToSnippets(info.source());
            if (snippets.size() != 1 || snippets.get(0).kind() != Snippet.Kind.IMPORT) throw new IllegalArgumentException("Expected an import");
            EvalResult result = evaluate(code, false);
            if (!result.error().isEmpty()) throw new IllegalArgumentException(result.error());
        }
    }

    public synchronized List<String> getImports() {
        return shell.imports().filter(s -> shell.status(s) == Snippet.Status.VALID).map(Snippet::source).toList();
    }

    public synchronized EvalResult eval(String code) { return evaluate(code, true); }
    /** Explicitly opted-in watch expression; never rotates last1/last2 or the user's result handles. */
    synchronized Object watchValue(String expression) {
        var snippets=shell.sourceCodeAnalysis().sourceToSnippets(expression);
        if(snippets.size()!=1 || !(snippets.get(0) instanceof ExpressionSnippet || snippets.get(0) instanceof VarSnippet v && v.subKind()==Snippet.SubKind.TEMP_VAR_EXPRESSION_SUBKIND))
            throw new IllegalArgumentException("A Java watch must contain one expression");
        EvalResult result=evaluate(expression,false);
        if(!result.error().isEmpty())throw new IllegalArgumentException(result.error());
        if(!execution.hasResult())throw new IllegalArgumentException("Watch expression did not return a value");
        return execution.result();
    }

    public synchronized Map<String,Object> symbols(String code) {
        if (closed || code == null || code.length() > 1_000_000) throw new IllegalArgumentException("Symbol analysis accepts at most 1 million characters per cell");
        Set<String> names = new TreeSet<>(); boolean conservative = code.contains("(") || code.contains("\\u") || code.contains("[");
        String remaining = code; int count = 0;
        while (!remaining.isBlank()) {
            if (++count > 200 || Thread.currentThread().isInterrupted()) { conservative = true; break; }
            var part = shell.sourceCodeAnalysis().analyzeCompletion(remaining);
            if (part.completeness() == SourceCodeAnalysis.Completeness.EMPTY) break;
            if (part.source() == null || part.remaining().equals(remaining)) { conservative = true; break; }
            for (Snippet snippet : shell.sourceCodeAnalysis().sourceToSnippets(part.source())) {
                if (snippet instanceof DeclarationSnippet declaration) names.add(declaration.name());
                else if (snippet instanceof ImportSnippet) names.add("*");
                else conservative = true; // Statements and mutation cannot be proven independent.
            }
            remaining = part.remaining();
        }
        return Map.of("declared-symbols", String.join("\n", names), "conservative", conservative, "analysis-executed", false);
    }

    public synchronized Map<String,Object> analyze(String code) {
        if (closed) throw new IllegalStateException("Session closed");
        analysisThread = Thread.currentThread();
        try { return SourceAnalyzer.analyze(shell, compilationClasspath, code); }
        finally { analysisThread = null; }
    }

    private EvalResult evaluate(String code, boolean remember) {
        if (closed) throw new IllegalStateException("Session closed");
        if (code == null || code.length() > 1_000_000) throw new IllegalArgumentException("Invalid or oversized source");
        List<String> values = new ArrayList<>();
        StringBuilder errors = new StringBuilder();
        String handle = "";
        String exceptionType = "", exceptionMessage = "";
        execution.begin();
        try (var output = ReplOutput.capture(); var ignored = ReplBindings.enterContext(context)) {
            String remaining = code;
            while (!remaining.isBlank()) {
                if (execution.isCancelled() || Thread.currentThread().isInterrupted()) { errors.append("Evaluation interrupted"); break; }
                var info = shell.sourceCodeAnalysis().analyzeCompletion(remaining);
                if (info.completeness() == SourceCodeAnalysis.Completeness.EMPTY) break;
                if (!info.completeness().isComplete() && info.completeness() != SourceCodeAnalysis.Completeness.UNKNOWN) {
                    errors.append("Incomplete Java snippet; complete the declaration or expression before running."); break;
                }
                String source = info.source();
                if (source == null || source.isBlank() || info.remaining().equals(remaining)) {
                    errors.append("Unable to parse Java snippet"); break;
                }
                execution.clearResult();
                for (SnippetEvent event : shell.eval(source)) {
                    if (event.causeSnippet() != null) continue;
                    if (event.exception() != null) {
                        if (event.exception() instanceof EvalException evaluated) {
                            exceptionType = evaluated.getExceptionClassName(); exceptionMessage = Objects.toString(evaluated.getMessage(), "");
                        }
                        StringWriter trace = new StringWriter();
                        event.exception().printStackTrace(new PrintWriter(trace));
                        errors.append(trace);
                    }
                    shell.diagnostics(event.snippet()).filter(Diag::isError).forEach(diag ->
                            errors.append("At ").append(diag.getPosition()).append(": ").append(diag.getMessage(Locale.ROOT)).append('\n'));
                    if (event.status() == Snippet.Status.REJECTED && errors.isEmpty()) errors.append("Snippet rejected\n");
                    if (event.value() != null && !event.value().isEmpty() && event.exception() == null) {
                        values.add(limit(event.value()));
                        if (remember && execution.hasResult()) {
                            last3 = last2; last2 = last1; last1 = execution.result(); hasResult = true;
                            handle = retain(last1);
                        }
                    }
                }
                if (!errors.isEmpty()) break;
                remaining = info.remaining();
            }
            boolean interrupted = execution.isCancelled() || Thread.currentThread().isInterrupted();
            if (execution.isCancelled()) {
                if (errors.isEmpty()) errors.append("Evaluation interrupted");
                execution.acknowledgeInterrupt();
            }
            if (remember && hasResult) {
                bindValue("last1", last1, "java.lang.Object");
                bindValue("last2", last2, "java.lang.Object");
                bindValue("last3", last3, "java.lang.Object");
            }
            if (remember && !errors.isEmpty()) bindValue("lastError", limit(errors.toString()), "java.lang.String");
            Map<String,Object> presentation = handle.isEmpty() ? Map.of() : ValuePresentation.present(last1);
            return new EvalResult(List.copyOf(values), output.out(), getImports(), limit(errors.toString()), output.err(), handle, interrupted, presentation, exceptionType, limit(exceptionMessage));
        } finally { execution.end(); }
    }

    public synchronized void bindValue(String name, Object value, String type) {
        if (!javax.lang.model.SourceVersion.isIdentifier(name) || javax.lang.model.SourceVersion.isKeyword(name)) throw new IllegalArgumentException("Invalid Java variable name");
        if (!type.matches("[\\w.$<>?, \\[\\]]+")) throw new IllegalArgumentException("Invalid Java type");
        try (var ignored = ReplBindings.enter(context, value)) {
            for (SnippetEvent event : shell.eval(type + " " + name + " = (" + type + ") com.baader.devrt.ReplBindings.transfer();")) {
                if (event.causeSnippet() != null) continue;
                if (event.exception() != null || event.status() != Snippet.Status.VALID) {
                    String message = shell.diagnostics(event.snippet()).map(d -> d.getMessage(Locale.ROOT)).reduce("", (a, b) -> a + b + "\n");
                    throw new IllegalArgumentException("Cannot bind " + name + ": " + message);
                }
            }
        }
    }

    public synchronized Object value(String handle, String variable) {
        if (handle != null && !handle.isBlank()) {
            if (!handles.containsKey(handle)) throw new IllegalArgumentException("Result expired; evaluate it again explicitly");
            return handles.get(handle);
        }
        if (variable != null && !variable.isBlank()) {
            if (!javax.lang.model.SourceVersion.isIdentifier(variable)) throw new IllegalArgumentException("Select a variable or the last result; evaluate expressions first");
            VarSnippet snippet = shell.variables().filter(v -> v.name().equals(variable) && shell.status(v) == Snippet.Status.VALID).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown variable: " + variable));
            shell.varValue(snippet);
            return execution.variableValue();
        }
        if (!hasResult) throw new IllegalStateException("No result yet");
        return last1;
    }

    public synchronized String variables() {
        return shell.variables().filter(v -> shell.status(v) == Snippet.Status.VALID)
                .map(v -> v.name() + "\t" + v.typeName() + "\t" + limit(shell.varValue(v)).replace('\n', ' ').replace('\t', ' '))
                .reduce("", (a, b) -> a + b + "\n");
    }
    public synchronized List<String> variableTypes() {
        return shell.variables().filter(v -> shell.status(v) == Snippet.Status.VALID).limit(1000).map(v -> v.name() + "\t" + v.typeName()).toList();
    }
    public synchronized void drop(String name) {
        VarSnippet variable = shell.variables().filter(v -> v.name().equals(name)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown variable"));
        shell.drop(variable);
    }
    public synchronized List<String> complete(String code, int cursor) {
        if (code == null || code.length() > 1_000_000 || cursor < 0 || cursor > code.length()) throw new IllegalArgumentException("Invalid completion source or cursor");
        // Analyze preceding statements without executing them; suggestions target the snippet at the caret.
        int start = 0;
        while (start < cursor) {
            String prefix = code.substring(start, cursor);
            var info = shell.sourceCodeAnalysis().analyzeCompletion(prefix);
            int consumed = prefix.length() - info.remaining().length();
            if (consumed <= 0 || info.remaining().isBlank() || !info.completeness().isComplete()) break;
            start += consumed;
        }
        int[] anchor = new int[1];
        int base = start;
        return shell.sourceCodeAnalysis().completionSuggestions(code.substring(base), cursor-base, anchor)
                .stream().limit(100).map(s -> (base+anchor[0]) + "\t" + s.continuation()).toList();
    }
    public synchronized String inspect(String handle, String variable, int offset) {
        Object value = value(handle, variable);
        StringBuilder text = new StringBuilder(value == null ? "null" : value.getClass().getName()).append('\n');
        int start = Math.max(0, offset), end = start + 50;
        if (value instanceof Map<?, ?> map) {
            int index = 0;
            for (var entry : map.entrySet()) { if (index >= end) break; if (index++ >= start) text.append(preview(entry.getKey())).append(" = ").append(preview(entry.getValue())).append('\n'); }
            text.append("Entries: ").append(map.size());
        } else if (value instanceof List<?> list) {
            for (int i = start; i < Math.min(end, list.size()); i++) text.append(i).append(": ").append(preview(list.get(i))).append('\n');
            text.append("Elements: ").append(list.size());
        } else if (value != null && value.getClass().isArray()) {
            int size = Array.getLength(value);
            for (int i = start; i < Math.min(end, size); i++) text.append(i).append(": ").append(preview(Array.get(value, i))).append('\n');
            text.append("Elements: ").append(size);
        } else text.append(preview(value));
        return limit(text.toString());
    }
    static String preview(Object value) {
        if (value instanceof String string) return limit(string);
        return ObjectInspector.preview(value);
    }
    private String retain(Object value) {
        String handle = id + ":" + UUID.randomUUID();
        handles.put(handle, value);
        while (handles.size() > 100) handles.remove(handles.keySet().iterator().next());
        return handle;
    }
    static String limit(String value) { return value.length() > 65536 ? value.substring(0, 65536) + "\n[truncated]" : value; }
    public void interrupt() { execution.interrupt(); Thread checking = analysisThread; if (checking != null) checking.interrupt(); }
    @Override public synchronized void close() { if (closed) return; closed = true; shell.close(); execution.close(); handles.clear(); context = null; last1 = last2 = last3 = null; }
    public record EvalResult(List<String> values, String output, List<String> imports, String error, String stderr, String handle, boolean interrupted, Map<String,Object> presentation, String exceptionType, String exceptionMessage) {}
}
