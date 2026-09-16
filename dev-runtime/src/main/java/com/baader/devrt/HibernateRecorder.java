package com.baader.devrt;

import hu.baader.repl.protocol.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.*;

/** Optional 6.6 adapter. SQL remains the authority for actual JDBC executions. */
public final class HibernateRecorder {
    /** Also used by generated JUnit tests running with the matching agent. */
    public static CaseObservation openCase(){return new CaseObservation();}
    public static final class CaseObservation implements AutoCloseable {
        private final SqlRecorder.Scope scope=SqlRecorder.scope(true);
        public Map<String,Object> metrics(){var s=scope.hibernate();return Map.of("available",s.available(),"partial",s.partial(),"loads",s.count("ENTITY_LOAD"),"flushes",s.flushCount(),"lazy",s.lazyCount(),"responseLazy",s.responseLazyCount());}
        public void close(){scope.close();}
    }
    private static volatile boolean installed,failed;
    private static volatile String observedVersion="";
    private static final HibernateAccess.WeakIdentity<String> SESSIONS=new HibernateAccess.WeakIdentity<>();
    private static final HibernateAccess.WeakIdentity<Boolean> FACTORIES=new HibernateAccess.WeakIdentity<>();
    private static final ThreadLocal<Frame> CURRENT=new ThreadLocal<>();
    private record Target(Log log,long parent,long root) {}
    private record Ticket(Target target,long id,long parentOrm) {}
    private record Frame(Frame previous,List<Ticket> tickets,long start,long wall,String session,String kind,String entity,
                         String role,String detail,StackTraceElement source,boolean response) {}
    static final class Log {
        final boolean enabled;private boolean incomplete;private String version="";
        private long revision,next,dropped,pending;private int bytes;
        private final List<HibernateObservation> events=new ArrayList<>();
        Log(boolean enabled){this.enabled=enabled;}
        synchronized long begin(){pending++;revision++;return ++next;}
        synchronized void incomplete(){incomplete=true;revision++;}
        synchronized void observed(String value){if(!version.equals(value)){version=value;revision++;}}
        synchronized void end(Ticket t,Frame f,String detail,Throwable error) {
            pending--;revision++;
            var e=new HibernateObservation(t.id(),t.parentOrm(),t.target().parent(),t.target().root(),Thread.currentThread().getId(),f.wall(),Math.max(0,System.nanoTime()-f.start()),
                    f.session(),f.kind(),bound(f.entity()),bound(f.role()),bound(detail),bound(f.source().getClassName()),bound(f.source().getMethodName()),
                    bound(Objects.toString(f.source().getFileName(),"")),f.source().getLineNumber(),error==null?"":bound(error.getClass().getName()),f.response());
            int size=e.encode().length()+1;
            if(events.size()>=HibernateSnapshot.MAX_EVENTS||bytes+size>HibernateSnapshot.MAX_WIRE-1000){dropped++;return;}
            events.add(e);bytes+=size;
        }
        synchronized HibernateSnapshot snapshot(){String v=version.isEmpty()?observedVersion:version;return new HibernateSnapshot(enabled,installed&&!failed&&!incomplete&&v.startsWith("6.6."),v,revision,dropped,pending,events);}
    }
    public static void installed(){installed=true;}
    public static void unsupportedSession(){markIncomplete();}
    public static void failed(String name,String error){if(!failed)System.err.println("[dev-runtime] Hibernate capture incomplete: "+name+" ("+error+")");failed=true;}
    private static List<Target> targets() {
        List<Target> result=new ArrayList<>();
        for(var t:TraceRecorder.currentRecordings())if(t.history().sql.orm.enabled&&t.history().epoch==SpringContextHolder.epoch())
            result.add(new Target(t.history().sql.orm,t.id(),t.history().root(t.id())));
        SqlRecorder.Log scope=SqlRecorder.caseLog();if(scope!=null&&scope.orm.enabled)result.add(new Target(scope.orm,0,0));
        return result;
    }
    static long currentId(Log log) {
        for(Frame f=CURRENT.get();f!=null;f=f.previous())for(Ticket t:f.tickets())if(t.target().log()==log)return t.id();
        return 0;
    }
    private static Object begin(Object session,String kind,String entity,String role,String detail) throws ReflectiveOperationException {
        List<Target> targets=targets();if(targets.isEmpty())return null;
        boolean detached=session==null&&(kind.startsWith("LAZY_")||kind.equals("COLLECTION_INIT"));
        if(!detached&&(session==null||!HibernateAccess.version(session).startsWith("6.6.")||!HibernateAccess.is(session,"org.hibernate.internal.SessionImpl"))){targets.forEach(t->t.log().incomplete());return null;}
        if(session!=null)sessionCreated(session);
        String id=detached?"detached":SESSIONS.get(session);if(id==null){targets.forEach(t->t.log().incomplete());return null;}
        targets.forEach(t->t.log().observed(session==null?observedVersion:HibernateAccess.version(session)));
        Frame previous=CURRENT.get();List<Ticket> tickets=new ArrayList<>();
        int depth=0;for(Frame f=previous;f!=null;f=f.previous())if(++depth>=64){targets.forEach(t->t.log().incomplete());return null;}
        for(Target t:targets) {
            long parent=0;
            for(Frame f=previous;f!=null;f=f.previous())if(f.session().equals(id)) {
                for(Ticket ancestor:f.tickets())if(ancestor.target().log()==t.log()&&ancestor.target().root()==t.root()){parent=ancestor.id();break;}
                if(parent!=0)break;
            }
            tickets.add(new Ticket(t,t.log().begin(),parent));
        }
        Frame frame=new Frame(previous,tickets,System.nanoTime(),System.currentTimeMillis(),id,kind,entity,role,detail,SqlRecorder.source(),TraceRecorder.responsePhase());
        CURRENT.set(frame);return frame;
    }
    public static Object enter(Object object,String operation,Object[] args) {
        try {
            if(targets().isEmpty())return null;
            if(!HibernateAccess.version(object).startsWith("6.6.")){markIncomplete();return null;}
            Object session;String kind,entity="",role="",detail="";
            if(operation.equals("initialize")&&HibernateAccess.is(object,"org.hibernate.proxy.AbstractLazyInitializer")) {
                if(!Boolean.TRUE.equals(HibernateAccess.get(object,"isUninitialized")))return null;
                session=HibernateAccess.get(object,"getSession");kind="LAZY_ENTITY";entity=(String)HibernateAccess.get(object,"getEntityName");role=Objects.toString(HibernateAccess.ROLES.get(object),"unknown association");
            } else if(operation.equals("initialize")) {
                if(Boolean.TRUE.equals(HibernateAccess.get(object,"wasInitialized")))return null;
                session=HibernateAccess.get(object,"getSession");kind="COLLECTION_INIT";role=Objects.toString(HibernateAccess.get(object,"getRole"),"");
                if(role.lastIndexOf('.')>0)entity=role.substring(0,role.lastIndexOf('.'));
            } else if(operation.equals("fetchAttribute")) {
                session=HibernateAccess.get(object,"getLinkedSession");kind="LAZY_ATTRIBUTE";entity=Objects.toString(HibernateAccess.get(object,"getEntityName"),"");role=entity+"."+args[1];
            } else if(operation.equals("forceInitialize")) {
                if(Boolean.TRUE.equals(HibernateAccess.get(object,"isInitialized")))return null;
                session=args[2];kind="LAZY_ENTITY";entity=Objects.toString(HibernateAccess.get(object,"getEntityName"),"");role=Objects.toString(HibernateAccess.ROLES.get(object),"unknown association");
            } else if(object.getClass().getName().equals("org.hibernate.engine.transaction.internal.TransactionImpl")) {
                session=HibernateAccess.field(object,"session");kind="TRANSACTION_"+operation.toUpperCase(Locale.ROOT);
            } else if(operation.equals("autoFlushIfRequired")) {
                session=object;kind="AUTO_FLUSH";
            } else if(object.getClass().getName().equals("org.hibernate.cache.internal.QueryResultsCacheImpl")) {
                if(CURRENT.get()!=null&&CURRENT.get().kind().equals("QUERY_CACHE_READ"))return null;
                session=args[2];kind=operation.equals("get")?"QUERY_CACHE_READ":"QUERY_CACHE_PUT";
            } else {
                session=HibernateAccess.get(object,"getSession");kind="QUERY";
                detail=SqlText.normalize(Objects.toString(HibernateAccess.get(object,"getQueryString"),"<criteria query>"));
            }
            return begin(session,kind,entity,role,detail);
        } catch(ReflectiveOperationException|RuntimeException|LinkageError e) { markIncomplete();return null; }
    }
    public static void exit(Object token,Object result,Throwable error) {
        if(!(token instanceof Frame f))return;
        if(f.previous()==null)CURRENT.remove();else CURRENT.set(f.previous());
        if(f.kind().equals("QUERY_CACHE_READ")) f=new Frame(f.previous(),f.tickets(),f.start(),f.wall(),f.session(),result==null?"QUERY_CACHE_MISS":"QUERY_CACHE_HIT",f.entity(),f.role(),f.detail(),f.source(),f.response());
        String detail=f.kind().equals("AUTO_FLUSH")?"required="+Boolean.TRUE.equals(result):f.kind().equals("QUERY_CACHE_PUT")?"stored="+Boolean.TRUE.equals(result):f.detail();
        for(Ticket t:f.tickets())t.target().log().end(t,f,detail,error);
    }
    private static void finish(Object token,String detail) {
        if(!(token instanceof Frame f))return;
        if(f.previous()==null)CURRENT.remove();else CURRENT.set(f.previous());
        for(Ticket t:f.tickets())t.target().log().end(t,f,detail,null);
    }
    private static void markIncomplete(){targets().forEach(t->t.log().incomplete());}
    /** Called by SessionImpl constructor advice, never replaces application listeners. */
    public static synchronized void sessionCreated(Object session) {
        if(SESSIONS.get(session)!=null)return;
        try {
            String version=HibernateAccess.version(session);if(observedVersion.isEmpty()||version.startsWith("6.6."))observedVersion=version;
            if(!version.startsWith("6.6."))return;
            Object factory=HibernateAccess.get(session,"getFactory");registerFactory(factory);
            ClassLoader loader=session.getClass().getClassLoader();
            Class<?> api=Class.forName("org.hibernate.SessionEventListener",false,loader);
            Object listener=Proxy.newProxyInstance(loader,new Class<?>[]{api},new SessionListener(session));
            Object manager=HibernateAccess.get(session,"getEventListenerManager");
            Object array=Array.newInstance(api,1);Array.set(array,0,listener);
            HibernateAccess.get(manager,"addListener",array);
            SESSIONS.put(session,UUID.randomUUID().toString());
        } catch(ReflectiveOperationException|RuntimeException|LinkageError e) { failed("Session listener",e.getClass().getName()); }
    }
    private static void registerFactory(Object factory) throws ReflectiveOperationException {
        if(FACTORIES.get(factory)!=null)return;
        ClassLoader loader=factory.getClass().getClassLoader();
        Class<?> registryApi=Class.forName("org.hibernate.event.service.spi.EventListenerRegistry",false,loader);
        Object registry=HibernateAccess.get(HibernateAccess.get(factory,"getServiceRegistry"),"getService",registryApi);
        Class<?> types=Class.forName("org.hibernate.event.spi.EventType",false,loader);
        for(String kind:List.of("LOAD","INSERT","UPDATE","DELETE")) {
            Class<?> api=Class.forName("org.hibernate.event.spi.Post"+kind.charAt(0)+kind.substring(1).toLowerCase(Locale.ROOT)+"EventListener",false,loader);
            Object listener=Proxy.newProxyInstance(loader,new Class<?>[]{api},(proxy,method,args)->{
                if(method.getName().startsWith("onPost")) entityEvent(kind,args[0]);
                if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
                if(method.getName().equals("equals"))return proxy==args[0];
                if(method.getName().equals("toString"))return "SB REPL Hibernate metadata listener";
                return method.getReturnType()==boolean.class?false:null;
            });
            Object type=types.getField("POST_"+kind).get(null);
            registryApi.getMethod("appendListeners",types,Object[].class).invoke(registry,type,new Object[]{listener});
        }
        FACTORIES.put(factory,true);
    }
    private static void entityEvent(String kind,Object event) {
        try {
            Object entity=HibernateAccess.get(event,"getEntity"),persister=HibernateAccess.get(event,"getPersister"),session=HibernateAccess.get(event,"getSession");
            HibernateAccess.observedEntity(entity,persister,session);
            String detail="";
            if(kind.equals("UPDATE")) {
                int[] dirty=(int[])HibernateAccess.get(event,"getDirtyProperties");String[] names=(String[])HibernateAccess.get(persister,"getPropertyNames");
                detail="Changed properties: "+(dirty==null?"unknown":String.join(", ",Arrays.stream(dirty).filter(i->i>=0&&i<names.length).mapToObj(i->names[i]).toList()));
            }
            Object token=begin(session,"ENTITY_"+kind,(String)HibernateAccess.get(persister,"getEntityName"),"",detail);exit(token,null,null);
        } catch(ReflectiveOperationException|RuntimeException|LinkageError e){markIncomplete();}
    }
    private static final class SessionListener implements InvocationHandler {
        private final WeakReference<Object> session;
        private final ThreadLocal<Map<String,ArrayDeque<Object>>> pairs=ThreadLocal.withInitial(HashMap::new);
        private static final Object NONE=new Object();
        SessionListener(Object session){this.session=new WeakReference<>(session);}
        public Object invoke(Object proxy,Method method,Object[] args) {
            String name=method.getName();
            try {
                if(name.equals("hashCode"))return System.identityHashCode(proxy);
                if(name.equals("equals"))return proxy==args[0];
                if(name.equals("toString"))return "SB REPL Hibernate session listener";
                if(name.equals("transactionCompletion")) {finish(begin(session.get(),"TRANSACTION","","",""),"successful="+args[0]);return null;}
                String base=name.replaceAll("(?:Start|End)$","");
                String kind=switch(base){case "flush"->"FLUSH";case "dirtyCalculation"->"DIRTY_CHECK";case "cacheGet"->"CACHE_MISS";case "cachePut"->"CACHE_PUT";default->null;};
                if(kind==null)return null;
                if(name.endsWith("Start")) {
                    Object token=begin(session.get(),kind,"","","");pairs.get().computeIfAbsent(base,k->new ArrayDeque<>()).push(token==null?NONE:token);
                } else {
                    Map<String,ArrayDeque<Object>> map=pairs.get();ArrayDeque<Object> stack=map.get(base);
                    Object token=stack==null||stack.isEmpty()?null:stack.pop();if(stack!=null&&stack.isEmpty())map.remove(base);if(map.isEmpty())pairs.remove();
                    if(token instanceof Frame f&&base.equals("cacheGet")&&Boolean.TRUE.equals(args[0]))
                        token=new Frame(f.previous(),f.tickets(),f.start(),f.wall(),f.session(),"CACHE_HIT",f.entity(),f.role(),f.detail(),f.source(),f.response());
                    finish(token,args==null?"":base.equals("flush")?"entities="+args[0]+", collections="+args[1]:base.equals("dirtyCalculation")?"dirty="+args[0]:"second-level cache access");
                }
            } catch(ReflectiveOperationException|RuntimeException|LinkageError e){markIncomplete();}
            return null;
        }
    }
    private static String bound(String s){return s.substring(0,Math.min(4096,s.length()));}
}
