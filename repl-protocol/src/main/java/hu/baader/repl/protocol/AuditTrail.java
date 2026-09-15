package hu.baader.repl.protocol;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Private append-only JSONL, with bounded rotation. Local audit is not tamper-proof against the host user. */
public final class AuditTrail {
    private static final long ROTATE_BYTES = 4 * 1024 * 1024;
    private AuditTrail() {}
    public static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    public static synchronized String append(String channel, Map<String,String> fields) {
        String id = UUID.randomUUID().toString();
        Map<String,String> entry = new LinkedHashMap<>();
        entry.put("id", id); entry.put("timestamp", Instant.now().toString()); entry.putAll(fields);
        StringBuilder line = new StringBuilder("{");
        entry.forEach((key, value) -> {
            if (line.length() > 1) line.append(',');
            String redacted = SensitiveValues.sensitiveName(key) ? "[REDACTED]" : SensitiveValues.redact(value);
            line.append(quote(key)).append(':').append(quote(redacted.length() > 1000000 ? redacted.substring(0,1000000) + "[truncated]" : redacted));
        });
        line.append("}\n");
        try {
            Path target = file(channel);
            if (Files.isSymbolicLink(target)) throw new java.io.IOException("Audit file cannot be a symbolic link");
            if (Files.exists(target) && Files.size(target) >= ROTATE_BYTES) {
                for (int i = 4; i >= 1; i--) {
                    Path from = target.resolveSibling(target.getFileName() + "." + i), to = target.resolveSibling(target.getFileName() + "." + (i+1));
                    if (Files.isSymbolicLink(from) || Files.isSymbolicLink(to)) throw new java.io.IOException("Invalid audit rotation path");
                    if (Files.exists(from)) Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(target, target.resolveSibling(target.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
            }
            if (!Files.exists(target)) {
                if (Files.getFileStore(target.getParent()).supportsFileAttributeView("posix")) Files.createFile(target, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                else Files.createFile(target);
            }
            if (Files.getFileStore(target).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));
            Files.writeString(target, line, StandardCharsets.UTF_8, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS);
            return id;
        } catch (java.io.IOException failure) { throw new IllegalStateException("Cannot write audit log; operation was not silently left unaudited", failure); }
    }
    public static synchronized String recent(String channel, String owner, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Audit page limit is 1–100");
        try {
            Path file = file(channel);
            if (!Files.exists(file)) return "";
            if (Files.isSymbolicLink(file)) throw new java.io.IOException("Invalid audit file");
            ArrayDeque<String> rows = new ArrayDeque<>();
            // Read strings only; audit data never instantiates application classes.
            try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
                lines.filter(line -> owner.isBlank() || line.contains("\"session\":" + quote(owner))).forEach(line -> {
                    rows.addLast(line.length() > 16384 ? line.substring(0,16384) + " [preview truncated]" : line);
                    if (rows.size() > limit) rows.removeFirst();
                });
            }
            String joined = String.join("\n", rows);
            return joined.length() > 65536 ? joined.substring(joined.length()-65536) : joined;
        } catch (java.io.IOException failure) { throw new IllegalStateException("Cannot read audit log", failure); }
    }
    public static Path file(String channel) throws java.io.IOException {
        if (!channel.matches("[a-z0-9-]{1,64}")) throw new IllegalArgumentException("Invalid audit channel");
        Path directory = Path.of(System.getProperty("sb.repl.audit.dir", Path.of(System.getProperty("user.home"), ".java-repl-audit").toString())).toAbsolutePath().normalize();
        if (Files.isSymbolicLink(directory)) throw new java.io.IOException("Invalid audit directory");
        Files.createDirectories(directory);
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        return directory.resolve(channel + ".jsonl");
    }
    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) switch(c) {
            case '\\' -> out.append("\\\\"); case '"' -> out.append("\\\"");
            case '\n' -> out.append("\\n"); case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
            default -> { if (c < 32) out.append(String.format("\\u%04x", (int)c)); else out.append(c); }
        }
        return out.append('"').toString();
    }
}
