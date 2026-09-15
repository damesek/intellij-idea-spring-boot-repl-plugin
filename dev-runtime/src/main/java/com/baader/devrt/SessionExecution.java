package com.baader.devrt;

import jdk.jshell.execution.DirectExecutionControl;
import jdk.jshell.execution.LoaderDelegate;
import jdk.jshell.spi.ExecutionControl;
import java.io.File;
import java.lang.reflect.Method;
import java.net.*;
import java.util.*;

final class SessionExecution extends DirectExecutionControl {
    private final Loader loader;
    private volatile Thread running;
    private volatile boolean cancelled;
    private Object result, variable;
    private boolean hasResult;
    SessionExecution(ClassLoader parent) { this(new Loader(parent)); }
    private SessionExecution(Loader loader) { super(loader); this.loader = loader; }
    void setParent(ClassLoader parent) { loader.applicationLoader = parent; }
    @Override protected String invoke(Method method) throws Exception {
        if (cancelled) throw new InterruptedException("Evaluation interrupted");
        result = method.invoke(null); hasResult = true;
        // Escape only a bounded preview; keep the complete result available to variables and snapshots.
        if (result instanceof String string) return valueString(JShellSession.limit(string));
        return result instanceof Character ? valueString(result) : JShellSession.preview(result);
    }
    @Override public String varValue(String className, String variableName) throws ExecutionControl.RunException, ExecutionControl.EngineTerminationException, ExecutionControl.InternalException {
        try {
            variable = null;
            var field = findClass(className).getDeclaredField(variableName);
            field.setAccessible(true);
            variable = field.get(null);
            return JShellSession.preview(variable);
        }
        catch (Exception exception) { throw new ExecutionControl.InternalException(exception.toString()); }
    }
    Object variableValue() { return variable; }
    Object result() { return result; }
    boolean hasResult() { return hasResult; }
    void clearResult() { result = null; hasResult = false; }
    void begin() { cancelled = false; running = Thread.currentThread(); }
    boolean isCancelled() { return cancelled; }
    void acknowledgeInterrupt() { if (cancelled) Thread.interrupted(); cancelled = false; }
    void end() { running = null; acknowledgeInterrupt(); }
    void interrupt() { Thread thread = running; if (thread != null) { cancelled = true; thread.interrupt(); } }
    @Override public void stop() { interrupt(); }
    @Override public void close() { result = variable = null; loader.bytes.clear(); loader.loaded.clear(); try { loader.close(); } catch (Exception ignored) {} }

    private static final class Loader extends URLClassLoader implements LoaderDelegate {
        private final Map<String, byte[]> bytes = new HashMap<>();
        private final Map<String, Class<?>> loaded = new HashMap<>();
        private volatile ClassLoader applicationLoader;
        Loader(ClassLoader parent) { super(new URL[0], ReplBindings.class.getClassLoader()); applicationLoader = parent; }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> type = findLoadedClass(name);
                if (type == null && bytes.containsKey(name)) type = findClass(name);
                if (type == null && !name.startsWith("com.baader.devrt.") && !name.startsWith("hu.baader.repl.protocol.")) {
                    try { type = applicationLoader.loadClass(name); } catch (ClassNotFoundException ignored) {}
                }
                if (type == null) type = super.loadClass(name, false);
                if (resolve) resolveClass(type);
                return type;
            }
        }
        @Override public Class<?> findClass(String name) throws ClassNotFoundException {
            Class<?> existing = loaded.get(name);
            if (existing != null) return existing;
            byte[] code = bytes.get(name);
            if (code == null) return super.findClass(name);
            Class<?> type = defineClass(name, code, 0, code.length); loaded.put(name, type); return type;
        }
        @Override public void load(ExecutionControl.ClassBytecodes[] classes) throws ExecutionControl.ClassInstallException {
            boolean[] installed = new boolean[classes.length];
            try {
                for (var item : classes) bytes.put(item.name(), item.bytecodes());
                for (int i = 0; i < classes.length; i++) { loadClass(classes[i].name()); installed[i] = true; }
            } catch (Throwable exception) { throw new ExecutionControl.ClassInstallException(exception.toString(), installed); }
        }
        @Override public void classesRedefined(ExecutionControl.ClassBytecodes[] classes) {}
        @Override public void addToClasspath(String path) throws ExecutionControl.InternalException {
            try { for (String entry : path.split(File.pathSeparator)) addURL(new File(entry).toURI().toURL()); }
            catch (MalformedURLException exception) { throw new ExecutionControl.InternalException(exception.toString()); }
        }
    }
}
