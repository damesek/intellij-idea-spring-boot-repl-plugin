package hu.baader.repl.protocol;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Properties;

/** Local discovery document; the authentication secret never needs to appear in the Java command line. */
public final class EndpointFile {
    public record Endpoint(int port, String token, long pid) {}
    private EndpointFile() {}
    public static void write(Path target, Endpoint endpoint) throws IOException {
        target = target.toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        if (Files.isSymbolicLink(target)) throw new IOException("Endpoint must not be a symbolic link");
        Path temporary = Files.createTempFile(target.getParent(), ".repl-endpoint-", ".tmp");
        try {
            if (Files.getFileStore(temporary).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            Properties values = new Properties();
            values.setProperty("port", Integer.toString(endpoint.port())); values.setProperty("token", endpoint.token());
            values.setProperty("pid", Long.toString(endpoint.pid())); values.setProperty("protocol", ReplProtocol.VERSION);
            try (OutputStream output = Files.newOutputStream(temporary)) { values.store(output, "sb-repl local endpoint; contains a secret"); }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    public static Endpoint read(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || Files.size(path) > 4096) throw new IOException("Invalid endpoint file");
        Properties values = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] data = input.readNBytes(4097);
            if (data.length > 4096) throw new IOException("Endpoint file too large");
            values.load(new ByteArrayInputStream(data));
        }
        try {
            int port = Integer.parseInt(values.getProperty("port")); long pid = Long.parseLong(values.getProperty("pid"));
            String token = values.getProperty("token", "");
            if (!ReplProtocol.VERSION.equals(values.getProperty("protocol")) || port <= 0 || port > 65535 || pid <= 0 || token.length() < 32 || token.length() > 256) throw new IllegalArgumentException();
            return new Endpoint(port, token, pid);
        } catch (RuntimeException exception) { throw new IOException("Incomplete or incompatible endpoint file", exception); }
    }
}
