package com.baader.devrt;

import javax.tools.*;
import java.io.*;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class JavaCodeEvaluator {
    private static final Map<String, Object> variables = new ConcurrentHashMap<>();
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("(?m)^\\s*package\\s+([\\w\\.]+)\\s*;");
    private static final Pattern TYPE_PATTERN = Pattern.compile(
            "(?m)^(?:\\s*@.*\\n)*\\s*(?:public\\s+|protected\\s+|private\\s+)?(?:abstract\\s+|final\\s+)?(class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)"
    );
    static final class EvalObj { final Object obj; final String error; EvalObj(Object o,String e){obj=o;error=e;} }
    static final class HotSwapResult { final boolean success; final String message; final String error; HotSwapResult(boolean s, String m, String e){success=s;message=m;error=e;} }
    static final class CompileOutput { final Map<String, byte[]> classes; final String error; CompileOutput(Map<String, byte[]> c, String e){classes=c;error=e;} }

    public static void setVariable(String name, Object value) {
        if (value == null) {
            variables.remove(name);
        } else {
            variables.put(name, value);
        }
    }

    public static Object getVariable(String name) {
        return variables.get(name);
    }

    public static Map<String, Object> getVariables() {
        return new HashMap<>(variables);
    }

    static EvalResult evaluate(String code) {
        try {
            String className = containsTypeDefinition(code) ? extractClassName(code, "DynamicJavaClass") : "DynamicJavaClass";
            String full = containsTypeDefinition(code) ? code : wrap(code, className);

            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) return new EvalResult(null, "Java compiler not available (JRE?). Use JDK.");

            DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
            StandardJavaFileManager fm = compiler.getStandardFileManager(diags, null, null);
            JavaSourceFromString src = new JavaSourceFromString(className, full);
            InMemoryFileManager mem = new InMemoryFileManager(fm);
            JavaCompiler.CompilationTask task = compiler.getTask(null, mem, diags,
                    Arrays.asList("-source","17","-target","17","-proc:none"), null, Arrays.asList(src));
            if (!task.call()) {
                StringBuilder sb = new StringBuilder("Compilation failed:\n");
                for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
                    sb.append("Line ").append(d.getLineNumber()).append(": ").append(d.getMessage(null)).append('\n');
                }
                return new EvalResult(null, sb.toString());
            }
            ClassLoader parent = resolveAppClassLoader();
            InMemoryClassLoader cl = new InMemoryClassLoader(parent, mem.getCompiledClasses());
            Class<?> clazz = cl.loadClass(className);
            Method run = clazz.getMethod("run");
            Object instance = clazz.getDeclaredConstructor().newInstance();
            Object res = run.invoke(instance);
            return new EvalResult(res != null ? String.valueOf(res) : "null", null);
        } catch (Throwable t) {
            StringWriter sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw));
            return new EvalResult(null, sw.toString());
        }
    }

    static EvalObj evaluateObject(String code) {
        try {
            String className = containsTypeDefinition(code) ? extractClassName(code, "DynamicJavaClass") : "DynamicJavaClass";
            String full = containsTypeDefinition(code) ? code : wrap(code, className);

            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) return new EvalObj(null, "Java compiler not available (JRE?). Use JDK.");

            DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
            StandardJavaFileManager fm = compiler.getStandardFileManager(diags, null, null);
            JavaSourceFromString src = new JavaSourceFromString(className, full);
            InMemoryFileManager mem = new InMemoryFileManager(fm);
            JavaCompiler.CompilationTask task = compiler.getTask(null, mem, diags,
                    Arrays.asList("-source","17","-target","17","-proc:none"), null, Arrays.asList(src));
            if (!task.call()) {
                StringBuilder sb = new StringBuilder("Compilation failed:\n");
                for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
                    sb.append("Line ").append(d.getLineNumber()).append(": ").append(d.getMessage(null)).append('\n');
                }
                return new EvalObj(null, sb.toString());
            }
            ClassLoader parent = resolveAppClassLoader();
            InMemoryClassLoader cl = new InMemoryClassLoader(parent, mem.getCompiledClasses());
            Class<?> clazz = cl.loadClass(className);
            Method run = clazz.getMethod("run");
            Object instance = clazz.getDeclaredConstructor().newInstance();
            Object res = run.invoke(instance);
            return new EvalObj(res, null);
        } catch (Throwable t) {
            StringWriter sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw));
            return new EvalObj(null, sw.toString());
        }
    }

    static HotSwapResult hotSwap(String code) {
        if (code == null || code.trim().isEmpty()) {
            return new HotSwapResult(false, null, "No Java source provided");
        }

        String primaryName = extractPrimaryBinaryName(code);
        if (primaryName == null) {
            return new HotSwapResult(false, null, "Could not detect class name. Ensure the snippet declares a class/record/interface.");
        }

        CompileOutput compiled = compileRawJava(code, primaryName);
        if (compiled.error != null) {
            return new HotSwapResult(false, null, compiled.error);
        }
        Instrumentation inst = AgentRuntime.getInstrumentation();
        if (inst == null) {
            return new HotSwapResult(false, null, "Instrumentation unavailable. Attach the dev runtime agent first.");
        }
        if (!inst.isRedefineClassesSupported()) {
            return new HotSwapResult(false, null, "JVM does not allow class redefinition");
        }

        List<ClassDefinition> defs = new ArrayList<>();
        List<String> updated = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        for (Map.Entry<String, byte[]> entry : compiled.classes.entrySet()) {
            String binaryName = entry.getKey();
            Class<?> target = findOrLoadClass(binaryName, inst);
            if (target == null) {
                missing.add(binaryName);
                continue;
            }
            defs.add(new ClassDefinition(target, entry.getValue()));
            updated.add(binaryName);
        }

        if (defs.isEmpty()) {
            String msg = missing.isEmpty()
                    ? "No loadable classes matched the provided source"
                    : "Classes not yet loaded: " + String.join(", ", missing);
            return new HotSwapResult(false, null, msg);
        }

        try {
            inst.redefineClasses(defs.toArray(new ClassDefinition[0]));
        } catch (Throwable t) {
            return new HotSwapResult(false, null, "HotSwap failed: " + t.getMessage());
        }

        StringBuilder message = new StringBuilder();
        if (!updated.isEmpty()) {
            message.append("Reloaded classes: ").append(String.join(", ", updated));
        }
        if (!missing.isEmpty()) {
            if (message.length() > 0) message.append('\n');
            message.append("Skipped (class not loaded yet): ").append(String.join(", ", missing));
        }
        return new HotSwapResult(true, message.toString(), null);
    }

    private static CompileOutput compileRawJava(String code, String binaryName) {
        try {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) return new CompileOutput(null, "Java compiler not available (JRE?). Use JDK.");
            DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
            StandardJavaFileManager fm = compiler.getStandardFileManager(diags, null, null);
            JavaSourceFromString src = new JavaSourceFromString(binaryName, code);
            InMemoryFileManager mem = new InMemoryFileManager(fm);
            JavaCompiler.CompilationTask task = compiler.getTask(null, mem, diags,
                    Arrays.asList("-source","17","-target","17","-proc:none"), null, Arrays.asList(src));
            if (!task.call()) {
                StringBuilder sb = new StringBuilder("Compilation failed:\n");
                for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
                    sb.append("Line ").append(d.getLineNumber()).append(": ").append(d.getMessage(null)).append('\n');
                }
                return new CompileOutput(null, sb.toString());
            }
            return new CompileOutput(mem.getCompiledClasses(), null);
        } catch (Throwable t) {
            StringWriter sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw));
            return new CompileOutput(null, sw.toString());
        }
    }

    private static String extractPrimaryBinaryName(String code) {
        Matcher typeMatcher = TYPE_PATTERN.matcher(code);
        if (!typeMatcher.find()) return null;
        String simple = typeMatcher.group(2);
        Matcher pkgMatcher = PACKAGE_PATTERN.matcher(code);
        if (pkgMatcher.find()) {
            return pkgMatcher.group(1) + "." + simple;
        }
        return simple;
    }

    private static Class<?> findOrLoadClass(String binaryName, Instrumentation inst) {
        try {
            if (inst != null) {
                for (Class<?> c : inst.getAllLoadedClasses()) {
                    if (binaryName.equals(c.getName())) return c;
                }
            }
        } catch (Throwable ignored) {}

        try {
            ClassLoader loader = resolveAppClassLoader();
            return Class.forName(binaryName, false, loader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static ClassLoader resolveAppClassLoader() {
        try {
            Class<?> holder = Class.forName("com.baader.devrt.SpringContextHolder");
            Method gm = holder.getMethod("get");
            Object ctx = gm.invoke(null);
            if (ctx != null) {
                try {
                    Class<?> cac = Class.forName("org.springframework.context.ConfigurableApplicationContext");
                    if (cac.isInstance(ctx)) {
                        Method getBF = cac.getMethod("getBeanFactory");
                        Object bf = getBF.invoke(ctx);
                        Method getBCL = bf.getClass().getMethod("getBeanClassLoader");
                        Object bcl = getBCL.invoke(bf);
                        if (bcl instanceof ClassLoader) return (ClassLoader) bcl;
                    }
                } catch (Throwable ignored) {}
                ClassLoader cl = ctx.getClass().getClassLoader();
                if (cl != null) return cl;
            }
        } catch (Throwable ignored) {}
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = JavaCodeEvaluator.class.getClassLoader();
        return cl;
    }

    // Resolve a class with the application bean class loader if possible
    static Class<?> resolveAppClass(String fqn) throws Exception {
        ClassLoader cl = null;
        try {
            Class<?> holder = Class.forName("com.baader.devrt.SpringContextHolder");
            Method gm = holder.getMethod("get");
            Object ctx = gm.invoke(null);
            if (ctx != null) {
                // try bean factory class loader
                try {
                    Class<?> cac = Class.forName("org.springframework.context.ConfigurableApplicationContext");
                    if (cac.isInstance(ctx)) {
                        Method getBF = cac.getMethod("getBeanFactory");
                        Object bf = getBF.invoke(ctx);
                        Method getBCL = bf.getClass().getMethod("getBeanClassLoader");
                        Object bcl = getBCL.invoke(bf);
                        if (bcl instanceof ClassLoader) cl = (ClassLoader) bcl;
                    }
                } catch (Throwable ignored) {}
                if (cl == null) cl = ctx.getClass().getClassLoader();
            }
        } catch (Throwable ignored) {}
        if (cl == null) cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = JavaCodeEvaluator.class.getClassLoader();
        return Class.forName(fqn, true, cl);
    }

    private static boolean containsTypeDefinition(String s) {
        return s.contains("class ") || s.contains("interface ") || s.contains("enum ") || s.contains("record ");
    }
    private static String extractClassName(String code, String def) {
        int idx = code.indexOf("class "); if (idx == -1) return def;
        int start = idx + 6; int end = code.indexOf(' ', start); if (end == -1) end = code.indexOf('{', start);
        if (end == -1) return def; return code.substring(start, end).trim();
    }
    private static String wrap(String code, String name) {
        String trimmed = code.trim();
        boolean isExpr = !(trimmed.contains(";") || trimmed.contains("{") || trimmed.startsWith("if ") || trimmed.startsWith("for "));

        StringBuilder sb = new StringBuilder();
        sb.append("import java.util.*;\n");
        sb.append("import java.io.*;\n");
        sb.append("import java.math.*;\n");
        sb.append("import java.time.*;\n");
        sb.append("public class ").append(name).append(" {\n");
        sb.append("  private org.springframework.context.ApplicationContext applicationContext;\n");
        sb.append("  private Class<?> appClass(String fqn) {\n");
        sb.append("    try {\n");
        sb.append("      ClassLoader cl = null;\n");
        sb.append("      // Prefer Spring's bean class loader if available\n");
        sb.append("      try {\n");
        sb.append("        Class<?> cac = Class.forName(\"org.springframework.context.ConfigurableApplicationContext\");\n");
        sb.append("        if (applicationContext != null && cac.isInstance(applicationContext)) {\n");
        sb.append("          java.lang.reflect.Method getBF = cac.getMethod(\"getBeanFactory\");\n");
        sb.append("          Object bf = getBF.invoke(applicationContext);\n");
        sb.append("          java.lang.reflect.Method getBCL = bf.getClass().getMethod(\"getBeanClassLoader\");\n");
        sb.append("          Object bcl = getBCL.invoke(bf);\n");
        sb.append("          if (bcl instanceof ClassLoader) cl = (ClassLoader) bcl;\n");
        sb.append("        }\n");
        sb.append("      } catch (Throwable ignored) {}\n");
        sb.append("      if (cl == null) cl = (applicationContext != null) ? applicationContext.getClass().getClassLoader() : Thread.currentThread().getContextClassLoader();\n");
        sb.append("      if (cl == null) cl = this.getClass().getClassLoader();\n");
        sb.append("      return Class.forName(fqn, true, cl);\n");
        sb.append("    } catch (Throwable t) { throw new RuntimeException(t); }\n");
        sb.append("  }\n");

        if (isExpr) {
            // Simple expression → just evaluate and return
            sb.append("  public Object run() throws Exception {\n");
            sb.append("    Object ctx = null; try { Class<?> h=Class.forName(\"com.baader.devrt.SpringContextHolder\"); java.lang.reflect.Method gm=h.getMethod(\"get\"); ctx=gm.invoke(null); } catch(Throwable ignored){}\n");
            sb.append("    try { this.applicationContext = (org.springframework.context.ApplicationContext) ctx; } catch (Throwable ignored) {}\n");
            sb.append("    return ").append(trimmed).append(";\n");
            sb.append("  }\n}\n");
            return sb.toString();
        }

        // Multi-statement: lift field declarations (public|private|protected ...;) to class scope
        String normalized = code.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n");
        StringBuilder fields = new StringBuilder();
        StringBuilder body = new StringBuilder();

        for (String rawLine : lines) {
            String work = rawLine;
            while (true) {
                if (work == null) break;
                String t = work.trim();
                if (t.isEmpty()) break;
                if (t.startsWith("public ") || t.startsWith("private ") || t.startsWith("protected ")) {
                    int semi = work.indexOf(';');
                    if (semi >= 0) {
                        String fieldDecl = work.substring(0, semi + 1).trim();
                        fields.append("  ").append(fieldDecl).append("\n");
                        work = work.substring(semi + 1);
                        continue; // process remainder in same line
                    } else {
                        // No semicolon on this line, treat whole as field (may fail but better than method body)
                        fields.append("  ").append(t).append("\n");
                        break;
                    }
                } else {
                    // Method body statement(s)
                    body.append("    ").append(t).append("\n");
                    break;
                }
            }
        }

        // Emit class with lifted fields and method body
        sb.append(fields);
        sb.append("  public Object run() throws Exception {\n");
        sb.append("    Object ctx = null; try { Class<?> h=Class.forName(\"com.baader.devrt.SpringContextHolder\"); java.lang.reflect.Method gm=h.getMethod(\"get\"); ctx=gm.invoke(null); } catch(Throwable ignored){}\n");
        sb.append("    try { this.applicationContext = (org.springframework.context.ApplicationContext) ctx; } catch (Throwable ignored) {}\n");
        sb.append(body);
        if (!normalized.contains("return ")) sb.append("    return null;\n");
        sb.append("  }\n}\n");
        return sb.toString();
    }

    static class EvalResult { final String value; final String error; EvalResult(String v,String e){value=v;error=e;} }
    static class JavaSourceFromString extends SimpleJavaFileObject { final String code;
        JavaSourceFromString(String name,String code){super(URI.create("string:///"+name.replace('.', '/')+Kind.SOURCE.extension), Kind.SOURCE); this.code=code;}
        @Override public CharSequence getCharContent(boolean ignoreEncodingErrors){return code;}
    }
    static class InMemoryFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
        private final Map<String, ByteArrayOutputStream> out = new HashMap<>();
        InMemoryFileManager(StandardJavaFileManager fm){super(fm);} @Override public JavaFileObject getJavaFileForOutput(Location l, String cn, JavaFileObject.Kind k, FileObject s){
            if (k==JavaFileObject.Kind.CLASS){ ByteArrayOutputStream baos=new ByteArrayOutputStream(); out.put(cn,baos);
                return new SimpleJavaFileObject(URI.create("mem:///"+cn), k){@Override public OutputStream openOutputStream(){return baos;}}; }
            return null; }
        Map<String, byte[]> getCompiledClasses(){ Map<String,byte[]> m=new HashMap<>(); out.forEach((k,v)->m.put(k,v.toByteArray())); return m; }
    }
    static class InMemoryClassLoader extends ClassLoader { private final Map<String, byte[]> cls;
        InMemoryClassLoader(Map<String,byte[]> c){super();cls=c;}
        InMemoryClassLoader(ClassLoader parent, Map<String,byte[]> c){super(parent);cls=c;}
        @Override protected Class<?> findClass(String name)throws ClassNotFoundException{ byte[] b=cls.get(name); if(b!=null)return defineClass(name,b,0,b.length); throw new ClassNotFoundException(name);} }
}
