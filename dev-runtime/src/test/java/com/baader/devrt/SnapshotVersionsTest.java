package com.baader.devrt;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotVersionsTest {
    @TempDir Path home; String oldHome,oldApp;
    @BeforeEach void setup(){oldHome=System.getProperty("user.home");oldApp=System.getProperty("sb.repl.applicationId");System.setProperty("user.home",home.toString());System.setProperty("sb.repl.applicationId","versions-test");SpringContextHolder.set(null);SnapshotManager.clearLive();}
    @AfterEach void cleanup(){SnapshotManager.clearLive();System.setProperty("user.home",oldHome);if(oldApp==null)System.clearProperty("sb.repl.applicationId");else System.setProperty("sb.repl.applicationId",oldApp);}
    private String current(String name){return (String)SnapshotManager.provenance(name,"").get("version");}
    @Test void restoreCreatesNewRevisionAndNeverDestroysCapturedBytes() throws Exception {
        SnapshotManager.save("value",Map.of("amount",1));String first=current("value");byte[] bytes=Files.readAllBytes(SnapshotVersions.version(SnapshotManager.path("value"),first));
        SnapshotManager.save("value",Map.of("amount",2));String second=current("value");
        assertEquals(Map.of("amount",1),SnapshotManager.loadVersion("value",first,""));
        assertEquals(Map.of("amount",2),SnapshotManager.load("value"));
        SnapshotManager.restoreVersion("value",first);String restored=current("value");
        assertNotEquals(first,restored);assertNotEquals(second,restored);assertEquals(first,SnapshotManager.provenance("value",restored).get("restoredFrom"));
        assertEquals(Map.of("amount",1),SnapshotManager.load("value"));assertEquals(3,SnapshotManager.versions("value").size());
        assertArrayEquals(bytes,Files.readAllBytes(SnapshotVersions.version(SnapshotManager.path("value"),first)));
        assertTrue(SnapshotManager.provenance("value",first).containsKey("executionContext"));
    }
    @Test void legacyV1IsArchivedOnFirstOverwrite() throws Exception {
        Path current=SnapshotManager.path("legacy");
        Files.writeString(current,"{\"schemaVersion\":1,\"name\":\"legacy\",\"applicationId\":\"versions-test\",\"kind\":\"DATA\",\"capturedAt\":\"2025-01-01T00:00:00Z\",\"declaredType\":\"java.lang.Integer\",\"payload\":7}");
        String first=current("legacy");assertEquals(1,SnapshotManager.versions("legacy").size());
        SnapshotManager.save("legacy",8);assertEquals(2,SnapshotManager.versions("legacy").size());assertEquals(7,SnapshotManager.loadVersion("legacy",first,""));
    }
    @Test void corruptedHistoryIsRejectedBeforeCurrentValueChanges() throws Exception {
        SnapshotManager.save("value",1);String version=current("value");SnapshotManager.save("value",2);
        Files.writeString(SnapshotVersions.version(SnapshotManager.path("value"),version),"{}");
        assertThrows(IllegalStateException.class,()->SnapshotManager.restoreVersion("value",version));assertEquals(2,(Object)SnapshotManager.load("value"));
        assertThrows(IllegalStateException.class,()->SnapshotManager.loadVersion("value",version,""));
        assertThrows(IllegalStateException.class,()->SnapshotManager.provenance("value","../../bad"));
    }
    @Test void concurrentWritersRetainEverySuccessfulValue() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> futures=new ArrayList<>();for(int i=0;i<8;i++){int value=i;futures.add(pool.submit(()->SnapshotManager.save("shared",value)));}
            for(Future<?> future:futures)future.get(15,TimeUnit.SECONDS);
            Set<Object> values=new HashSet<>();for(var version:SnapshotManager.versions("shared"))values.add(SnapshotManager.loadVersion("shared",version.get("version").toString(),""));
            assertEquals(Set.of(0,1,2,3,4,5,6,7),values);
        } finally {pool.shutdownNow();}
    }
    @Test void failedSerializationAddsNoRevisionAndExplicitDeletePurgesHistory() throws Exception {
        SnapshotManager.save("v",1);String version=current("v");
        assertThrows(IllegalStateException.class,()->SnapshotManager.save("v",new SnapshotManagerTest.Broken()));
        assertEquals(version,current("v"));assertEquals(1,SnapshotManager.versions("v").size());
        SnapshotManager.delete("v");assertTrue(SnapshotManager.versions("v").isEmpty());assertFalse(Files.exists(SnapshotManager.path("v")));
    }
    @Test void aSymlinkCannotRedirectHistoryWrites() throws Exception {
        Path repository=SnapshotManager.directory(),outside=home.resolve("outside");Files.createDirectories(outside);Files.createSymbolicLink(repository.resolve("versions"),outside);
        assertThrows(IllegalStateException.class,()->SnapshotManager.save("v",1));
        try(var files=Files.list(outside)){assertTrue(files.findAny().isEmpty());}
    }
}
