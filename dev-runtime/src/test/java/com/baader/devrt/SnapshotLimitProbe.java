package com.baader.devrt;

import hu.baader.repl.protocol.SnapshotLimits;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Runs in constrained, independent JVMs so full-file memory copies cannot hide behind a large test heap. */
public final class SnapshotLimitProbe {
    public static void main(String[] args) throws Exception {
        Path home = Path.of(System.getProperty("user.home")), exported = home.resolve("exported.json");
        if (args[0].equals("write")) {
            String chunk = "x".repeat(1024 * 1024 - 3);
            SnapshotManager.save("boundary", Collections.nCopies(199, chunk));
            // Version files are immutable; pad the current envelope only.
            Path stored = SnapshotManager.path("boundary");
            if (Files.size(stored) < 199L * 1024 * 1024) throw new AssertionError("Not a large capture");
            // Legal JSON whitespace brings the envelope to exactly 200 MiB, independent of timestamp length.
            byte[] padding = new byte[8192]; Arrays.fill(padding, (byte) ' ');
            long remaining = SnapshotLimits.MAX_BYTES - Files.size(stored);
            try (OutputStream output = Files.newOutputStream(stored, StandardOpenOption.APPEND)) {
                while (remaining > 0) { int length = (int) Math.min(padding.length, remaining); output.write(padding, 0, length); remaining -= length; }
            }
            if (!SnapshotManager.info("boundary").contains("209715200 bytes")) throw new AssertionError("Wrong file size");
            if (SnapshotManager.list().size() != 1) throw new AssertionError("Missing snapshot");
            SnapshotManager.exportFile("boundary", exported);
            if (Files.mismatch(stored, exported) != -1) throw new AssertionError("Export changed data");
            try {
                SnapshotManager.save("boundary", Collections.nCopies(200, chunk)); // Metadata puts it above the cap.
                throw new AssertionError("Oversize write succeeded");
            } catch (IllegalStateException expected) {
                if (!expected.getMessage().contains("200 MiB")) throw expected;
            }
            if (Files.mismatch(stored, exported) != -1) throw new AssertionError("Failed write replaced previous data");
            try (var files = Files.walk(home)) {
                if (files.anyMatch(p -> p.toString().endsWith(".tmp"))) throw new AssertionError("Temporary file leaked");
            }
        } else {
            List<?> restored = SnapshotManager.load("boundary");
            verify(restored);
            SnapshotManager.importFile("imported", exported);
            verify(SnapshotManager.load("imported"));
        }
        System.out.println("200_MIB_OK " + args[0] + " maxHeap=" + Runtime.getRuntime().maxMemory());
    }
    private static void verify(List<?> value) {
        if (value.size() != 199) throw new AssertionError("Wrong list size");
        for (Object item : value) {
            String text = (String) item;
            if (text.length() != 1024 * 1024 - 3 || text.chars().anyMatch(c -> c != 'x')) throw new AssertionError("Payload changed");
        }
    }
}
