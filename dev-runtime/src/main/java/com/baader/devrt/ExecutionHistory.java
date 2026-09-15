package com.baader.devrt;

import hu.baader.repl.protocol.RecordedCall;
import hu.baader.repl.protocol.ValueTree;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded, immutable-at-entry/exit display captures. Application objects stay only in RuntimeEvents' existing live cache. */
final class ExecutionHistory {
    static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final Map<String, ExecutionHistory> HISTORIES = new ConcurrentHashMap<>();
    final String owner, id = UUID.randomUUID().toString();
    final long epoch = SpringContextHolder.epoch();
    private final LinkedHashMap<Long, RecordedCall> calls = new LinkedHashMap<>();
    private boolean active = true;
    private long revision, nextId, dropped;
    private int bytes;
    record Ticket(ExecutionHistory history, long id) {}
    private ExecutionHistory(String owner) { this.owner = owner; }
    static ExecutionHistory begin(String owner) {
        ExecutionHistory history = new ExecutionHistory(owner);
        ExecutionHistory old = HISTORIES.put(owner, history); if (old != null) old.stop();
        return history;
    }
    static ExecutionHistory get(String owner) { return HISTORIES.get(owner); }
    static void release(String owner) { ExecutionHistory old = HISTORIES.remove(owner); if (old != null) old.stop(); }
    synchronized void stop() { active = false; }
    synchronized boolean recording() { return active && epoch == SpringContextHolder.epoch(); }
    Ticket enter(long parent, String type, String method, String descriptor, String names, Object[] args) {
        long callId;
        synchronized (this) {
            if (!recording()) return null;
            if (calls.size() >= RecordedCall.MAX_CALLS || bytes >= MAX_BYTES) { active = false; dropped++; return null; }
            callId = ++nextId;
            RecordedCall ancestor = calls.get(parent); long root = ancestor == null ? callId : ancestor.root();
            if (ancestor == null) parent = 0;
            Thread thread = Thread.currentThread();
            calls.put(callId, new RecordedCall(id,callId,parent,root,type,method,descriptor,names,thread.getId(),thread.getName().substring(0,Math.min(128,thread.getName().length())),
                    System.currentTimeMillis(),0,"RUNNING","Capturing input","","","",-1,++revision));
        }
        // Never hold the history lock while inspecting an application value (e.g. a synchronized collection).
        Map<String,Object> named = new LinkedHashMap<>(); String[] labels = names.split("\n", -1);
        for (int i=0; i<Math.min(args.length,32); i++) named.put(i < labels.length && !labels[i].isBlank() ? labels[i] : "arg"+i, args[i]);
        if (args.length > 32) named.put("…", "Only the first 32 arguments were recorded");
        String input = freeze(named);
        synchronized (this) {
            RecordedCall old = calls.get(callId);
            calls.put(callId,new RecordedCall(id,callId,old.parent(),old.root(),type,method,descriptor,names,old.threadId(),old.threadName(),
                    old.startedAt(),0,"RUNNING","Running",retain(input),"","",-1,++revision));
        }
        return new Ticket(this,callId);
    }
    void exit(Ticket ticket, long elapsed, Object result, Throwable failure, long event) {
        if (epoch != SpringContextHolder.epoch()) return;
        boolean returnsVoid;
        synchronized (this) { RecordedCall old=calls.get(ticket.id()); if(old==null)return; returnsVoid=old.descriptor().endsWith(")V"); }
        // Preview generation never invokes application getters or serializers.
        String output = failure != null ? "" : returnsVoid ? ValueTree.leaf("result","STRING","void","Completed without a return value").encode() : freeze(result);
        String message = failure == null ? "" : failureMessage(failure);
        String exception = failure == null ? "" : freeze(Map.of("type", failure.getClass().getName(), "message", message));
        String summary = bounded(failure == null ? returnsVoid ? "void" : summary(ValueTree.decode(output),0) : failure.getClass().getSimpleName()+": "+message);
        synchronized (this) {
            if (ticket.history() != this || epoch != SpringContextHolder.epoch()) return;
            RecordedCall old = calls.get(ticket.id()); if (old == null || !old.status().equals("RUNNING")) return;
            calls.put(old.id(),new RecordedCall(id,old.id(),old.parent(),old.root(),old.className(),old.method(),old.descriptor(),old.parameterNames(),
                    old.threadId(),old.threadName(),old.startedAt(),Math.max(0,elapsed),failure == null ? "SUCCESS" : "ERROR",
                    summary,old.input(),retain(output),retain(exception),event,++revision));
        }
    }
    synchronized Map<String,Object> list() {
        return Map.of("recording",id,"value",String.join("\n",calls.values().stream().map(c -> c.header().encode()).toList()),
                "active",recording(),"revision",revision,"dropped",dropped,"bytes",bytes,"context-epoch",epoch);
    }
    synchronized RecordedCall call(String recording, long id) {
        if (!this.id.equals(recording)) throw new IllegalArgumentException("Recording changed; refresh the call history");
        RecordedCall result = calls.get(id); if (result == null) throw new IllegalArgumentException("Recorded call not found");
        return result;
    }
    private static String freeze(Object value) {
        String wire = Objects.toString(ValuePresentation.present(value).get("view-data"));
        return wire.length() <= RecordedCall.MAX_VALUE ? wire : limit("Recorded preview limit reached; use Inspect live while available");
    }
    private static String limit(String reason) { return ValueTree.leaf("result","LIMIT","",reason).encode(); }
    private static String summary(ValueTree tree, int depth) {
        if (tree.kind().equals("OBJECT") || tree.kind().equals("ARRAY")) {
            boolean object=tree.kind().equals("OBJECT");
            if(depth>=2)return object ? "{…}" : "[…]";
            return (object ? "{" : "[")+String.join(", ",tree.children().stream().limit(3)
                    .map(c -> (object ? ObjectInspector.text(c.label(),32)+": " : "")+summary(c,depth+1)).toList())
                    +(tree.children().size()>3 ? ", …" : "")+(object ? "}" : "]");
        }
        return (tree.kind().equals("STRING") ? "\"" : "")+ObjectInspector.text(tree.text(),80)+(tree.kind().equals("STRING") ? "\"" : "");
    }
    private String retain(String value) {
        if (bytes + value.length()*2 > MAX_BYTES) {
            active = false;
            value = limit("Recording memory limit reached");
            if (bytes + value.length()*2 > MAX_BYTES) value = "";
        }
        bytes += value.length()*2;
        return value;
    }
    private static String failureMessage(Throwable failure) {
        try {
            if (failure.getClass().getMethod("getMessage").getDeclaringClass() == Throwable.class)
                return bounded(Objects.toString(failure.getMessage(), ""));
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        return "Custom exception message was not evaluated";
    }
    private static String bounded(String value) { return value.substring(0,Math.min(value.length(),4096)); }
}
