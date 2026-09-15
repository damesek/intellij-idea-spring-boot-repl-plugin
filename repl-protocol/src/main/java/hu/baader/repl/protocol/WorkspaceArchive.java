package hu.baader.repl.protocol;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Shared bounded archive I/O. No extraction path comes from the archive. */
public final class WorkspaceArchive {
    public static final int METADATA_BYTES = 8 * 1024 * 1024;
    private WorkspaceArchive() {}
    private static boolean allowed(String name) {
        return name.equals("workspace.json") || name.equals("manifest.json") || name.matches("objects/[a-f0-9]{64}\\.json");
    }
    public static String sha256(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[65536]; int n;
            long total = 0;
            while ((n = input.read(buffer)) >= 0) { total += n; if (total > SnapshotLimits.MAX_BYTES) throw new IOException("Workspace entry exceeds 200 MiB"); digest.update(buffer,0,n); }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static Path privateDirectory() throws IOException {
        Path dir = Files.createTempDirectory("sbrepl-workspace-");
        if (Files.getFileStore(dir).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        return dir;
    }
    public static void removeDirectory(Path dir) throws IOException {
        try (var paths = Files.walk(dir)) { for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
    }
    public static void write(Path target, Map<String,Path> files) throws IOException {
        if (Files.isSymbolicLink(target) || files.size() > 1202 || !files.keySet().containsAll(Set.of("workspace.json", "manifest.json")) || files.keySet().stream().anyMatch(n -> !allowed(n)))
            throw new IOException("Invalid workspace destination or entries");
        Path temp = Files.createTempFile(target.toAbsolutePath().getParent(), ".workspace-", ".tmp");
        try {
            if (Files.getFileStore(temp).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
            long total = 0;
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temp))) {
                for (var entry : files.entrySet()) {
                    Path p = entry.getValue();
                    if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Workspace sources must be regular files");
                    int limit = entry.getKey().startsWith("objects/") ? SnapshotLimits.MAX_BYTES : METADATA_BYTES;
                    if (Files.size(p) > limit) throw new IOException("Workspace entry exceeds its size limit");
                    zip.putNextEntry(new ZipEntry(entry.getKey())); long count = 0;
                    try (InputStream in = Files.newInputStream(p, LinkOption.NOFOLLOW_LINKS)) {
                        byte[] buffer = new byte[65536]; int n;
                        while ((n = in.read(buffer)) >= 0) {
                            count += n; total += n;
                            if (count > limit || total > SnapshotLimits.MAX_BYTES) throw new IOException("Workspace exceeds 200 MiB uncompressed");
                            zip.write(buffer,0,n);
                        }
                    }
                    zip.closeEntry();
                }
            }
            if (Files.size(temp) > SnapshotLimits.MAX_BYTES) throw new IOException("Workspace ZIP exceeds 200 MiB");
            if (Files.isSymbolicLink(target)) throw new IOException("Workspace target must not be a symbolic link");
            Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    public static Contents read(Path source) throws IOException {
        if (!Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS) || Files.size(source) > SnapshotLimits.MAX_BYTES) throw new IOException("Invalid workspace file (maximum 200 MiB)");
        Path temp = privateDirectory(); Map<String,Path> files = new LinkedHashMap<>();
        try {
            long total = 0;
            try (ZipFile zip = new ZipFile(source.toFile())) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement(); String name = entry.getName();
                    if (!allowed(name) || entry.isDirectory() || files.containsKey(name) || files.size() >= 1202) throw new IOException("Unexpected or duplicate workspace entry");
                    int limit = name.startsWith("objects/") ? SnapshotLimits.MAX_BYTES : METADATA_BYTES;
                    if (entry.getSize() < 0 || entry.getSize() > limit) throw new IOException("Invalid workspace entry size");
                    Path path = temp.resolve(Integer.toString(files.size())); files.put(name,path);
                    long count = 0; CRC32 crc = new CRC32();
                    try (InputStream in = zip.getInputStream(entry); OutputStream out = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)) {
                        byte[] buffer = new byte[65536]; int n;
                        while ((n=in.read(buffer)) >= 0) {
                            count+=n; total+=n;
                            if(count>limit || total>SnapshotLimits.MAX_BYTES)throw new IOException("Workspace exceeds 200 MiB uncompressed");
                            crc.update(buffer,0,n); out.write(buffer,0,n);
                        }
                    }
                    if(count!=entry.getSize() || crc.getValue()!=entry.getCrc())throw new IOException("Workspace ZIP checksum mismatch");
                    if(name.startsWith("objects/") && !name.equals("objects/"+sha256(path)+".json"))throw new IOException("Workspace snapshot SHA-256 mismatch");
                }
            }
            if(!files.keySet().containsAll(Set.of("workspace.json","manifest.json")))throw new IOException("Incomplete workspace");
            return new Contents(temp,Collections.unmodifiableMap(files));
        } catch(Throwable failure) { try { removeDirectory(temp); } catch(IOException cleanup) { failure.addSuppressed(cleanup); } throw failure; }
    }
    public record Contents(Path directory, Map<String,Path> files) implements AutoCloseable {
        @Override public void close() throws IOException { removeDirectory(directory); }
    }
}
