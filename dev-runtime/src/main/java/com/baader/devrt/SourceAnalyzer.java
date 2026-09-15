package com.baader.devrt;

import jdk.jshell.*;
import jdk.jshell.spi.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** A separate compiler world. Its engine cannot load classes, initialize objects or invoke user code. */
final class SourceAnalyzer {
    static final int MAX_SOURCE = 100_000, MAX_CONTEXT = 200, MAX_SNIPPETS = 200, MAX_DIAGNOSTICS = 100;
    private record Issue(String severity, int start, int end, String message) {
        String row() { return severity+"\t"+start+"\t"+end+"\t"+message.replace('\t',' ').replace('\n',' ').replace('\r',' '); }
    }
    private record Declaration(DeclarationSnippet snippet, int start, int end) {}
    static Map<String,Object> analyze(JShell live, Collection<String> classpath, String source) {
        if (source == null || source.length() > MAX_SOURCE) throw new IllegalArgumentException("Live checking supports workbooks up to 100000 characters");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        Set<Issue> issues = new LinkedHashSet<>();
        List<Declaration> declarations = new ArrayList<>();
        boolean limited = false, contextIncomplete = false;
        try (PrintStream sink = new PrintStream(OutputStream.nullOutputStream());
             JShell compiler = JShell.builder().out(sink).err(sink).compilerOptions("-proc:none", "-Xlint:unchecked", "-Xlint:deprecation")
                     .executionEngine(new ExecutionControlProvider() {
                         @Override public String name() { return "sb-repl-analysis-only"; }
                         @Override public ExecutionControl generate(ExecutionEnv env, Map<String,String> parameters) { return new NoExecution(); }
                     }, Map.of()).build()) {
            for (String entry : classpath) compiler.addToClasspath(entry);
            List<Snippet> context = live.snippets().filter(s -> live.status(s).isActive())
                    .filter(s -> s.kind() == Snippet.Kind.IMPORT || s.kind() == Snippet.Kind.TYPE_DECL || s.kind() == Snippet.Kind.METHOD || s.kind() == Snippet.Kind.VAR)
                    .sorted(Comparator.comparingInt(s -> s.kind() == Snippet.Kind.IMPORT ? 0 : s.kind() == Snippet.Kind.VAR ? 2 : 1)).toList();
            if (context.size() > MAX_CONTEXT) contextIncomplete = true;
            int contextCharacters = 0;
            for (Snippet snippet : context.stream().limit(MAX_CONTEXT).toList()) {
                if (expired(deadline)) { limited = true; contextIncomplete = true; break; }
                String declaration = declaration(snippet);
                contextCharacters += declaration.length();
                if (contextCharacters > MAX_SOURCE) { limited = true; contextIncomplete = true; break; }
                if (compiler.eval(declaration).stream().anyMatch(event -> event.status() == Snippet.Status.REJECTED)) contextIncomplete = true;
            }
            String remaining = source;
            int base = 0, snippets = 0;
            while (!remaining.isBlank()) {
                if (++snippets > MAX_SNIPPETS || issues.size() >= MAX_DIAGNOSTICS || expired(deadline)) { limited = true; break; }
                SourceCodeAnalysis.CompletionInfo info = compiler.sourceCodeAnalysis().analyzeCompletion(remaining);
                if (info.completeness() == SourceCodeAnalysis.Completeness.EMPTY) break;
                if (!info.completeness().isComplete() && info.completeness() != SourceCodeAnalysis.Completeness.UNKNOWN) {
                    int end = source.stripTrailing().length();
                    issues.add(new Issue("ERROR", Math.max(base, end-1), Math.max(base, end), "Incomplete Java snippet"));
                    break;
                }
                int consumed = remaining.length() - info.remaining().length();
                if (info.source() == null || consumed <= 0) {
                    issues.add(new Issue("ERROR", base, Math.min(source.length(), base+1), "Unable to parse Java snippet")); break;
                }
                int snippetBase = base;
                List<SnippetEvent> events = compiler.eval(info.source());
                for (SnippetEvent event : events) {
                    if (event.causeSnippet() != null) continue;
                    if (event.snippet() instanceof DeclarationSnippet declaration)
                        declarations.add(new Declaration(declaration, base, Math.min(source.length(), base+consumed)));
                    List<Diag> diagnostics = compiler.diagnostics(event.snippet()).toList();
                    for (Diag diagnostic : diagnostics) {
                        if (issues.size() >= MAX_DIAGNOSTICS) { limited = true; break; }
                        int start = position(diagnostic.getStartPosition(), snippetBase, consumed, source.length());
                        int end = position(diagnostic.getEndPosition(), snippetBase, consumed, source.length());
                        issues.add(new Issue(diagnostic.isError() ? "ERROR" : "WARNING", start, Math.max(start, end),
                                diagnostic.getMessage(Locale.ROOT).substring(0, Math.min(1000, diagnostic.getMessage(Locale.ROOT).length()))));
                    }
                    if (diagnostics.isEmpty() && event.status() == Snippet.Status.REJECTED)
                        issues.add(new Issue("ERROR", base, Math.min(source.length(), base+consumed), "Snippet rejected"));
                }
                base += consumed; remaining = info.remaining();
            }
            // JShell permits forward references without ordinary javac diagnostics. Check after later cells have resolved them.
            for (Declaration declaration : declarations) {
                Snippet.Status status = compiler.status(declaration.snippet());
                if (status != Snippet.Status.RECOVERABLE_DEFINED && status != Snippet.Status.RECOVERABLE_NOT_DEFINED) continue;
                String missing = String.join(", ", compiler.unresolvedDependencies(declaration.snippet()).toList());
                if (missing.isEmpty()) continue;
                if (issues.size() >= MAX_DIAGNOSTICS) { limited = true; break; }
                issues.add(new Issue("ERROR", declaration.start(), declaration.end(), "Unresolved: " + missing.substring(0, Math.min(missing.length(), 980))));
            }
        }
        return Map.of("diagnostics", String.join("\n", issues.stream().map(Issue::row).toList()),
                "analysis-limited", limited, "context-incomplete", contextIncomplete, "analysis-executed", false);
    }
    private static int position(long position, int base, int length, int total) {
        return Math.min(total, base + (int)Math.max(0, Math.min(position, length)));
    }
    private static boolean expired(long deadline) { return Thread.currentThread().isInterrupted() || System.nanoTime() > deadline; }
    private static String declaration(Snippet snippet) {
        if (snippet instanceof VarSnippet variable && variable.typeName().matches("[\\w.$<>?, \\[\\]]+")) {
            String type = variable.typeName();
            String initial = type.equals("boolean") ? "false" : Set.of("byte", "short", "char", "int", "long", "float", "double").contains(type) ? "0" : "null";
            return type + " " + variable.name() + " = " + initial + ";";
        }
        return snippet.source();
    }
    private static final class NoExecution implements ExecutionControl {
        @Override public void load(ClassBytecodes[] classes) { /* Compiler metadata only; no class loader. */ }
        @Override public void redefine(ClassBytecodes[] classes) {}
        @Override public String invoke(String className, String methodName) { return "null"; }
        @Override public String varValue(String className, String variableName) { return "null"; }
        @Override public void addToClasspath(String path) {}
        @Override public void stop() {}
        @Override public Object extensionCommand(String command, Object arg) throws NotImplementedException { throw new NotImplementedException("Analysis has no execution commands"); }
        @Override public void close() {}
    }
}
