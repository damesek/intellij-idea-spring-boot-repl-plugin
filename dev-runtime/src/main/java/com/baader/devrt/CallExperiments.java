package com.baader.devrt;

import hu.baader.repl.protocol.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;

/** Optional full entry/exit DATA, separate from display previews. No application object is retained here. */
final class CallExperiments {
    static final int MAX_VALUE=2*1024*1024,MAX_TOTAL=32*1024*1024;
    private record Capture(Method method,String input,String expected,String exception,String message,String error) {}
    private static final ThreadLocal<Boolean> CAPTURING=ThreadLocal.withInitial(()->false);
    static boolean capturing(){return CAPTURING.get();}
    private final ExecutionHistory history;
    private final boolean enabled;
    private final Map<Long,Capture> captures=new HashMap<>();
    private int bytes;
    CallExperiments(ExecutionHistory history,boolean enabled){this.history=history;this.enabled=enabled;}
    void enter(ExecutionHistory.Ticket ticket,Class<?> type,String method,String descriptor,Object[] args){
        if(!enabled)return;
        CAPTURING.set(true);
        try(var ignored=SnapshotManager.scope(history.owner)){
            Method selected=BeanExplorer.method(type,method,descriptor);InvocationPlan.sourceType(type);InvocationPlan.types(selected);
            if(args.length>32)throw new IllegalArgumentException("At most 32 replay arguments");
            Map<String,Object> input=new LinkedHashMap<>();
            for(int i=0;i<args.length;i++)input.put("arg"+i,SnapshotManager.freezeData(args[i],"java.lang.Object",MAX_VALUE).get("payload"));
            String frozen=CaseJson.write(input);
            synchronized(this){reserve(frozen);captures.put(ticket.id(),new Capture(selected,frozen,"","","",""));}
        }catch(Throwable failure){failed(ticket.id(),failure);}finally{CAPTURING.remove();}
    }
    void exit(ExecutionHistory.Ticket ticket,Object value,Throwable error){
        if(!enabled)return;
        Capture before;synchronized(this){before=captures.get(ticket.id());}
        if(before==null||!before.error().isEmpty())return;
        CAPTURING.set(true);
        try(var ignored=SnapshotManager.scope(history.owner)){
            if(value instanceof java.util.concurrent.CompletionStage<?> || value instanceof java.util.concurrent.Future<?>)
                throw new IllegalArgumentException("Capture the completed value inside the asynchronous task; a Future is not a replayable result");
            String exception=error==null?"":error.getClass().getName(),message=error==null?"":safeMessage(error);
            Object actual=error==null?value:Map.of("type",exception,"message",message);
            String expected=CaseJson.write(SnapshotManager.freezeData(actual,"java.lang.Object",MAX_VALUE).get("payload"));
            synchronized(this){reserve(expected);captures.put(ticket.id(),new Capture(before.method(),before.input(),expected,exception,message,""));}
        }catch(Throwable failure){failed(ticket.id(),failure);}finally{CAPTURING.remove();}
    }
    private static String safeMessage(Throwable error){
        try{String message=Objects.toString(error.getMessage(),error.getClass().getName());return message.substring(0,Math.min(message.length(),4096));}
        catch(Throwable ignored){return error.getClass().getName();}
    }
    private void reserve(String json){int size=json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;if(size>MAX_VALUE||size>MAX_TOTAL-bytes)throw new IllegalArgumentException("Full DATA capture limit reached (2 MiB per input/result, 32 MiB per recording)");bytes+=size;}
    private synchronized void failed(long id,Throwable failure){captures.put(id,new Capture(null,"","","","",safeMessage(failure)));}
    synchronized Map<String,Object> info(long id) throws Exception {
        Capture capture=captures.get(id);Map<String,Object> result=new LinkedHashMap<>();
        result.put("enabled",enabled);result.put("bytes",bytes);
        if(capture==null){result.put("ready",false);result.put("detail",enabled?"Full DATA capture is not available for this call":"Enable Capture replay DATA before recording this call");return result;}
        result.put("ready",capture.error().isEmpty()&&!capture.expected().isEmpty());result.put("detail",capture.error().isEmpty()?capture.expected().isEmpty()?"Waiting for call completion":"Full input and outcome captured":capture.error());
        if(capture.method()!=null){result.put("signature",capture.method().toGenericString());result.put("beans",String.join("\n",Modifier.isStatic(capture.method().getModifiers())?List.of():BeanExplorer.names(SpringContextHolder.get(),capture.method().getDeclaringClass())));}
        return result;
    }
    Map<String,Object> create(long id,String name,String bean) throws Exception {
        Capture capture;synchronized(this){capture=captures.get(id);}
        if(capture==null||!capture.error().isEmpty()||capture.expected().isEmpty())throw new IllegalArgumentException("A complete, successful DATA capture is required; previews cannot be replayed");
        if(history.epoch!=SpringContextHolder.epoch())throw new IllegalArgumentException("Spring context changed; recorded DATA is no longer available in this session");
        Method method=capture.method();Class<?> beanType=method.getDeclaringClass();
        if(!Modifier.isStatic(method.getModifiers())){
            if(bean==null||bean.isBlank()){
                List<String> candidates=BeanExplorer.names(SpringContextHolder.get(),beanType);
                if(candidates.size()!=1)throw new IllegalArgumentException("Select a bean explicitly; candidates: "+candidates);bean=candidates.get(0);
            }
            beanType=BeanExplorer.type(SpringContextHolder.get(),bean);
            if(!BeanExplorer.names(SpringContextHolder.get(),method.getDeclaringClass()).contains(bean))throw new IllegalArgumentException("Selected bean is incompatible with the recorded receiver class");
            beanType=BeanExplorer.invocationType(beanType,method);
            method=beanType.getMethod(method.getName(),method.getParameterTypes());
        }
        name=SnapshotManager.checkedName(name);String input=SnapshotManager.checkedName(name+"-input"),expected=SnapshotManager.checkedName(name+"-expected");
        for(String target:List.of(name,input,expected))if(Files.exists(SnapshotManager.path(target)))throw new IllegalArgumentException("Reproduction name is already used: "+target);
        Map<String,String> definition=InvocationPlan.definition(method,beanType,bean,input,expected);
        definition.put("expected-exception",capture.exception());definition.put("expected-message",capture.message());
        Set<Long> descendants=new LinkedHashSet<>();descendants.add(id);
        List<RecordedCall> calls=history.calls();for(RecordedCall call:calls)if(descendants.contains(call.parent()))descendants.add(call.id());
        definition.put("observed-classes",String.join("\n",calls.stream().filter(c->descendants.contains(c.id())&&!c.className().startsWith("http.")&&!c.className().startsWith("async."))
                .map(RecordedCall::className).distinct().sorted().toList()));
        SqlSnapshot sql=history.sql.snapshot();HibernateSnapshot orm=history.sql.orm.snapshot();
        boolean complete=!history.incomplete()&&calls.stream().filter(c->descendants.contains(c.id())).noneMatch(c->c.status().equals("RUNNING"));
        if(sql.enabled()&&!sql.partial()&&complete){
            List<SqlObservation> events=sql.events().stream().filter(e->descendants.contains(e.parent())&&e.kind().equals("SQL")).toList();
            definition.put("max-sql-count",String.valueOf(events.size()));
            Map<String,Long> groups=new HashMap<>();events.forEach(e->groups.merge(e.fingerprint(),1L,Long::sum));
            definition.put("max-sql-repetitions",String.valueOf(groups.values().stream().mapToLong(Long::longValue).max().orElse(0)));
        }
        if(orm.enabled()&&!orm.partial()&&complete){
            var events=orm.events().stream().filter(e->descendants.contains(e.parent())).toList();
            definition.put("max-hibernate-loads",""+events.stream().filter(e->e.kind().equals("ENTITY_LOAD")).count());
            definition.put("max-hibernate-flushes",""+events.stream().filter(e->e.kind().equals("FLUSH")||e.kind().equals("AUTO_FLUSH")&&e.detail().contains("required=true")).count());
            definition.put("max-hibernate-lazy-loads",""+events.stream().filter(HibernateObservation::lazy).count());
            definition.put("max-hibernate-response-lazy-loads",""+events.stream().filter(e->e.lazy()&&e.responsePhase()).count());
        }
        definition=SnapshotCases.definition(definition);
        Map<String,Object> inputData=SnapshotManager.envelope(input,"DATA"),expectedData=SnapshotManager.envelope(expected,"DATA"),caseData=SnapshotManager.envelope(name,"CASE");
        inputData.put("declaredType",definition.get("type"));inputData.put("payload",CaseJson.parse(capture.input(),MAX_VALUE));
        expectedData.put("declaredType","java.lang.Object");expectedData.put("payload",CaseJson.parse(capture.expected(),MAX_VALUE));
        caseData.put("payload",definition);
        Map<String,Map<String,Object>> entries=new LinkedHashMap<>();entries.put(input,inputData);entries.put(expected,expectedData);entries.put(name,caseData);
        SnapshotManager.createNew(entries,Map.of());
        return Map.of("name",name,"input",input,"expected",expected,"value","CASE prepared; no method was rerun. Review generated codec, proxy behavior, expected outcome and budgets before running.");
    }
}
