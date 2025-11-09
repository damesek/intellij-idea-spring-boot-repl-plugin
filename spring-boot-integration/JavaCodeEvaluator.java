package com.example.springboot.nrepl;

import org.codehaus.janino.ExpressionEvaluator;
import org.codehaus.janino.ScriptEvaluator;
import org.springframework.context.ApplicationContext;

import javax.tools.*;
import java.io.*;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.*;

/**
 * Java kód fordító és futtató a Spring Boot nREPL integrációhoz.
 */
public class JavaCodeEvaluator {
    
    /**
     * Java kód fordítása és futtatása
     */
    public static EvalResult evaluate(String code) {
        try {
            String trimmed = code == null ? "" : code.trim();
            if (containsTypeDefinition(trimmed)) {
                return evaluateWithJavacFull(trimmed);
            } else {
                return evaluateWithJanino(trimmed);
            }
        } catch (Throwable e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            return new EvalResult(null, "Runtime error: " + e.getMessage() + "\n" + sw.toString());
        }
    }

    private static boolean containsTypeDefinition(String src) {
        if (src == null) return false;
        return src.contains("class ") || src.contains("interface ") || src.contains("enum ") || src.contains("record ");
    }

    // --- Janino path for snippets ---
    private static EvalResult evaluateWithJanino(String code) throws Exception {
        String header = String.join("\n",
                "import java.util.*;",
                "import java.io.*;",
                "import java.math.*;",
                "import java.time.*;",
                "import org.springframework.context.ApplicationContext;"
        );
        ApplicationContext ctx = EvalEnvironment.getApplicationContext();
        String candidate = stripTrailingSemicolon(code);
        boolean expression = isExpression(candidate);

        if (expression) {
            ExpressionEvaluator ee = new ExpressionEvaluator();
            ee.setParentClassLoader(currentClassLoader());
            ee.setParameters(new String[]{"ctx"}, new Class[]{ApplicationContext.class});
            ee.setExpressionType(Object.class);
            ee.cook(header + "\n" + candidate);
            Object result = ee.evaluate(new Object[]{ctx});
            return new EvalResult(stringify(result), null);
        } else {
            ScriptEvaluator se = new ScriptEvaluator();
            se.setParentClassLoader(currentClassLoader());
            se.setParameters(new String[]{"ctx"}, new Class[]{ApplicationContext.class});
            se.setReturnType(Object.class);
            String body = ensureReturnIfNeeded(code);
            se.cook(header + "\n" + body);
            Object result = se.evaluate(new Object[]{ctx});
            return new EvalResult(stringify(result), null);
        }
    }

    private static ClassLoader currentClassLoader() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        return cl != null ? cl : JavaCodeEvaluator.class.getClassLoader();
    }

    private static String ensureReturnIfNeeded(String code) {
        String c = code.trim();
        if (c.startsWith("return ") || c.endsWith(";")) {
            return code;
        }
        if (isExpression(c)) {
            return "return " + c + ";";
        }
        return code;
    }

    private static String stripTrailingSemicolon(String s) {
        String t = s.trim();
        if (t.endsWith(";")) return t.substring(0, t.length() - 1);
        return t;
    }

    private static String stringify(Object o) {
        if (o == null) return "null";
        try { return String.valueOf(o); } catch (Throwable ignore) { return o.getClass().getName(); }
    }

    // --- javac path for full class definitions ---
    private static EvalResult evaluateWithJavacFull(String code) throws Exception {
        String className = extractClassName(code, "DynamicJavaClass");
        String fullCode = code;

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return new EvalResult(null, "Java compiler not available. Make sure you're running on JDK, not JRE.");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null);
        JavaSourceFromString source = new JavaSourceFromString(className, fullCode);
        InMemoryFileManager memFileManager = new InMemoryFileManager(fileManager);

        JavaCompiler.CompilationTask task = compiler.getTask(
                null,
                memFileManager,
                diagnostics,
                Arrays.asList("-source", "17", "-target", "17"),
                null,
                Arrays.asList(source)
        );

        boolean success = task.call();
        if (!success) {
            StringBuilder errors = new StringBuilder("Compilation failed:\n");
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                errors.append(String.format("Line %d: %s%n", d.getLineNumber(), d.getMessage(null)));
            }
            return new EvalResult(null, errors.toString());
        }

        InMemoryClassLoader classLoader = new InMemoryClassLoader(memFileManager.getCompiledClasses());
        Class<?> clazz = classLoader.loadClass(className);
        Method runMethod = clazz.getMethod("run");
        Object result = runMethod.invoke(null);
        return new EvalResult(stringify(result), null);
    }

    private static String extractClassName(String code, String defaultName) {
        int idx = code.indexOf("class ");
        if (idx == -1) return defaultName;
        int start = idx + 6;
        int end = code.indexOf(' ', start);
        if (end == -1) end = code.indexOf('{', start);
        if (end == -1) return defaultName;
        return code.substring(start, end).trim();
    }
    
    /**
     * Kód becsomagolása osztályba
     */
    private static String wrapInClass(String code, String className) {
        // Ha return statement van, használjuk
        boolean hasReturn = code.contains("return ");
        
        // Ha System.out.print van, különleges kezelés
        boolean hasPrint = code.contains("System.out") || code.contains("println");
        
        StringBuilder wrapped = new StringBuilder();
        wrapped.append("import java.util.*;\n");
        wrapped.append("import java.io.*;\n");
        wrapped.append("import java.math.*;\n");
        wrapped.append("import java.time.*;\n");
        wrapped.append("import org.springframework.context.ApplicationContext;\n");
        wrapped.append("\n");
        wrapped.append("public class ").append(className).append(" {\n");
        wrapped.append("    public static Object run() throws Exception {\n");
        // make Spring ApplicationContext available as `ctx`
        wrapped.append("        final ApplicationContext ctx = com.example.springboot.nrepl.EvalEnvironment.getApplicationContext();\n");
        
        if (!hasReturn && !hasPrint) {
            // Ha nincs return, próbáljuk meg az utolsó kifejezést visszaadni
            String trimmed = code.trim();
            if (trimmed.endsWith(";")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            
            // Ellenőrizzük, hogy statement vagy expression
            if (isExpression(trimmed)) {
                wrapped.append("        return ").append(trimmed).append(";\n");
            } else {
                wrapped.append("        ").append(code).append("\n");
                wrapped.append("        return null;\n");
            }
        } else {
            wrapped.append("        ").append(code).append("\n");
            if (!hasReturn) {
                wrapped.append("        return null;\n");
            }
        }
        
        wrapped.append("    }\n");
        wrapped.append("}\n");
        
        return wrapped.toString();
    }
    
    /**
     * Ellenőrzi, hogy egy kód expression-e
     */
    private static boolean isExpression(String code) {
        // Egyszerű heurisztika
        String[] statements = {"if", "for", "while", "do", "switch", "try", "class", "interface"};
        for (String stmt : statements) {
            if (code.startsWith(stmt + " ") || code.startsWith(stmt + "(")) {
                return false;
            }
        }
        return !code.contains("{") && !code.contains(";");
    }
    
    /**
     * Eredmény osztály
     */
    public static class EvalResult {
        public final String value;
        public final String error;
        
        public EvalResult(String value, String error) {
            this.value = value;
            this.error = error;
        }
    }
    
    /**
     * Java forrás string-ből
     */
    static class JavaSourceFromString extends SimpleJavaFileObject {
        final String code;
        
        JavaSourceFromString(String name, String code) {
            super(URI.create("string:///" + name.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.code = code;
        }
        
        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
    
    /**
     * Memória alapú file manager
     */
    static class InMemoryFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
        private Map<String, ByteArrayOutputStream> compiledClasses = new HashMap<>();
        
        public InMemoryFileManager(StandardJavaFileManager fileManager) {
            super(fileManager);
        }
        
        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className,
                                                  JavaFileObject.Kind kind, FileObject sibling) {
            if (kind == JavaFileObject.Kind.CLASS) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                compiledClasses.put(className, baos);
                return new SimpleJavaFileObject(URI.create("memory:///" + className), kind) {
                    @Override
                    public OutputStream openOutputStream() {
                        return baos;
                    }
                };
            }
            return null;
        }
        
        public Map<String, byte[]> getCompiledClasses() {
            Map<String, byte[]> result = new HashMap<>();
            for (Map.Entry<String, ByteArrayOutputStream> entry : compiledClasses.entrySet()) {
                result.put(entry.getKey(), entry.getValue().toByteArray());
            }
            return result;
        }
    }
    
    /**
     * Memória alapú class loader
     */
    static class InMemoryClassLoader extends ClassLoader {
        private final Map<String, byte[]> compiledClasses;
        
        public InMemoryClassLoader(Map<String, byte[]> compiledClasses) {
            this.compiledClasses = compiledClasses;
        }
        
        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = compiledClasses.get(name);
            if (bytes != null) {
                return defineClass(name, bytes, 0, bytes.length);
            }
            throw new ClassNotFoundException(name);
        }
    }
}
