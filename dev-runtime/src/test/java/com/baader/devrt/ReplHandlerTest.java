package com.baader.devrt;

import hu.baader.repl.protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.beans.factory.support.RootBeanDefinition;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ReplHandlerTest {
    @TempDir Path home;
    String previousHome;
    ReplHandler handler;
    String session;
    @BeforeEach void setup() {
        previousHome=System.getProperty("user.home");System.setProperty("user.home",home.toString());
        SpringContextHolder.set(null); SnapshotManager.clearLive();
        handler=new ReplHandler(); session=handler.createSession();
    }
    @AfterEach void cleanup() {
        handler.close();SpringContextHolder.set(null);SnapshotManager.clearLive();System.setProperty("user.home",previousHome);
    }
    Map<String,Object> request(String op, Map<String,String> extra) {
        var payload=new HashMap<>(extra);payload.put("session",session);
        return handler.handle(op,payload);
    }
    Map<String,Object> ok(String op, Map<String,String> extra) {
        var response=request(op,extra);assertFalse(ReplProtocol.error(response),response.toString());assertTrue(ReplProtocol.done(response));return response;
    }
    @Test void cloneAndSessionOwnershipAreReal() {
        String other=(String)handler.handle("clone",Map.of()).get("new-session");
        ok("eval",Map.of("code","int x = 42;"));
        assertTrue(ReplProtocol.error(handler.handle("eval",Map.of("session",other,"code","x"))));
        try(var foreign=new ReplHandler()) { assertTrue(ReplProtocol.error(foreign.handle("eval",Map.of("session",session,"code","1")))); }
        ok("close",Map.of());assertTrue(ReplProtocol.error(request("eval",Map.of("code","1"))));
    }
    @Test void resetImportsAndUnknownOperationsAlwaysTerminate() {
        ok("imports/add",Map.of("imports","import java.math.BigDecimal;"));
        assertTrue(ok("imports/get",Map.of()).get("imports").toString().contains("BigDecimal"));
        ok("eval",Map.of("code","int resetMe = 42;"));ok("session-reset",Map.of());
        assertTrue(ReplProtocol.error(request("eval",Map.of("code","resetMe"))));
        var unknown=request("made-up",Map.of());assertTrue(ReplProtocol.error(unknown));assertTrue(ReplProtocol.done(unknown));
    }
    @Test void bindingWaitsForReadyContextAndDoesNotResetState() {
        assertEquals("false",ok("bind-spring",Map.of()).get("value"));
        ok("eval",Map.of("code","int beforeBind = 42;"));
        try(var context=new GenericApplicationContext()) {
            context.refresh();SpringContextHolder.set(context);
            assertEquals("true",ok("bind-spring",Map.of()).get("value"));
            assertEquals("42",ok("eval",Map.of("code","beforeBind")).get("value"));
            assertEquals("true",ok("eval",Map.of("code","((org.springframework.context.ConfigurableApplicationContext)ctx).isActive()")).get("value"));
            SpringContextHolder.clear(context);
            assertTrue(ReplProtocol.error(request("eval",Map.of("code","beforeBind"))));
            ok("session/reset",Map.of());
            assertTrue(ReplProtocol.error(request("eval",Map.of("code","beforeBind"))));
        }
    }
    @Test void restartExpiresOldHandlesAndResetBindsTheNewApplication() throws Exception {
        try(var oldContext=new GenericApplicationContext();var newContext=new GenericApplicationContext()) {
            oldContext.refresh();SpringContextHolder.set(oldContext);ok("bind-spring",Map.of());
            var evaluated=ok("eval",Map.of("code","int stale=42; stale"));
            ok("snapshot/pin",Map.of("name","old","handle",evaluated.get("handle").toString()));
            newContext.refresh();SpringContextHolder.set(newContext);
            var barrier=new CompletableFuture<Void>(); handler.submit(session,()->barrier.complete(null));barrier.get(5,TimeUnit.SECONDS);
            assertTrue(ReplProtocol.error(request("inspect",Map.of("handle",evaluated.get("handle").toString()))));
            assertTrue(ReplProtocol.error(request("eval",Map.of("code","stale"))));
            assertEquals(true,ok("session/reset",Map.of()).get("context-ready"));
            assertEquals("true",ok("eval",Map.of("code","ctx == com.baader.devrt.SpringContextHolder.get()")).get("value"));
            assertTrue(ReplProtocol.error(request("snapshot/load",Map.of("name","old"))));
        }
    }
    @Test void exportedDataCanBeImportedWithoutTrustingStoredTypes() {
        ok("eval",Map.of("code","int value=42;"));ok("snapshot/save",Map.of("name","export","expr","value"));
        String json=ok("snapshot/export",Map.of("name","export")).get("value").toString();
        ok("snapshot/import",Map.of("name","copy","json",json));
        ok("snapshot/load",Map.of("name","copy","var","copied"));
        assertEquals("43",ok("eval",Map.of("code","copied+1")).get("value"));
    }
    @Test void largeFileOperationsReturnSmallResponsesAndKeepSessionUsable() throws Exception {
        String value = "x".repeat(3 * 1024 * 1024);
        SnapshotManager.save("large", value);
        var inline = request("snapshot/export", Map.of("name", "large"));
        assertTrue(ReplProtocol.error(inline)); assertTrue(ReplProtocol.done(inline));
        Path file = home.resolve("large.json");
        var exported = ok("snapshot/export-file", Map.of("name", "large", "path", file.toString()));
        var wire = new java.io.ByteArrayOutputStream(); Bencode.write(exported, wire);
        assertTrue(wire.size() < 1024);
        ok("snapshot/import-file", Map.of("name", "copy", "path", file.toString()));
        ok("snapshot/load", Map.of("name", "copy", "var", "largeCopy"));
        assertEquals(Integer.toString(value.length()), ok("eval", Map.of("code", "largeCopy.length()")).get("value"));
        assertTrue(ok("eval", Map.of("code", "largeCopy")).get("value").toString().length() < 66000);
        assertEquals("42", ok("eval", Map.of("code", "41+1")).get("value"));
    }
    @Test void beanListingDoesNotInitializeLazyOrPrototypeBeans() {
        AtomicInteger created=new AtomicInteger();
        try(var context=new GenericApplicationContext()) {
            var lazy=new RootBeanDefinition(String.class,()->{ created.incrementAndGet();return "lazy"; });lazy.setLazyInit(true);
            var prototype=new RootBeanDefinition(String.class,()->{ created.incrementAndGet();return "prototype"; });prototype.setScope("prototype");
            context.registerBeanDefinition("lazy",lazy);context.registerBeanDefinition("prototype",prototype);context.refresh();SpringContextHolder.set(context);
            assertTrue(ok("list-beans",Map.of()).get("value").toString().contains("prototype\tjava.lang.String"));
            assertEquals(0,created.get());
        }
    }
    @Test void savingAResultDoesNotReExecuteItsExpressionAndLoadBindsVariable() {
        var result=ok("eval",Map.of("code","int count=0; ++count"));
        ok("snapshot/save",Map.of("name","once","handle",result.get("handle").toString()));
        assertEquals("1",ok("eval",Map.of("code","count")).get("value"));
        ok("snapshot/load",Map.of("name","once","var","restored"));
        assertEquals("2",ok("eval",Map.of("code","restored + count")).get("value"));
        assertTrue(ok("vars/list",Map.of()).get("value").toString().contains("restored"));
        assertTrue(ReplProtocol.error(request("snapshot/save",Map.of("name","bad","expr","++count"))));
        assertEquals("1",ok("eval",Map.of("code","count")).get("value"));
    }
    @Test void sameSessionQueueIsOrderedAndInterruptIsIndependent() throws Exception {
        var latch=new CountDownLatch(1);
        // Bind a test-only synchronization latch via an in-process reference.
        ReplBindings.put("testLatch",latch);
        ok("eval",Map.of("code","var latch=(java.util.concurrent.CountDownLatch)com.baader.devrt.ReplBindings.get(\"testLatch\");"));
        var result=new CompletableFuture<Map<String,Object>>();
        handler.submit(session,()->result.complete(request("eval",Map.of("code","latch.countDown(); Thread.sleep(60000);"))));
        assertTrue(latch.await(10,TimeUnit.SECONDS));
        ok("interrupt",Map.of());
        assertTrue(ReplProtocol.error(result.get(10,TimeUnit.SECONDS)));
        var next=new CompletableFuture<Map<String,Object>>();
        handler.submit(session,()->next.complete(request("eval",Map.of("code","41+1"))));
        assertEquals("42",next.get(10,TimeUnit.SECONDS).get("value"));
    }
    @Test void applicationCaptureCanBeLoadedCompletedAndComparedThroughProtocol() {
        ok("capture/arm",Map.of("point","cv","name","before","case","request-42"));
        assertFalse(com.baader.sbrepl.bridge.SnapshotHelper.capture("cv","wrong",Map.of("count",99)));
        assertTrue(com.baader.sbrepl.bridge.SnapshotHelper.capture("cv","request-42",Map.of("count",1)));
        assertEquals("SAVED",ok("capture/status",Map.of()).get("phase"));
        assertFalse(com.baader.sbrepl.bridge.SnapshotHelper.capture("cv","request-42",Map.of("count",100)));
        ok("snapshot/load",Map.of("name","before","var","captured"));
        assertTrue(ok("complete",Map.of("code","captured.ge","cursor","11")).get("completions").toString().contains("get("));
        ok("eval",Map.of("code","captured.put(\"count\",2);"));
        ok("snapshot/save",Map.of("name","after","expr","captured"));
        assertTrue(ok("snapshot/diff",Map.of("before","before","after","after")).get("value").toString().contains("/count\tCHANGED\t1\t2"));
        ok("capture/arm",Map.of("point","cv","name","cancel")); ok("session/reset",Map.of());
        assertFalse(com.baader.sbrepl.bridge.SnapshotHelper.capture("cv",3));
        ok("capture/arm",Map.of("point","cv","name","closed")); handler.closeSession(session);
        assertFalse(com.baader.sbrepl.bridge.SnapshotHelper.capture("cv",4));
    }
    @Test void restoredGenericDtoListsKeepTheirElementTypeForCompletion() {
        SnapshotManager.save("typed",List.of(new SnapshotManagerTest.Item("test",java.time.LocalDate.of(2026,9,13))),
                "java.util.List<"+SnapshotManagerTest.Item.class.getName()+">");
        var loaded=ok("snapshot/load",Map.of("name","typed","var","items"));
        assertTrue(loaded.get("type").toString().contains("java.util.List<com.baader.devrt.SnapshotManagerTest.Item>"));
        String source="items.get(0).da";
        assertTrue(ok("complete",Map.of("code",source,"cursor",Integer.toString(source.length()))).get("completions").toString().contains("date()"));
        assertEquals("2026",ok("eval",Map.of("code","items.get(0).date().getYear()")).get("value"));
    }
    @Test void captureStatusAndDisarmBypassARunningEvaluation() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        ReplBindings.put("captureEntered",entered);ReplBindings.put("captureRelease",release);
        ok("eval",Map.of("code","var entered=(java.util.concurrent.CountDownLatch)com.baader.devrt.ReplBindings.get(\"captureEntered\"); var release=(java.util.concurrent.CountDownLatch)com.baader.devrt.ReplBindings.get(\"captureRelease\");"));
        ok("capture/arm",Map.of("point","blocked","name","snapshot"));
        var done=new CompletableFuture<Map<String,Object>>();
        handler.submit(session,()->done.complete(request("eval",Map.of("code","entered.countDown(); release.await();"))));
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            var status=CompletableFuture.supplyAsync(()->ok("capture/status",Map.of())).get(2,TimeUnit.SECONDS);
            assertEquals("ARMED",status.get("phase"));
            assertEquals("CANCELLED",CompletableFuture.supplyAsync(()->ok("capture/disarm",Map.of())).get(2,TimeUnit.SECONDS).get("phase"));
        } finally { release.countDown();done.get(5,TimeUnit.SECONDS); }
    }
    @Test void everyAdvertisedOperationIsHandled() {
        Map<?,?> ops=(Map<?,?>)handler.handle("describe",Map.of()).get("ops");
        assertEquals(ReplProtocol.OPS,ops.keySet());
        for(String op:ReplProtocol.OPS) {
            if(Set.of("clone","close").contains(op))continue;
            var response=request(op,Map.of("name","nonexistent","code","1","cursor","1","imports","java.util.List"));
            assertTrue(ReplProtocol.done(response),op);
            assertFalse(response.getOrDefault("err","").toString().startsWith("Unknown operation"),op);
        }
    }
}
