package com.baader.devrt;

import hu.baader.repl.protocol.*;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionHistoryTest {
    final String owner=UUID.randomUUID().toString();
    ExecutionHistory history;
    @BeforeEach void setup() { history=ExecutionHistory.begin(owner); }
    @AfterEach void cleanup() { ExecutionHistory.release(owner); }
    ExecutionHistory.Ticket enter(long parent,Object argument) { return history.enter(parent,"example.Service","call","(Ljava/lang/Object;)Ljava/lang/Object;","input",new Object[]{argument}); }
    @Test void freezesInputBeforeMutationAndOutputAtCompletion() {
        var value=new ArrayList<>(List.of("before")); var ticket=enter(0,value);
        value.set(0,"after"); history.exit(ticket,42,value,null,17); value.clear();
        var call=history.call(history.id,ticket.id());
        assertEquals("before",ValueTree.decode(call.input()).children().get(0).children().get(0).text());
        assertEquals("after",ValueTree.decode(call.output()).children().get(0).text());
        assertEquals("[\"after\"]",call.summary());
        assertEquals("SUCCESS",call.status()); assertEquals(17,call.event()); assertEquals(42,call.durationNanos());
        assertEquals(call,RecordedCall.decode(call.encode()));
        assertEquals("",RecordedCall.decode(history.list().get("value").toString()).input());
    }
    @Test void identifiesNestedRecursiveCallsAndSeparateRoots() {
        var root=enter(0,1);var child=enter(root.id(),2);var grandchild=enter(child.id(),3);var another=enter(0,4);
        assertEquals(root.id(),history.call(history.id,grandchild.id()).root());
        assertEquals(child.id(),history.call(history.id,grandchild.id()).parent());
        assertEquals(another.id(),history.call(history.id,another.id()).root());
        assertEquals(0,history.call(history.id,another.id()).parent());
    }
    @Test void stopKeepsHistoryAndLetsInflightCallsFinish() {
        var ticket=enter(0,"request");history.stop();assertNull(enter(0,"later"));
        history.exit(ticket,17,null,new IllegalArgumentException("failure"),-1);
        var call=history.call(history.id,ticket.id());assertEquals("ERROR",call.status());
        assertTrue(ValueTree.decode(call.exception()).children().stream().anyMatch(v->v.text().equals("failure")));
        assertFalse(history.recording());
    }
    @Test void voidReturnAndThrownExceptionDoNotPretendToReturnNull() {
        var ticket=history.enter(0,"example.Service","run","()V","",new Object[0]);history.exit(ticket,1,null,null,-1);
        assertEquals("void",history.call(history.id,ticket.id()).summary());
        var failed=enter(0,1);history.exit(failed,1,null,new IllegalStateException("boom"),-1);
        assertEquals("",history.call(history.id,failed.id()).output());assertFalse(history.call(history.id,failed.id()).exception().isEmpty());
    }
    @Test void boundedHistoryDoesNotEvictParentsOrLoseFinishingCalls() {
        var root=enter(0,0);
        for(int i=1;i<RecordedCall.MAX_CALLS;i++) enter(root.id(),i);
        assertNull(enter(root.id(),201)); assertFalse(history.recording());
        history.exit(root,2,42,null,-1);
        assertEquals("SUCCESS",history.call(history.id,root.id()).status());
        assertEquals(200,history.list().get("value").toString().lines().count()); assertEquals(1L,history.list().get("dropped"));
    }
    @Test void payloadMemoryLimitIncludesConcurrentCompletions() {
        String large="x".repeat(4096); Object value=Collections.nCopies(50,large).toArray();
        for(int i=0;i<200;i++) { var t=enter(0,value);if(t==null)break;history.exit(t,1,value,null,-1); }
        assertFalse(history.recording()); assertTrue((int)history.list().get("bytes")<=ExecutionHistory.MAX_BYTES);
    }
    @Test void customExceptionMessagesAndGettersAreNotExecuted() {
        var bomb=new RuntimeException() { @Override public String getMessage(){throw new AssertionError("application code executed");} };
        var t=enter(0,new Object(){public Object getSecret(){throw new AssertionError();}});
        assertDoesNotThrow(()->history.exit(t,1,null,bomb,-1));
        assertTrue(history.call(history.id,t.id()).summary().contains("not evaluated"));
    }
    @Test void historyCanBeReadAndStoppedWhileApplicationCollectionIsLocked() throws Exception {
        Vector<String> value=new Vector<>(List.of("input"));
        CompletableFuture<ExecutionHistory.Ticket> capture;
        synchronized(value) {
            capture=CompletableFuture.supplyAsync(()->enter(0,value));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(history.list().get("value").toString().isEmpty() && System.nanoTime()<deadline) Thread.yield();
            CompletableFuture.runAsync(()->{assertFalse(history.list().get("value").toString().isEmpty());history.stop();}).get(2,TimeUnit.SECONDS);
        }
        history.exit(capture.get(2,TimeUnit.SECONDS),1,42,null,-1);
    }
    @Test void replacementHistoryRejectsOldIdentityAndKeepsSessionsIsolated() {
        var t=enter(0,1);var old=history;history=ExecutionHistory.begin(owner);
        assertThrows(IllegalArgumentException.class,()->history.call(old.id,t.id()));
        assertNull(old.enter(0,"example.A","m","()V","",new Object[0]));
        assertTrue(history.list().get("value").toString().isEmpty());
    }
}
