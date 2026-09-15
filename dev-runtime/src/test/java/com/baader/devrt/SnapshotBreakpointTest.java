package com.baader.devrt;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotBreakpointTest {
    @TempDir Path home; String oldHome, oldApp;
    @BeforeEach void setup() { oldHome=System.getProperty("user.home"); oldApp=System.getProperty("sb.repl.applicationId"); System.setProperty("user.home",home.toString()); System.setProperty("sb.repl.applicationId","snapshot-point-test"); SpringContextHolder.set(null); }
    @AfterEach void cleanup() { SnapshotManager.clearLive(); System.setProperty("user.home",oldHome); if(oldApp==null) System.clearProperty("sb.repl.applicationId"); else System.setProperty("sb.repl.applicationId",oldApp); }
    @Test void capturesTheValueAtHitAndStopsAfterConfiguredCount() {
        String id=UUID.randomUUID().toString(); List<String> input=new ArrayList<>(List.of("before"));
        assertTrue(SnapshotBreakpoint.ready(id,1));
        assertTrue(SnapshotBreakpoint.capture(id,"request","",1,input).contains("Saved DATA"));
        input.add("after"); assertEquals(List.of("before"),SnapshotManager.load("request"));
        assertFalse(SnapshotBreakpoint.ready(id,1)); SnapshotBreakpoint.capture(id,"request","",1,input);
        assertEquals(1,SnapshotManager.versions("request").size());
        assertTrue(SnapshotManager.provenance("request","").toString().contains("snapshotPointId"));
    }
    @Test void concurrentHitsCreateExactlyTheRequestedNumberOfVersions() throws Exception {
        String id=UUID.randomUUID().toString(); var pool=Executors.newFixedThreadPool(4);
        try {
            var work=new ArrayList<Future<?>>(); for(int i=0;i<12;i++) { final int value=i; work.add(pool.submit(()->SnapshotBreakpoint.capture(id,"hits","",3,value))); }
            for(var task:work)task.get(20,TimeUnit.SECONDS);
            assertEquals(3,SnapshotManager.versions("hits").size()); assertFalse(SnapshotBreakpoint.ready(id,3));
        } finally { pool.shutdownNow(); }
    }
    @Test void failedCaptureIsVisibleAndDoesNotRepeatOrReplaceAnExistingValue() {
        SnapshotManager.save("input",7); String id=UUID.randomUUID().toString();
        String result=SnapshotBreakpoint.capture(id,"input","",1,new SnapshotManagerTest.Broken());
        assertTrue(result.contains("Capture failed"),result); assertFalse(SnapshotBreakpoint.ready(id,1));
        assertEquals(7,(Object)SnapshotManager.load("input"));
        assertTrue(SnapshotBreakpoint.ready(UUID.randomUUID().toString(),1)); // Rearming creates a fresh identity.
    }
}
