package com.baader.devrt;

import hu.baader.repl.protocol.*;
import java.lang.ref.*;
import java.util.*;

/** Inert outside an observed synchronous flow/CASE. Never executes SQL or reads a ResultSet. */
public final class SqlRecorder {
    private static volatile boolean installed, failed;
    private static final ThreadLocal<Integer> DEPTH=ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Integer> CONNECTION_DEPTH=ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Log> CASE=new ThreadLocal<>();
    private static final WeakIdentity<StatementInfo> STATEMENTS=new WeakIdentity<>();
    private static final WeakIdentity<String> CONNECTIONS=new WeakIdentity<>();
    private record StatementInfo(String sql,String datasource) {}
    private record Target(Log log,long parent,long root) {}
    private record Invocation(List<Target> targets,long start,long wall,String kind,String operation,String sql,String datasource,StackTraceElement source) {}
    public static void installed() { installed=true; }
    public static void failed(String name,String error) { if(!failed) System.err.println("[dev-runtime] JDBC capture incomplete: "+name+" ("+error+")"); failed=true; }
    static boolean available() { return installed && !failed; }
    public static final class Log {
        final HibernateRecorder.Log orm;
        private final boolean enabled; private final int threshold;
        private final List<SqlObservation> events=new ArrayList<>();
        private long revision,total,dropped,pending; private int wireBytes;
        Log(boolean enabled,int threshold) { this(enabled,threshold,false); }
        Log(boolean enabled,int threshold,boolean hibernate) { this.enabled=enabled; this.threshold=threshold;orm=new HibernateRecorder.Log(hibernate); }
        boolean enabled() { return enabled; }
        synchronized void begin() { pending++; revision++; }
        synchronized void end(Invocation call,Target target,Throwable error) {
            pending--; total++; revision++;
            SqlObservation event=new SqlObservation(total,target.parent(),target.root(),Thread.currentThread().getId(),call.wall(),Math.max(0,System.nanoTime()-call.start()),
                    call.kind(),call.operation(),call.sql(),bound(call.datasource()),bound(call.source().getClassName()),bound(call.source().getMethodName()),
                    bound(Objects.toString(call.source().getFileName(),"")),call.source().getLineNumber(),error==null ? "" : bound(error.getClass().getName()),0,HibernateRecorder.currentId(orm));
            int size=event.encode().length()+1;
            if(events.size()>=SqlSnapshot.MAX_EVENTS || wireBytes+size>SqlSnapshot.MAX_WIRE-1000) { dropped++; return; }
            events.add(event); wireBytes+=size;
        }
        synchronized SqlSnapshot snapshot() { return new SqlSnapshot(enabled,available(),revision,total,dropped,pending,threshold,events); }
    }
    static final class Scope implements AutoCloseable {
        private final Log previous=CASE.get(), log;
        Scope() { this(false); }
        Scope(boolean hibernate) {
            if(!available()) throw new IllegalStateException("SQL assertions require the sb-repl Java agent with JDBC instrumentation");
            log=new Log(true,5,hibernate);
            if(hibernate&&!log.orm.snapshot().available())throw new IllegalStateException("Hibernate assertions require the sb-repl agent and Hibernate 6.6 with an observed session");
            CASE.set(log);
        }
        SqlSnapshot snapshot() { return log.snapshot(); }
        HibernateSnapshot hibernate() { return log.orm.snapshot(); }
        public void close() { if(previous==null) CASE.remove(); else CASE.set(previous); }
    }
    static Scope scope() { return new Scope(); }
    static Scope scope(boolean hibernate) { return new Scope(hibernate); }
    static Log caseLog() { return CASE.get(); }
    private static List<Target> targets() {
        List<Target> targets=new ArrayList<>();
        for(ExecutionHistory.Ticket ticket:TraceRecorder.currentRecordings()) {
            ExecutionHistory h=ticket.history();
            if(h.sql.enabled && h.epoch==SpringContextHolder.epoch()) targets.add(new Target(h.sql,ticket.id(),h.root(ticket.id())));
        }
        Log scope=CASE.get(); if(scope!=null) targets.add(new Target(scope,0,0));
        return targets;
    }
    public static Object enter(Object statement,String method,Object[] args) {
        int depth=DEPTH.get(); DEPTH.set(depth+1);
        if(depth!=0) return null;
        List<Target> targets=targets(); if(targets.isEmpty()) return null;
        StatementInfo info=STATEMENTS.get(statement);
        String sql=args.length>0 && args[0] instanceof String text ? SqlText.normalize(text) : info==null ? "<SQL unavailable: statement predates instrumentation or metadata limit>" : info.sql();
        String ds=info==null ? statement.getClass().getName() : info.datasource();
        if(method.toLowerCase(Locale.ROOT).contains("batch")) sql="<JDBC batch; individual statements not counted> "+sql;
        return begin(targets,"SQL",method,sql,ds);
    }
    public static void exit(Object token,Throwable error) {
        int depth=DEPTH.get()-1; if(depth<=0) DEPTH.remove(); else DEPTH.set(depth);
        if(token instanceof Invocation call) for(Target target:call.targets()) target.log().end(call,target,error);
    }
    public static Object connectionEnter() {
        int depth=CONNECTION_DEPTH.get(); CONNECTION_DEPTH.set(depth+1);
        if(depth!=0) return null;
        List<Target> targets=targets(); return targets.isEmpty() ? null : begin(targets,"CONNECTION","getConnection","","");
    }
    public static void connectionExit(Object token,Object datasource,Object connection,Throwable error) {
        String label=datasource.getClass().getName()+"@"+Integer.toHexString(System.identityHashCode(datasource));
        if(connection!=null) CONNECTIONS.put(connection,label);
        int depth=CONNECTION_DEPTH.get()-1; if(depth<=0) CONNECTION_DEPTH.remove(); else CONNECTION_DEPTH.set(depth);
        if(token instanceof Invocation c) {
            Invocation call=new Invocation(c.targets(),c.start(),c.wall(),c.kind(),c.operation(),c.sql(),label,c.source());
            for(Target target:call.targets()) target.log().end(call,target,error);
        }
    }
    public static void prepared(Object connection,Object statement,Object[] args) {
        if(statement==null) return;
        String ds=CONNECTIONS.get(connection);
        STATEMENTS.put(statement,new StatementInfo(args.length>0 && args[0] instanceof String s ? SqlText.normalize(s) : "<SQL unavailable>",
                ds==null ? connection.getClass().getName() : ds));
    }
    private static Invocation begin(List<Target> targets,String kind,String operation,String sql,String ds) {
        StackTraceElement source=source();
        targets.forEach(t -> t.log().begin());
        return new Invocation(targets,System.nanoTime(),System.currentTimeMillis(),kind,operation,sql,ds,source);
    }
    static StackTraceElement source() {
        return Arrays.stream(Thread.currentThread().getStackTrace()).filter(f -> {
            String n=f.getClassName(); return !n.startsWith("java.") && !n.startsWith("jdk.") && !n.startsWith("sun.") && !n.startsWith("com.baader.devrt.")
                    && !n.startsWith("org.springframework.") && !n.startsWith("org.hibernate.") && !n.startsWith("com.zaxxer.") && !n.startsWith("org.h2.")
                    && !n.startsWith("org.postgresql.") && !n.startsWith("com.mysql.") && !n.startsWith("org.mariadb.") && !n.startsWith("net.bytebuddy.")
                    && !n.contains("$HibernateProxy") && !n.contains("$Proxy") && !n.startsWith("com.sun.proxy.");
        }).findFirst().orElse(new StackTraceElement("unknown","unknown",null,-1));
    }
    private static String bound(String s) { return s.substring(0,Math.min(4096,s.length())); }
    /** Weak identity keys never call user equals/hashCode and cannot retain a pool or connection. */
    private static final class WeakIdentity<V> {
        final ReferenceQueue<Object> queue=new ReferenceQueue<>(); final Map<Key,V> data=new HashMap<>();
        synchronized V get(Object key) { clean(); return data.get(new Key(key,null)); }
        synchronized void put(Object key,V value) { clean(); if(data.size()>=10000) data.remove(data.keySet().iterator().next()); data.put(new Key(key,queue),value); }
        void clean() { Reference<?> key; while((key=queue.poll())!=null) data.remove(key); }
        static final class Key extends WeakReference<Object> {
            final int hash; Key(Object key,ReferenceQueue<Object> q) { super(key,q); hash=System.identityHashCode(key); }
            public int hashCode() { return hash; }
            public boolean equals(Object other) { return this==other || other instanceof Key k && get()!=null && get()==k.get(); }
        }
    }
}
