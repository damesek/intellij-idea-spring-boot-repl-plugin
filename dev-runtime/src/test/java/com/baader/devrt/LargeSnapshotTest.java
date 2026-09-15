package com.baader.devrt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class LargeSnapshotTest {
    @TempDir Path home;
    @Test void actual200MiBBoundaryStreamingAndNewJvmRestore() throws Exception {
        runProbe("96m", "write");
        runProbe("1024m", "restore");
    }
    private void runProbe(String heap, String mode) throws Exception {
        // Gradle loads test outputs through a URLClassLoader rather than java.class.path.
        Set<String> paths = new LinkedHashSet<>(Arrays.asList(System.getProperty("java.class.path").split(java.io.File.pathSeparator)));
        for (ClassLoader loader = getClass().getClassLoader(); loader != null; loader = loader.getParent()) {
            if (loader instanceof java.net.URLClassLoader urls) for (var url : urls.getURLs())
                if (url.getProtocol().equals("file")) paths.add(Path.of(url.toURI()).toString());
        }
        Path log = home.resolve(mode + ".log");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx" + heap, "-Duser.home=" + home, "-Dsb.repl.applicationId=large-snapshot-test",
                "-cp", String.join(java.io.File.pathSeparator, paths), SnapshotLimitProbe.class.getName(), mode)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(120, TimeUnit.SECONDS), "Large snapshot probe timed out: " + mode);
            assertEquals(0, process.exitValue(), Files.readString(log));
            assertTrue(Files.readString(log).contains("200_MIB_OK"));
        } finally { process.destroyForcibly(); }
    }
}
