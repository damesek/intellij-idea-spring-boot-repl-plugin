package com.baader.devrt;

import hu.baader.repl.protocol.SnapshotLimits;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.util.*;

/** Content-addressed immutable files. The v1 current file remains readable by earlier releases. */
final class SnapshotVersions {
    private SnapshotVersions() {}
    private static Path directory(Path current) throws IOException {
        Path root = current.getParent().resolve("versions");
        Path dir = root.resolve(current.getFileName().toString().replace(".json", ""));
        for (Path p : List.of(root, dir)) {
            if (Files.isSymbolicLink(p)) throw new IOException("Version directories must not be symbolic links");
            Files.createDirectories(p);
            if (Files.getFileStore(p).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwx------"));
        }
        return dir;
    }
    static String checksum(Path path) throws IOException {
        try (InputStream in = SnapshotIO.input(path, SnapshotLimits.MAX_BYTES)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536]; int n;
            while ((n = in.read(buffer)) >= 0) digest.update(buffer, 0, n);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    @FunctionalInterface interface Action<T> { T run() throws IOException; }
    static <T> T locked(Path current, Action<T> action) throws IOException {
        synchronized (SnapshotIO.COMMIT_LOCK) {
            Path lock = current.getParent().resolve(".repository.lock");
            if (Files.isSymbolicLink(lock)) throw new IOException("Repository lock must not be a symbolic link");
            try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 var ignored = channel.lock()) { return action.run(); }
        }
    }
    static void commit(Path temporary, Path current) throws IOException {
        locked(current, () -> {
            if (Files.isSymbolicLink(current)) throw new IOException("Snapshot destination must not be a symbolic link");
            if (!Files.exists(current)) try (var entries = Files.list(current.getParent())) {
                if (entries.filter(p -> p.getFileName().toString().matches("[a-f0-9]{64}\\.json")).count() >= 1000)
                    throw new IOException("Snapshot limit reached (1000)");
            }
            Path dir = directory(current);
            String before = Files.exists(current) ? checksum(current) : null;
            String after = checksum(temporary);
            Set<String> existing = new HashSet<>();
            try (var files = Files.list(dir)) { files.forEach(p -> existing.add(p.getFileName().toString())); }
            int additions = (existing.contains(after + ".json") ? 0 : 1) + (before == null || before.equals(after) || existing.contains(before + ".json") ? 0 : 1);
            if (existing.size() + additions > 100) throw new IOException("Snapshot version limit reached (100); export history and explicitly delete the snapshot, or use a new name");
            if (before != null) archive(current, dir.resolve(before + ".json"), before);
            Path saved = dir.resolve(after + ".json");
            boolean created = !Files.exists(saved);
            archive(temporary, saved, after);
            try { Files.move(temporary, current, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (IOException failure) { if (created) Files.deleteIfExists(saved); throw failure; }
            return null;
        });
    }
    private static void archive(Path source, Path destination, String sha) throws IOException {
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            if (!sha.equals(checksum(destination))) throw new IOException("Snapshot history checksum mismatch");
            return;
        }
        // A copied immutable file also protects history against modifications by older plugin versions.
        SnapshotIO.atomicWrite(destination, false, out -> { try (InputStream in = SnapshotIO.input(source, SnapshotLimits.MAX_BYTES)) { in.transferTo(out); } });
    }
    static Path version(Path current, String id) throws IOException {
        if (id == null || !id.matches("[a-f0-9]{64}")) throw new IOException("Invalid snapshot version (expected SHA-256)");
        Path candidate = directory(current).resolve(id + ".json");
        if (!Files.exists(candidate) && Files.exists(current) && checksum(current).equals(id)) return current; // Legacy v1 file.
        if (!id.equals(checksum(candidate))) throw new IOException("Snapshot history checksum mismatch");
        return candidate;
    }
    static List<Map<String,Object>> list(Path current) throws IOException {
        return locked(current, () -> listLocked(current));
    }
    private static List<Map<String,Object>> listLocked(Path current) throws IOException {
        Map<String,Map<String,Object>> result = new LinkedHashMap<>();
        String active = Files.exists(current) ? checksum(current) : "";
        List<Path> files;
        try (var entries = Files.list(directory(current))) { files = entries.limit(101).toList(); }
        if (files.size() > 100) throw new IOException("Snapshot version limit exceeded");
        if (Files.exists(current)) { files = new ArrayList<>(files); files.add(current); }
        for (Path p : files) {
            String id = p.equals(current) ? active : p.getFileName().toString().replace(".json", "");
            if (!id.matches("[a-f0-9]{64}")) continue;
            Map<String,Object> row = new LinkedHashMap<>(SnapshotManager.metadata(p));
            row.put("version", id); row.put("current", id.equals(active)); result.put(id, row);
        }
        return result.values().stream().sorted(Comparator.comparing((Map<String,Object> m) -> String.valueOf(m.getOrDefault("recordedAt", m.get("capturedAt")))).reversed()).toList();
    }
    static void delete(Path current) throws IOException {
        locked(current, () -> {
            Path dir = directory(current);
            try (var files = Files.list(dir)) { for (Path p : files.toList()) {
                if (!p.getFileName().toString().matches("[a-f0-9]{64}\\.json") || Files.isSymbolicLink(p)) throw new IOException("Unexpected snapshot history entry");
            } }
            Files.deleteIfExists(current);
            try (var files = Files.list(dir)) { for (Path p : files.toList()) Files.delete(p); }
            Files.deleteIfExists(dir); return null;
        });
    }
    static void importBatch(Map<Path,Path> prepared) throws IOException {
        if (prepared.isEmpty()) return;
        locked(prepared.keySet().iterator().next(), () -> {
            for (Path target : prepared.keySet()) if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Workspace import name already exists; choose a different prefix");
            try (var entries = Files.list(prepared.keySet().iterator().next().getParent())) {
                if (entries.filter(p -> p.getFileName().toString().matches("[a-f0-9]{64}\\.json")).count() + prepared.size() > 1000) throw new IOException("Workspace import exceeds snapshot limit (1000)");
            }
            List<Path> created = new ArrayList<>();
            try {
                for (var entry : prepared.entrySet()) {
                    Path current = entry.getKey(), source = entry.getValue(), dir = directory(current);
                    try (var existing = Files.list(dir)) { if (existing.findAny().isPresent()) throw new IOException("Workspace import name has existing history; choose a different prefix"); }
                    Path archived = dir.resolve(checksum(source) + ".json");
                    archive(source, archived, checksum(source)); created.add(archived);
                    SnapshotIO.atomicWrite(current, false, out -> { try (InputStream in = SnapshotIO.input(source, SnapshotLimits.MAX_BYTES)) { in.transferTo(out); } });
                    created.add(current);
                }
            } catch (IOException | RuntimeException failure) {
                Collections.reverse(created);
                for (Path file : created) try { Files.deleteIfExists(file); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
            return null;
        });
    }
}
