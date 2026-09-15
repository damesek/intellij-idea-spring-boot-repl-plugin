package com.baader.devrt;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

public class SnapshotManagerTest {
    @TempDir Path home;
    private String oldHome, oldApp;
    public record Item(String name, LocalDate date) {}
    public record PrivateData(String name, String secret) {}
    public abstract static class HideSecret { @JsonIgnore public abstract String secret(); }
    public static class Broken { public String getValue() { throw new IllegalStateException("serialization failure"); } }
    @BeforeEach void setup() {
        oldHome = System.getProperty("user.home"); oldApp = System.getProperty("sb.repl.applicationId");
        System.setProperty("user.home", home.toString()); System.setProperty("sb.repl.applicationId", "snapshot-test");
        SpringContextHolder.set(null); SnapshotManager.clearLive();
    }
    @AfterEach void cleanup() {
        SnapshotManager.clearLive(); SpringContextHolder.set(null); System.setProperty("user.home", oldHome);
        if (oldApp == null) System.clearProperty("sb.repl.applicationId"); else System.setProperty("sb.repl.applicationId", oldApp);
    }
    public static class SlowDto {
        private final java.util.concurrent.CountDownLatch started, release;
        SlowDto(java.util.concurrent.CountDownLatch started, java.util.concurrent.CountDownLatch release) { this.started=started; this.release=release; }
        public String getValue() throws InterruptedException { started.countDown(); release.await(10, TimeUnit.SECONDS); return "saved"; }
    }
    @Test void userSerializerCannotBlockSpringContextRelease() throws Exception {
        var started=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        Object context=new org.springframework.context.support.GenericApplicationContext(); SpringContextHolder.set(context);
        var saving=java.util.concurrent.CompletableFuture.runAsync(()->SnapshotManager.save("slow",new SlowDto(started,release)));
        try {
            assertTrue(started.await(5,TimeUnit.SECONDS));
            java.util.concurrent.CompletableFuture.runAsync(()->SpringContextHolder.clear(context)).get(2,TimeUnit.SECONDS);
            assertNull(SpringContextHolder.get());
        } finally { release.countDown(); saving.get(5,TimeUnit.SECONDS); }
    }
    @Test void typedDtoGenericListDateAndNullRoundTrip() {
        var item = new Item("árvíz 🧪", LocalDate.of(2026, 9, 13));
        SnapshotManager.save("dto", item); assertEquals(item, SnapshotManager.load("dto"));
        SnapshotManager.save("list", List.of(item), "java.util.List<" + Item.class.getName() + ">");
        assertEquals(List.of(item), SnapshotManager.load("list"));
        SnapshotManager.save("null", null); assertNull(SnapshotManager.load("null"));
    }
    @Test void dataSurvivesNewJvm() throws Exception {
        SnapshotManager.save("dto", new Item("persisted", LocalDate.of(2026, 9, 13)));
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Duser.home=" + home, "-Dsb.repl.applicationId=snapshot-test", "-cp", System.getProperty("java.class.path"),
            SnapshotManagerTest.class.getName()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Snapshot read timed out");
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output); assertTrue(output.contains("ROUNDTRIP_OK"), output);
        } finally { process.destroyForcibly(); }
    }
    public static void main(String[] args) {
        Item item = SnapshotManager.load("dto");
        if (!item.equals(new Item("persisted", LocalDate.of(2026, 9, 13)))) throw new AssertionError(item);
        System.out.println("ROUNDTRIP_OK");
    }
    @Test void pinsAreScopedLiveReferencesAndClearedOnEpochChange() {
        var values = new ArrayList<>(List.of("a"));
        try (var ignored = SnapshotManager.scope("one")) { SnapshotManager.pin("live", values); values.add("b"); assertSame(values, SnapshotManager.load("live")); }
        try (var ignored = SnapshotManager.scope("two")) { assertThrows(IllegalStateException.class, () -> SnapshotManager.load("live")); }
        SpringContextHolder.set(new Object());
        try (var ignored = SnapshotManager.scope("one")) { assertThrows(IllegalStateException.class, () -> SnapshotManager.load("live")); }
    }
    @Test void failedOverwritePreservesPreviousDataAndDoesNotFallback() {
        SnapshotManager.save("value", "original");
        assertThrows(IllegalStateException.class, () -> SnapshotManager.save("value", new Broken()));
        assertEquals("original", SnapshotManager.load("value"));
        assertThrows(IllegalStateException.class, () -> SnapshotManager.save("new", new Broken()));
        assertThrows(IllegalStateException.class, () -> SnapshotManager.load("new"));
    }
    @Test void oversizedDataPreservesPreviousValue() {
        SnapshotManager.save("size", 42);
        assertThrows(IllegalStateException.class, () -> SnapshotManager.save("size", Collections.nCopies(200, "x".repeat(1024 * 1024 - 3))));
        assertEquals(Integer.valueOf(42), (Object) SnapshotManager.load("size"));
    }
    @Test void largeStringRoundTripAndFileTransferPreserveData() throws Exception {
        String value = "árvíz 🧪".repeat(3_000_000); // Exceeds both the old 2 MiB cap and Jackson's default string limit.
        SnapshotManager.save("large-text", value);
        assertEquals(value, SnapshotManager.load("large-text"));
        assertTrue(SnapshotManager.info("large-text").contains("200 MiB"));
        assertTrue(SnapshotManager.list().stream().anyMatch(s -> s.startsWith("large-text\t")));
        assertTrue(assertThrows(IllegalStateException.class, () -> SnapshotManager.json("large-text")).getMessage().contains("file import/export"));
        Path exported = home.resolve("export.json"); SnapshotManager.exportFile("large-text", exported);
        SnapshotManager.importFile("imported", exported);
        assertEquals(value, SnapshotManager.load("imported"));
    }
    @Test void fileImportFailurePreservesOldDataAndRejectsOversizeAndSymlinks() throws Exception {
        SnapshotManager.save("existing", 42);
        Path invalid = home.resolve("invalid.json"); Files.writeString(invalid, "{broken");
        assertThrows(IllegalStateException.class, () -> SnapshotManager.importFile("existing", invalid));
        Files.writeString(invalid, "42 43");
        assertThrows(IllegalStateException.class, () -> SnapshotManager.importFile("existing", invalid));
        Path huge = home.resolve("oversize.json");
        try (var file = new java.io.RandomAccessFile(huge.toFile(), "rw")) { file.setLength(hu.baader.repl.protocol.SnapshotLimits.MAX_BYTES + 1L); }
        assertThrows(IllegalStateException.class, () -> SnapshotManager.importFile("existing", huge));
        Path link = home.resolve("link.json"); Files.createSymbolicLink(link, invalid);
        assertThrows(IllegalStateException.class, () -> SnapshotManager.importFile("existing", link));
        assertThrows(IllegalStateException.class, () -> SnapshotManager.exportFile("existing", link));
        assertEquals("42 43", Files.readString(invalid));
        assertEquals(Integer.valueOf(42), (Object) SnapshotManager.load("existing"));
        try (var files = Files.walk(home)) { assertTrue(files.noneMatch(f -> f.toString().endsWith(".tmp"))); }
    }
    @Test void largeSnapshotConstraintsAndHeaderOrderDoNotModifyApplicationMapper() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        int originalLimit = mapper.getFactory().streamReadConstraints().getMaxStringLength();
        try (var context = new org.springframework.context.support.GenericApplicationContext()) {
            context.getBeanFactory().registerSingleton("objectMapper", mapper); context.refresh(); SpringContextHolder.set(context);
            SnapshotManager.save("ordered", Map.of("b", 2, "a", 1));
            String json = SnapshotManager.json("ordered");
            assertTrue(json.indexOf("schemaVersion") < json.indexOf("payload"));
            assertTrue(SnapshotManager.info("ordered").contains("LinkedHashMap"));
            assertEquals(originalLimit, mapper.getFactory().streamReadConstraints().getMaxStringLength());
            assertTrue(mapper.isEnabled(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS));
        }
    }
    @Test void oneRepositoryAndExplicitModeTransitions() {
        SnapshotStore.pin("switch", "live");
        SnapshotManager.save("switch", "data");
        assertEquals("data", SnapshotStore.get("switch"));
        assertTrue(SnapshotManager.list().stream().anyMatch(s -> s.endsWith("\tDATA")));
        assertThrows(IllegalArgumentException.class, () -> SnapshotStore.pin("switch", "stale"));
        SnapshotStore.delete("switch");
        assertThrows(IllegalStateException.class, () -> SnapshotManager.load("switch"));
    }
    @Test void recipesLoadAsCodeWithoutExecuting() {
        SnapshotManager.saveRecipe("setup", "throw new RuntimeException(\"must not execute\");");
        assertTrue(SnapshotManager.loadRecipe("setup").startsWith("throw"));
        assertThrows(IllegalArgumentException.class, () -> SnapshotManager.load("setup"));
    }
    @Test void importedTypeMetadataDoesNotInstantiateClasses() {
        SnapshotManager.importJson("import", "{\"schemaVersion\":1,\"declaredType\":\"java.lang.ProcessBuilder\",\"payload\":{\"safe\":42}}");
        assertEquals(Map.of("safe",42), SnapshotManager.load("import"));
        assertThrows(IllegalArgumentException.class, () -> SnapshotManager.loadTyped("import", "java.lang.ProcessBuilder"));
    }
    @Test void mixInRedactionIsSnapshotSpecific() throws Exception {
        SnapshotManager.addMixIn(PrivateData.class, HideSecret.class);
        SnapshotManager.save("redacted", new PrivateData("visible", "fake-test-secret"));
        String json = SnapshotManager.json("redacted");
        assertFalse(json.contains("fake-test-secret")); assertFalse(json.contains("\"secret\""));
        assertTrue(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(new PrivateData("visible", "fake-test-secret")).contains("fake-test-secret"));
    }
    @Test void applicationNamespacesAndPathsAreIsolated() throws Exception {
        SnapshotManager.save("same", "one");
        System.setProperty("sb.repl.applicationId", "other");
        assertThrows(IllegalStateException.class, () -> SnapshotManager.load("same"));
        for (String name : List.of("../outside", "..", "a/b", "a\\b", "\t"))
            assertThrows(IllegalArgumentException.class, () -> SnapshotManager.save(name, 42));
    }
    @Test void rejectsSymlinkDirectoriesBeforeCreatingChildren() throws Exception {
        Path other = Files.createDirectory(home.resolve("other"));
        Files.createSymbolicLink(home.resolve(".java-repl-snapshots"), other);
        assertThrows(IllegalStateException.class, () -> SnapshotManager.save("blocked", 42));
        try (var files = Files.list(other)) { assertEquals(0, files.count()); }
    }
    @Test void liveInfrastructureCanBePinnedButNotFrozen() {
        Thread thread = new Thread();
        SnapshotManager.pin("thread", thread); assertSame(thread, SnapshotManager.load("thread"));
        assertThrows(IllegalArgumentException.class, () -> SnapshotManager.save("thread-data", thread));
    }
}
