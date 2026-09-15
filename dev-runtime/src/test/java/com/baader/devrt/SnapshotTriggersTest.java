package com.baader.devrt;

import com.baader.sbrepl.bridge.SnapshotHelper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotTriggersTest {
    @TempDir Path home;
    private String oldHome;
    @BeforeEach void setup() { oldHome=System.getProperty("user.home"); System.setProperty("user.home",home.toString()); SpringContextHolder.set(null); SnapshotManager.clearLive(); }
    @AfterEach void cleanup() { SnapshotManager.clearLive(); SpringContextHolder.set(null); System.setProperty("user.home",oldHome); }
    private void arm(String name,String filter) { SnapshotTriggers.arm("owner","cv-input",name,filter,"",300000); }
    @Test void independentRulesHaveCountsSamplingAndPrivateOwnership() {
        String first = SnapshotTriggers.arm("one","orders","order-${sequence}","42","",300000,3,2).get("rule-id").toString();
        String second = SnapshotTriggers.arm("two","orders","other","42","",300000,1,1).get("rule-id").toString();
        AtomicInteger projections = new AtomicInteger();
        assertTrue(SnapshotHelper.captureLazy("orders","42",projections::incrementAndGet));
        assertEquals(1, projections.get()); // Shared call, one projection, two saved snapshots.
        assertEquals(1, (Object)SnapshotManager.load("order-1")); assertEquals(1, (Object)SnapshotManager.load("other"));
        assertFalse(SnapshotHelper.captureLazy("orders","42",projections::incrementAndGet));
        assertTrue(SnapshotHelper.captureLazy("orders","42",projections::incrementAndGet));
        assertFalse(SnapshotHelper.captureLazy("orders","42",projections::incrementAndGet));
        assertTrue(SnapshotHelper.captureLazy("orders","42",projections::incrementAndGet));
        assertEquals("SAVED", SnapshotTriggers.status("one",first).get("phase"));
        assertEquals(3, (Object)SnapshotManager.load("order-3"));
        assertEquals("SAVED", SnapshotTriggers.status("two",second).get("phase"));
        assertThrows(IllegalStateException.class, () -> SnapshotTriggers.disarm("one",second));
        assertFalse(SnapshotTriggers.list("one").get("value").toString().contains(second));
    }
    @Test void closingOneSessionLeavesOtherRulesArmedAndCapacityIsBounded() {
        for(int i=0;i<16;i++) SnapshotTriggers.arm("owner-"+i,"point-"+i,"value-"+i,"","",300000,1,1);
        assertThrows(IllegalStateException.class, () -> SnapshotTriggers.arm("extra","extra","extra","","",300000,1,1));
        SnapshotTriggers.release("owner-0");
        assertFalse(SnapshotHelper.capture("point-0",0));
        assertTrue(SnapshotHelper.capture("point-1",1));
        SnapshotTriggers.arm("extra","extra","extra","","",300000,1,1);
    }
    @Test void disabledAndNonMatchingCallsDoNotBuildTheProjection() {
        AtomicInteger calls=new AtomicInteger();
        assertFalse(SnapshotHelper.captureLazy("cv-input","42",()->calls.incrementAndGet()));
        arm("captured","42");
        assertFalse(SnapshotHelper.captureLazy("other","42",()->calls.incrementAndGet()));
        assertFalse(SnapshotHelper.captureLazy("cv-input","other",()->calls.incrementAndGet()));
        assertEquals(0,calls.get()); assertEquals("ARMED",SnapshotTriggers.status("owner").get("phase"));
        assertTrue(SnapshotHelper.captureLazy("cv-input","42",()->calls.incrementAndGet()));
        assertFalse(SnapshotHelper.captureLazy("cv-input","42",()->calls.incrementAndGet()));
        assertEquals(1,calls.get()); assertEquals(Integer.valueOf(1),(Object)SnapshotManager.load("captured"));
        assertEquals("SAVED",SnapshotTriggers.status("owner").get("phase"));
        assertTrue(((Number)SnapshotTriggers.status("owner").get("bytes")).longValue()>0);
    }
    @Test void concurrentApplicationCallsClaimExactlyOneCapture() throws Exception {
        arm("concurrent",""); AtomicInteger projections=new AtomicInteger();
        var pool=Executors.newFixedThreadPool(12); var start=new CountDownLatch(1);
        try {
            List<Future<Boolean>> results=new ArrayList<>();
            for(int i=0;i<40;i++) results.add(pool.submit(()->{ start.await(); return SnapshotHelper.captureLazy("cv-input","case",()->Map.of("count",projections.incrementAndGet())); }));
            start.countDown(); int captured=0;
            for(var result:results) if(result.get(15,TimeUnit.SECONDS)) captured++;
            assertEquals(1,captured); assertEquals(1,projections.get());
            assertEquals(Map.of("count",1),SnapshotManager.load("concurrent"));
        } finally { pool.shutdownNow(); }
    }
    @Test void failedCaptureIsNotRetriedAndPreservesPreviousSnapshot() {
        SnapshotManager.save("existing",42); arm("existing","");
        AtomicInteger calls=new AtomicInteger();
        assertFalse(SnapshotHelper.captureLazy("cv-input","",()->{ calls.incrementAndGet(); throw new IllegalArgumentException("projection failed"); }));
        assertFalse(SnapshotHelper.captureLazy("cv-input","",()->calls.incrementAndGet()));
        assertEquals(1,calls.get()); assertEquals("FAILED",SnapshotTriggers.status("owner").get("phase"));
        assertTrue(SnapshotTriggers.status("owner").get("detail").toString().contains("projection failed"));
        assertEquals(Integer.valueOf(42),(Object)SnapshotManager.load("existing"));
    }
    @Test void expiryDisarmSessionCloseAndContextReplacementRemovePendingWork() throws Exception {
        SnapshotTriggers.arm("owner","cv-input","expired","","",1); Thread.sleep(10);
        assertEquals("EXPIRED",SnapshotTriggers.status("owner").get("phase")); assertFalse(SnapshotHelper.capture("cv-input",42));
        arm("cancelled",""); SnapshotTriggers.disarm("owner"); assertFalse(SnapshotHelper.capture("cv-input",42));
        arm("closed",""); SnapshotManager.release("owner"); assertFalse(SnapshotHelper.capture("cv-input",42));
        arm("context",""); SpringContextHolder.set(new Object()); assertFalse(SnapshotHelper.capture("cv-input",42));
        assertEquals("IDLE",SnapshotTriggers.status("owner").get("phase"));
    }
    @Test void ownershipIsEnforcedAndInvalidArmLeavesNoCapture() {
        assertThrows(IllegalArgumentException.class,()->SnapshotTriggers.arm("owner","cv-input","../escape","","",300000));
        assertEquals("IDLE",SnapshotTriggers.status("owner").get("phase"));
        arm("owned","");
        assertEquals("OTHER_SESSION",SnapshotTriggers.status("foreign").get("phase"));
        assertThrows(IllegalStateException.class,()->SnapshotTriggers.disarm("foreign"));
        assertThrows(IllegalStateException.class,()->SnapshotTriggers.arm("foreign","cv-input","second","","",300000));
    }
    @Test void recursiveCaptureAndContextCloseDoNotBlockOrCaptureTwice() throws Exception {
        var context=new org.springframework.context.support.GenericApplicationContext(); context.refresh(); SpringContextHolder.set(context);
        arm("slow",""); var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var saving=CompletableFuture.supplyAsync(()->SnapshotHelper.captureLazy("cv-input","",()->{
            assertFalse(SnapshotHelper.capture("cv-input","recursive")); entered.countDown();
            try { release.await(10,TimeUnit.SECONDS); } catch(InterruptedException e) { throw new RuntimeException(e); }
            return "finished";
        }));
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertEquals("CAPTURING",SnapshotTriggers.status("owner").get("phase"));
            assertThrows(IllegalStateException.class,()->SnapshotTriggers.disarm("owner"));
            CompletableFuture.runAsync(()->SpringContextHolder.clear(context)).get(2,TimeUnit.SECONDS);
            assertEquals("IDLE",SnapshotTriggers.status("owner").get("phase"));
        } finally { release.countDown(); assertTrue(saving.get(5,TimeUnit.SECONDS)); context.close(); }
        assertEquals("finished",SnapshotManager.load("slow")); // An already claimed save may finish after disconnect/close.
    }
    @Test void inFlightCaptureRetainsRedactionWhenTheSessionIsReleased() throws Exception {
        try(var scope=SnapshotManager.scope("owner")) {
            SnapshotManager.addMixIn(SnapshotManagerTest.PrivateData.class,SnapshotManagerTest.HideSecret.class);
        }
        arm("redacted",""); var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var saving=CompletableFuture.supplyAsync(()->SnapshotHelper.captureLazy("cv-input","",()->{
            entered.countDown();try { release.await(10,TimeUnit.SECONDS); } catch(InterruptedException e) { throw new RuntimeException(e); }
            return new SnapshotManagerTest.PrivateData("visible","fake-test-secret");
        }));
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS));SnapshotManager.release("owner");
        } finally { release.countDown();assertTrue(saving.get(5,TimeUnit.SECONDS)); }
        assertFalse(SnapshotManager.json("redacted").contains("fake-test-secret"));
        assertTrue(SnapshotManager.json("redacted").contains("visible"));
    }
    @Test void applicationErrorsPropagateButDoNotLeaveTheSlotCapturing() {
        arm("aborted","");
        assertThrows(AssertionError.class,()->SnapshotHelper.captureLazy("cv-input","",()->{ throw new AssertionError("fixture"); }));
        assertEquals("FAILED",SnapshotTriggers.status("owner").get("phase"));
        assertFalse(SnapshotHelper.capture("cv-input",42));
    }
    @Test void facadeWorksWithoutAgentAndDoesNotEvaluateSupplier() throws Exception {
        String path=SnapshotHelper.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath()
                +java.io.File.pathSeparator+CaptureWithoutAgentProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",path,CaptureWithoutAgentProbe.class.getName())
                .redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(20,TimeUnit.SECONDS));
            assertEquals(0,process.exitValue(),new String(process.getInputStream().readAllBytes()));
        } finally { process.destroyForcibly(); }
    }
}
