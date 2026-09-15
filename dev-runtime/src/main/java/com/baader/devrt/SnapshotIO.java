package com.baader.devrt;

import hu.baader.repl.protocol.SnapshotLimits;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;

/** Bounded disk I/O without full-file byte arrays or locks around application serializers. */
final class SnapshotIO {
    static final Object COMMIT_LOCK = new Object();
    @FunctionalInterface interface Writer { void write(OutputStream output) throws IOException; }
    private SnapshotIO() {}

    static InputStream input(Path path, int limit) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Snapshot source must be a regular file, not a symbolic link");
        if (Files.size(path) > limit) throw tooLarge(limit);
        return new FilterInputStream(Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            long count;
            @Override public int read() throws IOException {
                int value = in.read();
                if (value >= 0 && ++count > limit) throw tooLarge(limit);
                return value;
            }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                int read = in.read(bytes, offset, (int) Math.min(length, limit - count + 1));
                if (read > 0 && (count += read) > limit) throw tooLarge(limit);
                return read;
            }
            @Override public long skip(long count) throws IOException {
                long remaining = count;
                byte[] buffer = new byte[8192];
                while (remaining > 0) {
                    int read = read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (read < 0) break;
                    remaining -= read;
                }
                return count - remaining;
            }
        };
    }

    static void atomicWrite(Path destination, boolean repository, Writer writer) throws IOException {
        Path temporary = Files.createTempFile(destination.getParent(), ".snapshot-", ".tmp");
        try {
            if (Files.getFileStore(temporary).supportsFileAttributeView("posix"))
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            try (OutputStream output = new FilterOutputStream(Files.newOutputStream(temporary)) {
                long count;
                @Override public void write(int value) throws IOException {
                    if (++count > SnapshotLimits.MAX_BYTES) throw tooLarge(SnapshotLimits.MAX_BYTES);
                    out.write(value);
                }
                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    if (length > SnapshotLimits.MAX_BYTES - count) throw tooLarge(SnapshotLimits.MAX_BYTES);
                    count += length; out.write(bytes, offset, length);
                }
            }) { writer.write(output); }
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            synchronized (COMMIT_LOCK) {
                if (Files.isSymbolicLink(destination)) throw new IOException("Snapshot destination must not be a symbolic link");
                if (repository && !Files.exists(destination)) try (var files = Files.list(destination.getParent())) {
                    if (files.filter(p -> p.toString().endsWith(".json")).count() >= 1000)
                        throw new IOException("Snapshot limit reached (1000)");
                }
                if (repository) SnapshotVersions.commit(temporary, destination);
                else Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }

    private static IOException tooLarge(int limit) {
        return new IOException("Snapshot exceeds " + (limit / 1024 / 1024) + " MiB"
                + (limit == SnapshotLimits.INLINE_JSON_BYTES ? "; use file import/export for up to 200 MiB" : " (including metadata)"));
    }
}
