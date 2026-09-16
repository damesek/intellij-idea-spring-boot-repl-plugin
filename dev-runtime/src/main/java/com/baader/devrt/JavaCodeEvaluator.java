package com.baader.devrt;

import com.sun.source.tree.ClassTree;
import com.sun.source.util.JavacTask;
import javax.tools.*;
import java.io.*;
import java.lang.instrument.*;
import java.net.URI;
import java.util.*;

final class JavaCodeEvaluator {
    static Set<String> sourceClasses(String source) throws Exception {
        if(source==null||source.isBlank()||source.length()>1_000_000)throw new IllegalArgumentException("Provide a complete Java source file (up to 1 MB)");
        var compiler=ToolProvider.getSystemJavaCompiler();if(compiler==null)throw new IllegalStateException("A full JDK is required");
        var diagnostics=new DiagnosticCollector<JavaFileObject>();Set<String> names=new LinkedHashSet<>();
        try(var files=compiler.getStandardFileManager(diagnostics,Locale.ROOT,null)){
            var task=(JavacTask)compiler.getTask(null,files,diagnostics,List.of("-proc:none"),null,List.of(new Source("Source",source)));
            var unit=task.parse().iterator().next();String prefix=unit.getPackageName()==null?"":unit.getPackageName()+".";
            for(var declaration:unit.getTypeDecls())if(declaration instanceof ClassTree type)names.add(prefix+type.getSimpleName());
        }
        if(names.isEmpty()||diagnostics.getDiagnostics().stream().anyMatch(d->d.getKind()==Diagnostic.Kind.ERROR))throw new IllegalArgumentException("Java source could not be parsed");return names;
    }
    static final class HotSwapResult {
        final boolean success; final String message, error;
        HotSwapResult(boolean success, String message, String error) { this.success = success; this.message = message; this.error = error; }
    }
    static HotSwapResult hotSwap(String source) {
        try {
            if (source == null || source.isBlank() || source.length() > 1_000_000) throw new IllegalArgumentException("Provide a complete Java class (up to 1 MB)");
            Instrumentation instrumentation = AgentRuntime.getInstrumentation();
            if (instrumentation == null || !instrumentation.isRedefineClassesSupported()) throw new IllegalStateException("Class redefinition is unavailable in this JVM");
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) throw new IllegalStateException("A JDK with javac is required");
            var diagnostics = new DiagnosticCollector<JavaFileObject>();
            String name;
            try (var files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
                var parser = (JavacTask) compiler.getTask(null, files, diagnostics, List.of("-proc:none"), null, List.of(new Source("Source", source)));
                var unit = parser.parse().iterator().next();
                var types = unit.getTypeDecls().stream().filter(t -> t instanceof ClassTree).map(t -> (ClassTree) t).toList();
                var type = types.stream().filter(t -> t.getModifiers().getFlags().contains(javax.lang.model.element.Modifier.PUBLIC)).findFirst()
                    .orElseGet(() -> types.stream().findFirst().orElseThrow(() -> new IllegalArgumentException("No class, interface, enum or record found")));
                name = (unit.getPackageName() == null ? "" : unit.getPackageName() + ".") + type.getSimpleName();
            }
            Map<String, byte[]> compiled;
            try (var files = new MemoryFiles(compiler.getStandardFileManager(diagnostics, Locale.ROOT, null))) {
                var options = List.of("--release", Integer.toString(Runtime.version().feature()), "-proc:none", "-classpath", String.join(File.pathSeparator, AppClassPath.entries(SpringContextHolder.get())));
                boolean success = compiler.getTask(null, files, diagnostics, options, null, List.of(new Source(name, source))).call();
                if (!success) throw new IllegalArgumentException(diagnostics.getDiagnostics().stream().map(d -> "Line " + d.getLineNumber() + ": " + d.getMessage(Locale.ROOT)).reduce("", (a,b) -> a + b + "\n"));
                compiled = files.compiled();
            }
            ClassLoader app = AppClassPath.loader(SpringContextHolder.get());
            List<ClassDefinition> definitions = new ArrayList<>();
            for (var entry : compiled.entrySet()) {
                Class<?> target = null;
                for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                    if (loaded.getName().equals(entry.getKey()) && Class.forName(entry.getKey(), false, app) == loaded) { target = loaded; break; }
                }
                if (target == null || !instrumentation.isModifiableClass(target))
                    throw new IllegalArgumentException("Class is not loaded/modifiable in the active application: " + entry.getKey() + ". Restart after structural changes.");
                definitions.add(new ClassDefinition(target, entry.getValue()));
            }
            instrumentation.redefineClasses(definitions.toArray(ClassDefinition[]::new));
            return new HotSwapResult(true, "Reloaded: " + String.join(", ", compiled.keySet()) + ". Existing state is retained.", null);
        } catch (Exception | LinkageError failure) {
            return new HotSwapResult(false, null, "HotSwap failed: " + failure.getMessage() + ". Method-body changes are supported; structural changes require a restart.");
        }
    }
    private static final class Source extends SimpleJavaFileObject {
        private final String source;
        Source(String name, String source) { super(URI.create("string:///" + name.replace('.', '/') + ".java"), Kind.SOURCE); this.source = source; }
        @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
    }
    private static final class MemoryFiles extends ForwardingJavaFileManager<StandardJavaFileManager> {
        private final Map<String, ByteArrayOutputStream> output = new LinkedHashMap<>();
        MemoryFiles(StandardJavaFileManager delegate) { super(delegate); }
        @Override public JavaFileObject getJavaFileForOutput(Location location, String name, JavaFileObject.Kind kind, FileObject sibling) {
            var bytes = new ByteArrayOutputStream(); output.put(name, bytes);
            return new SimpleJavaFileObject(URI.create("mem:///" + name.replace('.', '/') + kind.extension), kind) {
                @Override public OutputStream openOutputStream() { return bytes; }
            };
        }
        Map<String, byte[]> compiled() { Map<String, byte[]> result = new LinkedHashMap<>(); output.forEach((name, bytes) -> result.put(name, bytes.toByteArray())); return result; }
    }
}
