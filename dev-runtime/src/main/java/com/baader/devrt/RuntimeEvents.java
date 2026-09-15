package com.baader.devrt;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded live references, isolated by REPL session. Nothing is subscribed by default. */
public final class RuntimeEvents {
    static final int MAX_EVENTS=128, MAX_DEBUG_VALUES=16;
    static final long TTL_MS=5*60_000L;
    private static final ConcurrentHashMap<String, Channel> CHANNELS=new ConcurrentHashMap<>();
    private static final AtomicLong SEQUENCE=new AtomicLong();
    private record Event(long id,long time,String kind,String label,long nanos,Object value) {}
    private record Ticket(long time,Object value) {}
    private static final class Channel {
        final long epoch=SpringContextHolder.epoch();
        volatile boolean active=true;
        final Deque<Event> events=new ArrayDeque<>();
        final java.util.concurrent.atomic.AtomicReference<Map<String,Ticket>> debug=new java.util.concurrent.atomic.AtomicReference<>(Map.of());
        boolean tapping;
        String filter="";
        long dropped;
        synchronized void expire(long now) {
            while (!events.isEmpty() && now-events.peekFirst().time()>TTL_MS) events.removeFirst();
            debug.updateAndGet(old -> { Map<String,Ticket> next=new HashMap<>(old); next.values().removeIf(item -> now-item.time()>TTL_MS); return Map.copyOf(next); });
        }
        synchronized long add(String kind,String label,long nanos,Object value) {
            expire(System.currentTimeMillis());
            if (!active || epoch!=SpringContextHolder.epoch()) return -1;
            while(events.size()>=MAX_EVENTS) { events.removeFirst(); dropped++; }
            long id=SEQUENCE.incrementAndGet();
            events.addLast(new Event(id,System.currentTimeMillis(),kind,label,nanos,value));
            return id;
        }
    }
    static {
        Executors.newSingleThreadScheduledExecutor(r -> { Thread t=new Thread(r,"sb-repl-event-expiry"); t.setDaemon(true); return t; })
                .scheduleAtFixedRate(() -> expire(System.currentTimeMillis()),30,30,TimeUnit.SECONDS);
    }
    private RuntimeEvents() {}
    static void expire(long now) { CHANNELS.values().forEach(c -> c.expire(now)); }
    static void register(String owner) { Channel old=CHANNELS.put(owner,new Channel()); if(old!=null) old.active=false; }
    static void release(String owner) { Channel old=CHANNELS.remove(owner); if(old!=null) old.active=false; TraceRecorder.release(owner); }
    static void clearAll() { for (String owner:CHANNELS.keySet()) release(owner); }
    static void requireSession(String owner) { channel(owner); }
    private static Channel channel(String owner) {
        Channel c=CHANNELS.get(owner);
        if(c==null || !c.active || c.epoch!=SpringContextHolder.epoch()) throw new IllegalStateException("Event session expired; reconnect or reset");
        return c;
    }
    static void subscribe(String owner,boolean enabled,String label) {
        if(label==null || label.length()>128) throw new IllegalArgumentException("Tap label must be at most 128 characters");
        Channel c=channel(owner);
        synchronized(c) { c.tapping=enabled; c.filter=label; }
    }
    /** Return false when no matching subscriber exists; preserves the original object's identity. */
    public static boolean tap(String label,Object value) {
        if(label==null || label.length()>128) return false;
        boolean captured=false;
        for(Channel c:CHANNELS.values()) synchronized(c) {
            if(c.active && c.epoch==SpringContextHolder.epoch() && c.tapping && (c.filter.isEmpty() || c.filter.equals(label))) {
                c.add("TAP",label,0,value); captured=true;
            }
        }
        return captured;
    }
    static long trace(String owner,String label,long nanos,Object value) {
        Channel c=CHANNELS.get(owner);
        return c==null ? -1 : c.add("TRACE",label,nanos,value);
    }
    static long recordCase(String owner,String name,long nanos,Object value) { return channel(owner).add("CASE",name,nanos,value); }
    static Map<String,Object> list(String owner) {
        Channel c=channel(owner);
        synchronized(c) {
            c.expire(System.currentTimeMillis());
            List<String> rows=new ArrayList<>();
            for(Event event:c.events) rows.add(event.id()+"\t"+event.time()+"\t"+event.kind()+"\t"+ObjectInspector.text(event.label(),256)
                    +"\t"+(event.nanos()/1_000_000.0)+"\t"+ObjectInspector.preview(event.value()));
            return Map.of("value",String.join("\n",rows),"subscribed",c.tapping,"dropped",c.dropped,"filter",c.filter);
        }
    }
    static Object value(String owner,long eventId) {
        Channel c=channel(owner);
        synchronized(c) {
            c.expire(System.currentTimeMillis());
            return c.events.stream().filter(e -> e.id()==eventId).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Event expired or belongs to another session")).value();
        }
    }
    static void clear(String owner) { Channel c=channel(owner); synchronized(c) { c.events.clear(); c.dropped=0; } }
    static void captureDebug(String owner,String ticket,Object value) {
        if(ticket==null || !ticket.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("Invalid debugger ticket");
        Channel c=channel(owner);
        // No monitor: another application/nREPL thread may be suspended while holding one.
        for(;;) {
            Map<String,Ticket> old=c.debug.get();
            Map<String,Ticket> next=new HashMap<>(old);
            next.values().removeIf(item -> System.currentTimeMillis()-item.time()>TTL_MS);
            if(next.size()>=MAX_DEBUG_VALUES) throw new IllegalStateException("Resume and import pending debugger values first");
            if(next.containsKey(ticket)) throw new IllegalArgumentException("Debugger ticket already captured");
            next.put(ticket,new Ticket(System.currentTimeMillis(),value));
            if(c.debug.compareAndSet(old,Map.copyOf(next))) return;
        }
    }
    static Object claimDebug(String owner,String ticket) {
        Channel c=channel(owner);
        for(;;) {
            Map<String,Ticket> old=c.debug.get();
            Ticket found=old.get(ticket);
            if(found==null || System.currentTimeMillis()-found.time()>TTL_MS) throw new IllegalArgumentException("Debugger value expired, already imported, or from another session");
            Map<String,Ticket> next=new HashMap<>(old); next.remove(ticket);
            if(c.debug.compareAndSet(old,Map.copyOf(next))) return found.value();
        }
    }
}
