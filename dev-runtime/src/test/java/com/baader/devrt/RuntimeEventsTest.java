package com.baader.devrt;

import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeEventsTest {
    final String owner=UUID.randomUUID().toString(), other=UUID.randomUUID().toString();
    @BeforeEach void setup(){RuntimeEvents.register(owner);RuntimeEvents.register(other);}
    @AfterEach void cleanup(){RuntimeEvents.release(owner);RuntimeEvents.release(other);}
    private long last(String session){return Long.parseLong(RuntimeEvents.list(session).get("value").toString().lines().reduce((a,b)->b).orElseThrow().split("\t")[0]);}
    @Test void tapIsOptInFilteredAndOwnedByTheReceivingSession() {
        Object value=new Object(); assertFalse(RuntimeEvents.tap("input",value));
        RuntimeEvents.subscribe(owner,true,"input"); assertFalse(RuntimeEvents.tap("output",value));
        assertTrue(RuntimeEvents.tap("input",value)); long id=last(owner); assertSame(value,RuntimeEvents.value(owner,id));
        assertThrows(IllegalArgumentException.class,()->RuntimeEvents.value(other,id));
        RuntimeEvents.subscribe(owner,false,""); assertFalse(RuntimeEvents.tap("input",value));
        assertSame(value,RuntimeEvents.value(owner,id));
        RuntimeEvents.clear(owner); assertThrows(IllegalArgumentException.class,()->RuntimeEvents.value(owner,id));
    }
    @Test void eventsHaveCountAndTimeLimitsEvenWhenNothingNewArrives() {
        RuntimeEvents.subscribe(owner,true,""); RuntimeEvents.tap("first",1); long first=last(owner);
        for(int i=0;i<200;i++) RuntimeEvents.tap("request",i);
        assertEquals(128,RuntimeEvents.list(owner).get("value").toString().lines().count());
        assertEquals(73L,RuntimeEvents.list(owner).get("dropped"));
        assertThrows(IllegalArgumentException.class,()->RuntimeEvents.value(owner,first));
        long newest=last(owner); RuntimeEvents.expire(System.currentTimeMillis()+RuntimeEvents.TTL_MS+1);
        assertThrows(IllegalArgumentException.class,()->RuntimeEvents.value(owner,newest));
    }
    @Test void debuggerTicketsAreSingleUseAndCheckProcessAndSession() {
        String ticket=UUID.randomUUID().toString(); Object value=new Object(); long pid=ProcessHandle.current().pid();
        assertThrows(IllegalArgumentException.class,()->DebugBridge.capture(owner,ticket,pid+1,value));
        DebugBridge.capture(owner,ticket,pid,value);
        assertThrows(IllegalArgumentException.class,()->RuntimeEvents.claimDebug(other,ticket));
        assertSame(value,RuntimeEvents.claimDebug(owner,ticket));
        assertThrows(IllegalArgumentException.class,()->RuntimeEvents.claimDebug(owner,ticket));
        DebugBridge.capture(owner,ticket,pid,null); assertNull(RuntimeEvents.claimDebug(owner,ticket));
        RuntimeEvents.release(owner); assertThrows(IllegalStateException.class,()->DebugBridge.checkTarget(owner,pid));
    }
    @Test void debuggerCaptureDoesNotWaitForSuspendedEventReader() throws Exception {
        var field=RuntimeEvents.class.getDeclaredField("CHANNELS");field.setAccessible(true);
        Object channel=((Map<?,?>)field.get(null)).get(owner);
        CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1);
        var holder=CompletableFuture.runAsync(()->{synchronized(channel){locked.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}}});
        try {
            assertTrue(locked.await(2,TimeUnit.SECONDS));
            String ticket=UUID.randomUUID().toString();
            CompletableFuture.runAsync(()->DebugBridge.capture(owner,ticket,ProcessHandle.current().pid(),42)).get(2,TimeUnit.SECONDS);
            assertEquals(42,RuntimeEvents.claimDebug(owner,ticket));
        } finally {release.countDown();holder.get(2,TimeUnit.SECONDS);}
    }
    @Test void debuggerTicketsExpireAndAreBounded() {
        String first=UUID.randomUUID().toString(); DebugBridge.capture(owner,first,ProcessHandle.current().pid(),1);
        for(int i=1;i<RuntimeEvents.MAX_DEBUG_VALUES;i++) DebugBridge.capture(owner,UUID.randomUUID().toString(),ProcessHandle.current().pid(),i);
        assertThrows(IllegalStateException.class,()->DebugBridge.capture(owner,UUID.randomUUID().toString(),ProcessHandle.current().pid(),17));
        RuntimeEvents.expire(System.currentTimeMillis()+RuntimeEvents.TTL_MS+1);
        assertThrows(IllegalArgumentException.class,()->RuntimeEvents.claimDebug(owner,first));
        DebugBridge.capture(owner,UUID.randomUUID().toString(),ProcessHandle.current().pid(),18);
    }
}
