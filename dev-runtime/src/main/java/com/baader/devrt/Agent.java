package com.baader.devrt;

import hu.baader.repl.protocol.EndpointFile;
import java.io.*;
import java.lang.instrument.Instrumentation;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/** Minimal bootstrap: optional instrumentation failures must never abort the application's main. */
public final class Agent {
    private static MiniNreplServer server;
    private static URLClassLoader instrumentationLoader;
    private static Path endpoint;
    private static EndpointFile.Endpoint address;
    private static final Set<Path> endpointFiles = new HashSet<>();
    public static void premain(String args, Instrumentation instrumentation) { start(args, instrumentation); }
    public static void agentmain(String args, Instrumentation instrumentation) { start(args, instrumentation); }

    private static synchronized void start(String args, Instrumentation instrumentation) {
        try {
            AgentRuntime.setInstrumentation(instrumentation);
            Map<String, String> options = new HashMap<>();
            if (args != null) for (String part : args.split(",")) {
                String[] pair = part.split("=", 2);
                if (pair.length == 2) options.put(pair[0], pair[1]);
            }
            Path requested = options.containsKey("endpoint64") ? Path.of(new String(Base64.getUrlDecoder().decode(options.get("endpoint64")), java.nio.charset.StandardCharsets.UTF_8)) :
                    options.containsKey("endpoint") ? Path.of(options.get("endpoint")) :
                    Path.of(System.getProperty("user.home"), ".sb-repl", "endpoints", ProcessHandle.current().pid() + ".properties");
            if (server != null) {
                try { EndpointFile.write(requested, address); endpointFiles.add(requested); }
                catch (IOException failure) { System.err.println("[sb-repl] Cannot publish existing endpoint: " + failure.getMessage()); }
                return;
            }
            int port = Integer.parseInt(options.getOrDefault("port", "0"));
            if (port < 0 || port > 65535) throw new IllegalArgumentException("Invalid port");
            String token = UUID.randomUUID().toString() + UUID.randomUUID();
            endpoint = requested;
            Path jar = Path.of(Agent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(jar)) {
                instrumentation.appendToSystemClassLoaderSearch(new JarFile(jar.toFile()));
                try { installInstrumentation(jar, instrumentation); }
                catch (Throwable exception) { System.err.println("[sb-repl] Context instrumentation unavailable: " + exception.getClass().getSimpleName() + ". Use the Spring bridge or explicit context binding."); }
            }
            server = new MiniNreplServer(port, token);
            server.start();
            address = new EndpointFile.Endpoint(server.port(), token, ProcessHandle.current().pid());
            EndpointFile.write(endpoint, address);
            endpointFiles.add(endpoint);
            AutoBinder.tryBindOnce();
            Runtime.getRuntime().addShutdownHook(new Thread(Agent::close, "sb-repl-shutdown"));
            System.out.println("[sb-repl] Ready on loopback port " + server.port() + "; endpoint: " + endpoint);
        } catch (Throwable exception) {
            close();
            System.err.println("[sb-repl] Agent disabled; application startup continues: " + exception.getMessage());
        }
    }

    private static void installInstrumentation(Path jar, Instrumentation instrumentation) throws Exception {
        try { installAsyncBridge(instrumentation); }
        catch(Exception|LinkageError failure) { System.err.println("[sb-repl] Async recording unavailable: "+failure.getClass().getSimpleName()); }
        Path libraries = Files.createTempDirectory("sb-repl-agent-libs-");
        libraries.toFile().deleteOnExit();
        List<URL> urls = new ArrayList<>(); urls.add(jar.toUri().toURL());
        try (JarFile archive = new JarFile(jar.toFile())) {
            for (JarEntry entry : archive.stream().filter(e -> e.getName().startsWith("agent-libs/") && e.getName().endsWith(".jar")).toList()) {
                Path file = libraries.resolve(Path.of(entry.getName()).getFileName().toString());
                try (InputStream input = archive.getInputStream(entry)) { Files.copy(input, file); }
                file.toFile().deleteOnExit(); urls.add(file.toUri().toURL());
            }
        }
        if (urls.size() < 2) throw new IOException("Bundled Byte Buddy library missing");
        instrumentationLoader = new URLClassLoader(urls.toArray(URL[]::new), Agent.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    if (name.startsWith("net.bytebuddy.") || name.startsWith("com.baader.devrt.AgentInstrumentation")) {
                        Class<?> loaded = findLoadedClass(name);
                        if (loaded == null) loaded = findClass(name);
                        if (resolve) resolveClass(loaded);
                        return loaded;
                    }
                    return super.loadClass(name, resolve);
                }
            }
        };
        instrumentationLoader.loadClass("com.baader.devrt.AgentInstrumentation").getMethod("install", Instrumentation.class).invoke(null, instrumentation);
    }
    private static void installAsyncBridge(Instrumentation instrumentation) throws Exception {
        Path bridge=Files.createTempFile("sb-repl-bootstrap-", ".jar");bridge.toFile().deleteOnExit();
        String entry="com/baader/devrt/bootstrap/AsyncBridge.class";
        try(var output=new JarOutputStream(Files.newOutputStream(bridge));InputStream input=Agent.class.getResourceAsStream("/"+entry)){
            if(input==null)throw new IOException("Missing bootstrap recording bridge");output.putNextEntry(new JarEntry(entry));input.transferTo(output);output.closeEntry();
        }
        instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(bridge.toFile()));
        Class<?> type=Class.forName("com.baader.devrt.bootstrap.AsyncBridge",true,null);
        // java.base must be able to call the tiny bridge in the bootstrap unnamed module.
        instrumentation.redefineModule(Object.class.getModule(),Set.of(type.getModule()),Map.of(),Map.of(),Set.of(),Map.of());
        type.getField("handler").set(null,(java.util.function.Function<Object[],Object>)AsyncRecorder::dispatch);
    }
    static void configureTrace(Class<?> type, Set<String> methods) throws Exception {
        if (instrumentationLoader == null) throw new IllegalStateException("Tracing requires the bundled agent at JVM startup");
        try {
            instrumentationLoader.loadClass("com.baader.devrt.AgentInstrumentation")
                    .getMethod("configureTrace", Class.class, Set.class).invoke(null, type, methods);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            throw new IllegalStateException("Cannot update tracing: " + failure.getCause().getMessage(), failure.getCause());
        }
    }
    private static synchronized void close() {
        if (server != null) { server.close(); server = null; }
        for (Path file : endpointFiles) try { Files.deleteIfExists(file); } catch (IOException ignored) {}
        endpointFiles.clear(); address = null;
        SpringContextHolder.set(null);
    }
}
