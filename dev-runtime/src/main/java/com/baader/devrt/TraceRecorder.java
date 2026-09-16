package com.baader.devrt;

import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.*;

/** Public entry points for inlined agent advice; rules and values remain session-owned. */
public final class TraceRecorder {
    private record Rule(Class<?> type,String method) {}
    private record Call(Rule rule,List<String> owners,long start,int depth,Object[] args,String thread,Call parent,
                        Map<String,ExecutionHistory.Ticket> recordings) {}
    private static final Map<String,Set<Rule>> RULES=new ConcurrentHashMap<>();
    private static final ThreadLocal<Call> FRAME=new ThreadLocal<>();
    private static final ThreadLocal<Http> HTTP=new ThreadLocal<>();
    private static final class Http {
        boolean responsePhase;
        final Object request; final long start=System.nanoTime();
        final Map<String,ExecutionHistory.Ticket> tickets=new LinkedHashMap<>();
        Http(Object request) { this.request=request; }
    }
    public static Object httpEnter(Object request) {
        if(WATCHES.isEmpty() && SqlRecorder.caseLog()==null || HTTP.get()!=null) return null;
        Http http=new Http(request); HTTP.set(http); return http;
    }
    public static void httpExit(Object token,Throwable error) {
        if(!(token instanceof Http http)) return;
        HTTP.remove();
        http.tickets.values().forEach(t -> t.history().exit(t,System.nanoTime()-http.start,null,error,-1));
    }
    public static void responseStarted() { Http http=HTTP.get();if(http!=null)http.responsePhase=true; }
    static boolean responsePhase() { Http http=HTTP.get();return http!=null&&http.responsePhase; }
    static Collection<ExecutionHistory.Ticket> currentRecordings() {
        if(CallExperiments.capturing())return List.of();
        Map<String,ExecutionHistory.Ticket> result=new LinkedHashMap<>();
        for(Call c=FRAME.get(); c!=null; c=c.parent()) c.recordings().forEach(result::putIfAbsent);
        Http http=HTTP.get(); if(http!=null) http.tickets.forEach(result::putIfAbsent);
        AsyncRecorder.current().forEach(t->result.putIfAbsent(t.history().owner,t));
        return result.values();
    }
    private static long httpParent(String owner,ExecutionHistory history) {
        Http http=HTTP.get(); if(http==null || !history.sql.enabled()) return 0;
        ExecutionHistory.Ticket existing=http.tickets.get(owner);
        if(existing!=null && existing.history()==history) return existing.id();
        String label="HTTP request";
        try {
            Class<?> api=Class.forName("jakarta.servlet.http.HttpServletRequest",false,http.request.getClass().getClassLoader());
            label=api.getMethod("getMethod").invoke(http.request)+" "+api.getMethod("getRequestURI").invoke(http.request);
        } catch(ReflectiveOperationException ignored) { }
        ExecutionHistory.Ticket ticket=history.enter(0,"http.Request","dispatch","(Ljava/lang/String;)V","request",new Object[]{label});
        if(ticket!=null) { http.tickets.put(owner,ticket); return ticket.id(); } return 0;
    }
    private static final Map<String,Set<Class<?>>> WATCHES=new ConcurrentHashMap<>();
    private static final ClassValue<Map<String,String>> PARAMETERS=new ClassValue<>() {
        @Override protected Map<String,String> computeValue(Class<?> type) {
            Map<String,String> result=new HashMap<>();
            for (var m:type.getDeclaredMethods()) result.put(m.getName()+java.lang.invoke.MethodType.methodType(m.getReturnType(),m.getParameterTypes()).toMethodDescriptorString(),
                String.join("\n",Arrays.stream(m.getParameters()).limit(32).map(p -> p.getName().substring(0,Math.min(64,p.getName().length()))).toList()));
            return result;
        }
    };
    private static final ExecutorService CLEANUP=Executors.newSingleThreadExecutor(r -> { Thread t=new Thread(r,"sb-repl-trace-cleanup"); t.setDaemon(true); return t; });
    private TraceRecorder() {}
    static synchronized Map<String,Object> configure(String owner,String className,String method,boolean enabled) throws Exception {
        RuntimeEvents.requireSession(owner);
        if(className==null || !className.matches("[\\w$]+(?:\\.[\\w$]+)+") || className.length()>512
                || method==null || !javax.lang.model.SourceVersion.isIdentifier(method)) throw new IllegalArgumentException("Select an exact Java class and method name");
        if(className.startsWith("java.") || className.startsWith("javax.") || className.startsWith("jdk.") || className.startsWith("sun.")
                || className.startsWith("com.baader.devrt.") || className.startsWith("net.bytebuddy.") || className.startsWith("hu.baader.repl.protocol."))
            throw new IllegalArgumentException("Trace application methods, not JDK/agent infrastructure");
        Class<?> type=Class.forName(className,false,AppClassPath.loader(SpringContextHolder.get()));
        if(Arrays.stream(type.getDeclaredMethods()).noneMatch(m -> m.getName().equals(method) && !Modifier.isNative(m.getModifiers()) && !Modifier.isAbstract(m.getModifiers()) && !m.isSynthetic()))
            throw new IllegalArgumentException("No concrete declared method: "+method);
        Rule rule=new Rule(type,method);
        Set<Rule> before=RULES.getOrDefault(owner,Set.of());
        Set<Rule> after=new HashSet<>(before);
        if(enabled) after.add(rule); else after.remove(rule);
        if(after.size()>8) throw new IllegalArgumentException("At most eight traced methods per session");
        Set<Class<?>> all=new HashSet<>();
        RULES.values().forEach(rules -> rules.forEach(r -> all.add(r.type()))); WATCHES.values().forEach(all::addAll);
        if(enabled && all.size()>=16 && !all.contains(type)) throw new IllegalArgumentException("Trace class limit reached (16)");
        RULES.put(owner,Set.copyOf(after));
        try { Agent.configureTrace(type,methods(type)); }
        catch(Exception failure) {
            RULES.put(owner,before);
            try { Agent.configureTrace(type,methods(type)); } catch(Exception restore) { failure.addSuppressed(restore); }
            throw failure;
        }
        return list(owner);
    }
    private static Set<String> methods(Class<?> type) {
        Set<String> methods=new HashSet<>();
        if (WATCHES.values().stream().anyMatch(types -> types.contains(type))) methods.addAll(concreteMethods(type));
        RULES.values().forEach(rules -> rules.stream().filter(r -> r.type()==type).forEach(r -> methods.add(r.method())));
        return methods;
    }
    static Map<String,Object> list(String owner) {
        return Map.of("value",String.join("\n",RULES.getOrDefault(owner,Set.of()).stream().map(r -> r.type().getName()+"\t"+r.method()).sorted().toList()));
    }
    static synchronized void release(String owner) {
        stopRecording(owner); ExecutionHistory.release(owner); AsyncRecorder.prune();
        Set<Rule> removed=RULES.remove(owner);
        if(removed!=null) for(Class<?> type:removed.stream().map(Rule::type).distinct().toList()) CLEANUP.execute(() -> {
            synchronized(TraceRecorder.class) {
                try { Agent.configureTrace(type,methods(type)); }
                catch(Exception ignored) { /* Removed rules make remaining advice inert. */ }
            }
        });
    }
    private static Set<String> concreteMethods(Class<?> type) {
        Set<String> names=new HashSet<>();
        for(var m:type.getDeclaredMethods()) if(!Modifier.isNative(m.getModifiers()) && !Modifier.isAbstract(m.getModifiers()) && !m.isSynthetic()) names.add(m.getName());
        return names;
    }
    static synchronized Map<String,Object> record(String owner,String classes) throws Exception {
        return record(owner,classes,false,5);
    }
    static synchronized Map<String,Object> record(String owner,String classes,boolean sql,int threshold) throws Exception {
        return record(owner,classes,sql,threshold,false);
    }
    static synchronized Map<String,Object> record(String owner,String classes,boolean sql,int threshold,boolean hibernate) throws Exception {
        return record(owner,classes,sql,threshold,hibernate,false);
    }
    static synchronized Map<String,Object> record(String owner,String classes,boolean sql,int threshold,boolean hibernate,boolean captureData) throws Exception {
        return record(owner,classes,sql,threshold,hibernate,captureData,false);
    }
    static synchronized Map<String,Object> record(String owner,String classes,boolean sql,int threshold,boolean hibernate,boolean captureData,boolean async) throws Exception {
        if(async&&!AsyncRecorder.available())throw new IllegalStateException("Async recording is unavailable; restart with the current agent or disable async capture");
        if(hibernate&&!sql)throw new IllegalArgumentException("Hibernate recording requires SQL recording");
        if(threshold<2 || threshold>1000) throw new IllegalArgumentException("N+1 threshold must be 2–1000");
        RuntimeEvents.requireSession(owner);
        ExecutionHistory old=ExecutionHistory.get(owner);
        if(old!=null && old.recording()) throw new IllegalStateException("Stop the current recording before starting another");
        if(classes==null || classes.length()>4096) throw new IllegalArgumentException("Choose 1–8 application classes");
        Set<Class<?>> types=new LinkedHashSet<>();
        for(String name:classes.lines().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList()) {
            if(!name.matches("[\\w$]+(?:\\.[\\w$]+)+") || name.length()>512 || name.startsWith("java.") || name.startsWith("javax.")
                || name.startsWith("jdk.") || name.startsWith("sun.") || name.startsWith("com.baader.devrt.") || name.startsWith("net.bytebuddy.")
                || name.startsWith("hu.baader.repl.protocol.")) throw new IllegalArgumentException("Choose an exact application class name: "+name);
            Class<?> type=Class.forName(name,false,AppClassPath.loader(SpringContextHolder.get()));
            Set<String> names=concreteMethods(type);
            if(names.isEmpty() || names.size()>64) throw new IllegalArgumentException("Choose classes with 1–64 concrete method names");
            PARAMETERS.get(type); types.add(type);
        }
        if(types.isEmpty() || types.size()>8) throw new IllegalArgumentException("Choose 1–8 application classes");
        Set<Class<?>> all=new HashSet<>(types);
        RULES.values().forEach(r -> r.forEach(rule -> all.add(rule.type())));
        WATCHES.entrySet().stream().filter(e -> !e.getKey().equals(owner)).forEach(e -> all.addAll(e.getValue()));
        if(all.size()>16) throw new IllegalArgumentException("Trace class limit reached (16 across sessions)");
        Set<Class<?>> previous=WATCHES.getOrDefault(owner,Set.of());
        Set<Class<?>> changed=new HashSet<>(types); changed.addAll(previous);
        WATCHES.put(owner,Set.copyOf(types));
        try {
            for(Class<?> type:changed) Agent.configureTrace(type,methods(type));
            ExecutionHistory.begin(owner,sql,threshold,hibernate,captureData,async);
            if(async)AsyncRecorder.enabled(true);
        } catch(Exception failure) {
            WATCHES.put(owner,previous);
            for(Class<?> type:changed) try { Agent.configureTrace(type,methods(type)); } catch(Exception restore) { failure.addSuppressed(restore); }
            throw failure;
        }
        return history(owner);
    }
    static synchronized void stopRecording(String owner) {
        ExecutionHistory history=ExecutionHistory.get(owner); if(history!=null) history.stop();
        Set<Class<?>> removed=WATCHES.remove(owner);
        if(removed!=null) for(Class<?> type:removed) CLEANUP.execute(() -> {
            synchronized(TraceRecorder.class) { try { Agent.configureTrace(type,methods(type)); } catch(Exception ignored) { /* Rules already disabled. */ } }
        });
    }
    static Map<String,Object> history(String owner) {
        RuntimeEvents.requireSession(owner);
        AsyncRecorder.prune();
        ExecutionHistory history=ExecutionHistory.get(owner);
        Map<String,Object> result=new LinkedHashMap<>(history==null ? Map.of("recording","","value","","active",false) : history.list());
        result.put("classes",String.join("\n",WATCHES.getOrDefault(owner,Set.of()).stream().map(Class::getName).sorted().toList()));
        return result;
    }
    static Map<String,Object> recordedCall(String owner,String recording,long id) {
        RuntimeEvents.requireSession(owner); ExecutionHistory history=ExecutionHistory.get(owner);
        if(history==null) throw new IllegalArgumentException("No recording in this session");
        return Map.of("value",history.call(recording,id).encode());
    }
    public static Object enter(Class<?> type,String method,Object[] args) { return enter(type,method,"",args); }
    public static Object enter(Class<?> type,String method,String descriptor,Object[] args) {
        if(CallExperiments.capturing())return null;
        Rule rule=new Rule(type,method);
        List<String> owners=RULES.entrySet().stream().filter(e -> e.getValue().contains(rule)).map(Map.Entry::getKey).toList();
        Map<String,ExecutionHistory> histories=new LinkedHashMap<>();
        WATCHES.forEach((owner,types) -> { ExecutionHistory h=ExecutionHistory.get(owner); if(types.contains(type) && h!=null && h.recording()) histories.put(owner,h); });
        if(owners.isEmpty() && histories.isEmpty()) return null;
        Call parent=FRAME.get(); int depth=parent==null ? 0 : parent.depth()+1;
        if(depth>=64) return null;
        Map<String,ExecutionHistory.Ticket> tickets=new LinkedHashMap<>();
        String names=histories.isEmpty() ? "" : PARAMETERS.get(type).getOrDefault(method+descriptor,"");
        for(var entry:histories.entrySet()) {
            long parentId=0;
            for(Call frame=parent; frame!=null; frame=frame.parent()) {
                ExecutionHistory.Ticket candidate=frame.recordings().get(entry.getKey());
                if(candidate!=null && candidate.history()==entry.getValue()) { parentId=candidate.id(); break; }
            }
            if(parentId==0)for(var boundary:AsyncRecorder.current())if(boundary.history()==entry.getValue()){parentId=boundary.id();break;}
            if(parentId==0) parentId=httpParent(entry.getKey(),entry.getValue());
            ExecutionHistory.Ticket ticket=entry.getValue().enter(parentId,type.getName(),method,descriptor,names,args);
            if(ticket!=null) {tickets.put(entry.getKey(),ticket);entry.getValue().experiments.enter(ticket,type,method,descriptor,args);}
        }
        if(owners.isEmpty() && tickets.isEmpty()) return null;
        Call call=new Call(rule,owners,System.nanoTime(),depth,Arrays.copyOf(args,Math.min(args.length,32)),Thread.currentThread().getName(),parent,tickets);
        FRAME.set(call); return call;
    }
    public static void exit(Object ticket,Object result,Throwable error) {
        if(!(ticket instanceof Call call)) return;
        if(call.parent()==null) FRAME.remove(); else FRAME.set(call.parent());
        Map<String,Object> data=new LinkedHashMap<>();
        data.put("arguments",Arrays.asList(call.args())); data.put("result",result); data.put("exception",error);
        data.put("thread",call.thread()); data.put("depth",call.depth());
        long nanos=System.nanoTime()-call.start();
        Set<String> owners=new HashSet<>(call.owners()); owners.addAll(call.recordings().keySet());
        for(String owner:owners) {
            boolean traced=RULES.getOrDefault(owner,Set.of()).contains(call.rule());
            ExecutionHistory.Ticket recorded=call.recordings().get(owner);
            long event=traced || recorded!=null && ExecutionHistory.get(owner)==recorded.history() ? RuntimeEvents.trace(owner,call.rule().type().getName()+"."+call.rule().method(),nanos,data) : -1;
            if(recorded!=null) {recorded.history().exit(recorded,nanos,result,error,event);recorded.history().experiments.exit(recorded,result,error);}
        }
    }
}
