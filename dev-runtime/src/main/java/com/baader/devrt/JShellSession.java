package com.baader.devrt;

import jdk.jshell.Diag;
import jdk.jshell.JShell;
import jdk.jshell.SnippetEvent;
import jdk.jshell.execution.LocalExecutionControlProvider;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Stateful JShell session: imports/definitions/values are persisted.
 * LocalExecutionControl ensures it runs in the same JVM process, allowing
 * access to the live Spring context.
 */
public final class JShellSession implements AutoCloseable {
    private final JShell jshell;
    private final Set<String> rememberedImports = new LinkedHashSet<>();

    public JShellSession(Object applicationContext) {
        this.jshell = JShell.builder()
                .executionEngine(new LocalExecutionControlProvider(), null)
                .build();

        // Make ReplBindings available in the shell and inject the context
        jshell.eval("import " + ReplBindings.class.getName() + ";");
        ReplBindings.setApplicationContext(applicationContext);

        // Add a set of default, useful imports
        addImports(List.of(
                "java.util.*",
                "java.time.*"
        ));
        if (applicationContext != null) {
            // Explicitly import the ApplicationContext interface, as the concrete class may be a proxy.
            addImports(List.of(
                "org.springframework.context.ApplicationContext"
            ));
        }
    }

    public void addImports(Collection<String> imports) {
        for (String imp : imports) {
            String line = imp.trim();
            if (!line.startsWith("import ")) line = "import " + line;
            if (!line.endsWith(";")) line += ";";
            jshell.eval(line);
            rememberedImports.add(line.substring(0, line.length() - 1)); // Store "import a.b.C"
        }
    }

    public List<String> getImports() {
        return new ArrayList<>(rememberedImports);
    }

    /**
     * Extracts import statements from the user's code, adds them to the session,
     * and evaluates the remaining code.
     */
    public EvalResult eval(String userCode) {
        List<String> lines = Arrays.stream(userCode.split("\\R")).toList();
        List<String> toImport = lines.stream()
                .map(String::trim)
                .filter(l -> l.startsWith("import "))
                .collect(Collectors.toList());
        if (!toImport.isEmpty()) {
            addImports(toImport);
        }

        // Strip import sorok, a maradék lesz az értékelendő payload
        List<String> nonImportLines = lines.stream()
                .filter(l -> !l.trim().startsWith("import "))
                .collect(Collectors.toList());

        String payload = nonImportLines.stream()
                .collect(Collectors.joining("\n"))
                .trim();

        if (payload.isEmpty()) {
            return EvalResult.onlyImports(getImports());
        }

        // Okos utolsó sor kezelés:
        // ha az utolsó nem üres sor kifejezés-szerű és nem ';'-re végződik,
        // akkor a megelőző sorokat külön, a kifejezést pedig önálló snippetként futtatjuk.
        List<SnippetEvent> events;
        List<Integer> nonEmptyIndices = new ArrayList<>();
        for (int i = 0; i < nonImportLines.size(); i++) {
            String line = nonImportLines.get(i);
            if (line != null && !line.trim().isEmpty()) {
                nonEmptyIndices.add(i);
            }
        }

        if (nonEmptyIndices.size() >= 2) {
            int lastIdx = nonEmptyIndices.get(nonEmptyIndices.size() - 1);
            String lastLine = nonImportLines.get(lastIdx);
            String lastTrim = lastLine.trim();

            if (isExpressionCandidate(lastTrim)) {
                String setup = nonImportLines.subList(0, lastIdx).stream()
                        .collect(Collectors.joining("\n"))
                        .trim();
                events = new ArrayList<>();
                if (!setup.isEmpty()) {
                    events.addAll(jshell.eval(setup));
                }
                events.addAll(jshell.eval(lastTrim));
            } else {
                events = jshell.eval(payload);
            }
        } else {
            events = jshell.eval(payload);
        }

        var output = new StringBuilder();
        var values = new ArrayList<String>();

        for (SnippetEvent e : events) {
            if (e.exception() != null) {
                output.append("! ").append(e.exception().getClass().getName())
                      .append(": ").append(e.exception().getMessage()).append("\n");
            }
            if (e.snippet() != null) {
                jshell.diagnostics(e.snippet())
                        .forEach(diag -> output.append("! ")
                                .append(diag.getMessage(Locale.getDefault()))
                                .append("\n"));
            }
            if (e.value() != null) {
                // Collect all non-null values (including VAR declarations) so the REPL
                // can show context objects and assignment results just like JShell.
                values.add(e.value()); // JShell provides a string representation
            }
            if (e.status() != null && e.status() != jdk.jshell.Snippet.Status.VALID) {
                output.append("# ").append(e.status()).append("\n");
            }
        }
        return new EvalResult(values, output.toString(), getImports());
    }

    @Override public void close() { jshell.close(); }

    /** DTO for evaluation results. */
    public record EvalResult(List<String> values, String output, List<String> imports) {
        public static EvalResult onlyImports(List<String> imports) {
            return new EvalResult(List.of(), "Imports updated.", imports);
        }
    }

    /**
     * Heurisztika annak eldöntésére, hogy egy sor "kifejezés jellegű"-e:
     * - nem üres
     * - nem import / típus- vagy vezérlési szerkezet
     * - nem ';', '{', '}' végű
     * - nem '.', ')', '}' vagy '@' jellel kezdődik.
     */
    private boolean isExpressionCandidate(String line) {
        if (line == null) return false;
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return false;

        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("import ")) return false;
        if (lower.startsWith("class ") || lower.startsWith("interface ")
                || lower.startsWith("enum ") || lower.startsWith("record ")) return false;
        if (lower.startsWith("if ") || lower.startsWith("for ")
                || lower.startsWith("while ") || lower.startsWith("switch ")
                || lower.startsWith("try ") || lower.startsWith("catch ")
                || lower.startsWith("finally ") || lower.startsWith("do ")
                || lower.startsWith("else ")) return false;

        if (trimmed.endsWith(";")) return false;
        if (trimmed.endsWith("{") || trimmed.endsWith("}")) return false;

        char first = trimmed.charAt(0);
        if (first == '.' || first == ')' || first == '}' || first == '@') return false;

        return true;
    }
}
