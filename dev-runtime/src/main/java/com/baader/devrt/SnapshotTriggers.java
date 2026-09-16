package com.baader.devrt;

import java.util.*;
import java.util.function.Supplier;
import hu.baader.repl.protocol.AuditTrail;

/** Bounded independent capture rules. A claimed projection is synchronous; it is never automatically replayed. */
final class SnapshotTriggers {
    private static final LinkedHashMap<String,Capture> RULES = new LinkedHashMap<>();
    private static final class Capture {
        final String id = UUID.randomUUID().toString(), owner, point, name, filter, type;
        final long epoch = SpringContextHolder.epoch(), deadline;
        final int count, sampleEvery;
        final boolean legacy;
        String phase = "ARMED", error = "", savedName = "";
        long bytes, millis, matches;
        int saved;
        Capture(String owner, String point, String name, String filter, String type, long ttl, int count, int sampleEvery, boolean legacy) {
            this.owner=owner; this.point=point; this.name=name; this.filter=filter; this.type=type;
            this.count=count; this.sampleEvery=sampleEvery; this.legacy=legacy;
            deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(ttl);
        }
        boolean active() { return phase.equals("ARMED") || phase.equals("CAPTURING"); }
        String nameAt(int sequence) { return name.contains("${sequence}") ? name.replace("${sequence}", String.valueOf(sequence)) : count==1 ? name : name+"-"+sequence; }
        String nextName() { return nameAt(saved+1); }
    }
    private SnapshotTriggers() {}
    // Preserve the old Java API's single-slot contract. New protocol clients use explicit independent rules.
    static synchronized Map<String,Object> arm(String owner,String point,String name,String filter,String type,long ttl) {
        return arm(owner,point,name,filter,type,ttl,1,1,true);
    }
    static synchronized Map<String,Object> arm(String owner,String point,String name,String filter,String type,long ttl,int count,int sampleEvery) {
        return arm(owner,point,name,filter,type,ttl,count,sampleEvery,false);
    }
    private static Map<String,Object> arm(String owner,String point,String name,String filter,String type,long ttl,int count,int sampleEvery,boolean legacy) {
        expire();
        if (legacy && RULES.values().stream().anyMatch(Capture::active)) throw new IllegalStateException("A capture is already armed or saving");
        if (RULES.values().stream().filter(Capture::active).count() >= 16) throw new IllegalStateException("Capture limit reached (16 active rules per JVM)");
        SnapshotManager.checkedName(point); SnapshotManager.checkedName(name.replace("${sequence}","100")+(count>1&&!name.contains("${sequence}")?"-100":""));
        if (filter == null || filter.length()>256 || filter.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid capture case filter");
        if (!type.isBlank()) SnapshotManager.checkedType(type);
        if (ttl<1 || ttl>1800000) throw new IllegalArgumentException("Capture expiry must be 1 ms–30 minutes");
        if (count<1 || count>100 || sampleEvery<1 || sampleEvery>10000) throw new IllegalArgumentException("Capture count must be 1–100; sampling interval 1–10000");
        Capture rule = new Capture(owner,point,name,filter,type,ttl,count,sampleEvery,legacy);
        Set<String> planned = new HashSet<>();
        for(int i=1;i<=count;i++) planned.add(rule.nameAt(i));
        for(Capture c:RULES.values()) if(c.active()) for(int i=c.saved+1;i<=c.count;i++)
            if(planned.contains(c.nameAt(i))) throw new IllegalArgumentException("An active rule would write the same snapshot name");
        RULES.values().removeIf(c -> !c.active() && c.owner.equals(owner) && (legacy || RULES.size()>=128));
        if (RULES.size()>=128) RULES.values().removeIf(c -> !c.active());
        RULES.put(rule.id,rule);
        return view(rule);
    }
    static synchronized Map<String,Object> status(String owner) { return status(owner,""); }
    static synchronized Map<String,Object> status(String owner,String id) {
        expire(); Capture rule = select(owner,id);
        if (rule != null) return view(rule);
        return Map.of("phase", RULES.values().stream().anyMatch(Capture::active) ? "OTHER_SESSION" : "IDLE");
    }
    static synchronized Map<String,Object> list(String owner) {
        expire();
        return Map.of("value", String.join("\n", RULES.values().stream().filter(c -> c.owner.equals(owner)).map(c ->
                String.join("\t",c.id,c.phase,c.point,c.name,String.valueOf(c.saved),String.valueOf(c.count),String.valueOf(c.sampleEvery),c.savedName)).toList()));
    }
    private static Capture select(String owner,String id) {
        if (!id.isBlank()) {
            Capture c=RULES.get(id); if(c==null) return null;
            if(!c.owner.equals(owner)) throw new IllegalStateException("This capture belongs to another REPL session"); return c;
        }
        Capture last=null; for(Capture c:RULES.values()) if(c.owner.equals(owner)) last=c; return last;
    }
    private static Map<String,Object> view(Capture c) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("rule-id",c.id); result.put("phase",c.phase); result.put("point",c.point); result.put("name",c.name);
        result.put("case",c.filter); result.put("bytes",c.bytes); result.put("duration-ms",c.millis); result.put("detail",c.error);
        result.put("saved",c.saved); result.put("count",c.count); result.put("sample-every",c.sampleEvery); result.put("saved-name",c.savedName);
        result.put("expires-in-ms",Math.max(0,java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(c.deadline-System.nanoTime())));
        return result;
    }
    static synchronized Map<String,Object> disarm(String owner) { return disarm(owner,""); }
    static synchronized Map<String,Object> disarm(String owner,String id) {
        expire(); Capture c=select(owner,id);
        if(c==null) {
            if(RULES.values().stream().anyMatch(Capture::active)) throw new IllegalStateException("This capture belongs to another REPL session");
            return Map.of("phase","IDLE");
        }
        if(c.phase.equals("CAPTURING")) throw new IllegalStateException("Capture is already saving; wait for completion before disarming");
        if(c.phase.equals("ARMED")) c.phase="CANCELLED";
        return view(c);
    }
    static synchronized void release(String owner) { RULES.values().removeIf(c -> c.owner.equals(owner)); }
    static synchronized void clear() { RULES.clear(); }
    private static void expire() {
        for(Capture c:RULES.values()) if(c.phase.equals("ARMED") && (System.nanoTime()>=c.deadline || SpringContextHolder.epoch()!=c.epoch)) c.phase="EXPIRED";
    }
    static boolean capture(String point,String caseId,Supplier<?> supplier) {
        List<Capture> claimed=new ArrayList<>();
        synchronized(SnapshotTriggers.class) {
            expire();
            for(Capture c:RULES.values()) if(c.phase.equals("ARMED") && c.point.equals(point) && (c.filter.isEmpty() || c.filter.equals(caseId))) {
                if(c.matches++ % c.sampleEvery != 0) continue;
                c.phase="CAPTURING"; claimed.add(c);
            }
        }
        if(claimed.isEmpty()) return false;
        // A projection is invoked only once even when several rules match this application call.
        Object[] value={null}; boolean[] obtained={false}; RuntimeException[] projectionFailure={null};
        Supplier<?> once=() -> {
            if(projectionFailure[0]!=null) throw projectionFailure[0];
            if(!obtained[0]) { try { value[0]=supplier.get(); obtained[0]=true; } catch(RuntimeException ex) { projectionFailure[0]=ex; throw ex; } }
            return value[0];
        };
        boolean success=false;
        try {
            for(Capture c:claimed) {
                long start=System.nanoTime(); String name=c.nextName();
                String audit = "", channel = "runtime-" + ProcessHandle.current().pid();
                try(var ignored=SnapshotManager.scope(c.owner)) {
                    audit=AuditTrail.append(channel,Map.of("session",c.owner,"operation","capture/save","phase","STARTED","rule-id",c.id,"name",name,"point",point,"contextEpoch",String.valueOf(c.epoch)));
                    SnapshotManager.captureValue(name,c.type,once);
                    long bytes=SnapshotManager.size(name);
                    synchronized(SnapshotTriggers.class) { c.bytes=bytes; c.savedName=name; c.saved++; c.phase=c.saved>=c.count?"SAVED":"ARMED"; }
                    success=true;
                    AuditTrail.append(channel,Map.of("session",c.owner,"operation","capture/save","phase","COMPLETED","request",audit,"name",name,"bytes",String.valueOf(bytes),"durationMs",String.valueOf((System.nanoTime()-start)/1000000)));
                } catch(Exception failure) {
                    synchronized(SnapshotTriggers.class) { c.phase="FAILED"; c.error=Objects.toString(failure.getMessage(),failure.getClass().getName()); if(c.error.length()>1000)c.error=c.error.substring(0,1000); }
                    try { AuditTrail.append(channel,Map.of("session",c.owner,"operation","capture/save","phase","ERROR","request",audit,"name",name,"detail",c.error)); } catch(Exception unavailable) { /* Keep the failure visible in capture status; do not break the application request. */ }
                } finally { ReplNotifications.publish(c.owner,"capture.completed",Map.of("ruleId",c.id,"status",c.phase,"saved",c.saved)); synchronized(SnapshotTriggers.class) { c.millis=(System.nanoTime()-start)/1000000; } }
            }
        } finally {
            synchronized(SnapshotTriggers.class) {
                for(Capture c:claimed) if(c.phase.equals("CAPTURING")) { c.phase="FAILED"; c.error="Capture aborted by an application/JVM error"; }
                expire();
            }
        }
        return success;
    }
}
