package com.baader.devrt;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/** Compilation uses file paths, execution keeps the application's original classloader identity. */
final class AppClassPath {
    private static final Map<String, List<String>> BOOT = new LinkedHashMap<>();
    private static final List<Path> TEMPORARIES = new ArrayList<>();
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            synchronized (AppClassPath.class) {
                for (Path root : TEMPORARIES) try (var files = Files.walk(root)) {
                    for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
                } catch (IOException ignored) {}
            }
        }, "sb-repl-classpath-cleanup"));
    }
    static ClassLoader loader(Object context) {
        if (context != null) {
            try { Object loader = context.getClass().getMethod("getClassLoader").invoke(context); if (loader instanceof ClassLoader cl) return cl; }
            catch (ReflectiveOperationException ignored) {}
            return context.getClass().getClassLoader();
        }
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader == null ? AppClassPath.class.getClassLoader() : loader;
    }
    static Set<String> entries(Object context) {
        Set<String> entries = new LinkedHashSet<>();
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) if (!entry.isBlank()) entries.add(entry);
        try { entries.add(Path.of(ReplBindings.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString()); }
        catch (Exception ignored) {}
        for (ClassLoader cl = loader(context); cl != null; cl = cl.getParent()) {
            if (cl instanceof URLClassLoader urls) for (URL url : urls.getURLs()) {
                if (url.getProtocol().equals("file")) try { entries.add(Path.of(url.toURI()).toString()); } catch (Exception ignored) {}
            }
        }
        for (String entry : List.copyOf(entries)) {
            Path path = Path.of(entry);
            if (Files.isRegularFile(path) && entry.endsWith(".jar")) entries.addAll(bootEntries(path));
        }
        return entries;
    }
    private static synchronized List<String> bootEntries(Path jar) {
        try {
            String key = jar.toAbsolutePath() + ":" + Files.size(jar) + ":" + Files.getLastModifiedTime(jar);
            if (BOOT.containsKey(key)) return BOOT.get(key);
            try (JarFile archive = new JarFile(jar.toFile())) {
                List<JarEntry> relevant = archive.stream().filter(e -> !e.isDirectory() &&
                    (e.getName().startsWith("BOOT-INF/classes/") || (e.getName().startsWith("BOOT-INF/lib/") && e.getName().endsWith(".jar")))).toList();
                if (relevant.isEmpty()) { BOOT.put(key, List.of()); return List.of(); }
                if (relevant.size() > 50000 || TEMPORARIES.size() >= 8) throw new IOException("Boot compilation classpath limit exceeded");
                Path root = Files.createTempDirectory("sb-repl-boot-");
                TEMPORARIES.add(root);
                List<String> paths = new ArrayList<>();
                Path classes = root.resolve("BOOT-INF/classes"); Files.createDirectories(classes); paths.add(classes.toString());
                long total = 0;
                for (JarEntry entry : relevant) {
                    Path file = root.resolve(entry.getName()).normalize();
                    if (!file.startsWith(root)) throw new IOException("Invalid Boot archive path");
                    if (entry.getSize() > 256L * 1024 * 1024) throw new IOException("Boot classpath entry exceeds 256 MiB");
                    Files.createDirectories(file.getParent());
                    try (InputStream in = archive.getInputStream(entry); OutputStream out = Files.newOutputStream(file)) {
                        byte[] buffer = new byte[8192]; int count;
                        while ((count = in.read(buffer)) != -1) {
                            total += count;
                            if (total > 1024L * 1024 * 1024) throw new IOException("Boot compilation classpath exceeds 1 GiB");
                            out.write(buffer, 0, count);
                        }
                    }
                    if (entry.getName().startsWith("BOOT-INF/lib/")) paths.add(file.toString());
                }
                List<String> result = List.copyOf(paths); BOOT.put(key, result); return result;
            }
        } catch (IOException exception) { throw new IllegalStateException("Cannot prepare Boot executable JAR for JShell", exception); }
    }
}
