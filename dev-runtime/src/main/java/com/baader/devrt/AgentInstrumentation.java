package com.baader.devrt;

import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import java.util.*;
import static net.bytebuddy.matcher.ElementMatchers.*;

/** Loaded with agent-private Byte Buddy classes; no Spring/SLF4J linkage in the agent bootstrap. */
public final class AgentInstrumentation {
    private static Instrumentation runtime;
    private static final Map<Class<?>, ResettableClassFileTransformer> TRACES = new HashMap<>();
    private static final Map<Class<?>, Set<String>> TRACE_METHODS = new HashMap<>();
    public static void install(Instrumentation instrumentation) {
        runtime = instrumentation;
        new AgentBuilder.Default().disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(named("org.springframework.boot.SpringApplication"))
                .transform((builder, type, loader, module, domain) -> builder.visit(Advice.to(Started.class)
                        .on(named("run").and(takesArguments(String[].class)).and(not(isStatic())))))
                .type(named("org.springframework.context.support.AbstractApplicationContext"))
                .transform((builder, type, loader, module, domain) -> builder.visit(Advice.to(Closed.class).on(named("doClose").and(takesArguments(0)))))
                .installOn(instrumentation);
        new AgentBuilder.Default().disableClassFormatChanges()
                // Mock/agent implementation classes can have deliberately unavailable supertypes.
                // They are not JDBC drivers; do not resolve their hierarchy in hasSuperType().
                .ignore(nameStartsWith("org.mockito.").or(nameStartsWith("net.bytebuddy."))
                        .or(nameStartsWith("com.baader.devrt.")).or(nameStartsWith("java."))
                        .or(nameStartsWith("jdk.")).or(nameStartsWith("sun.")).or(isSynthetic()))
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(new AgentBuilder.Listener.Adapter() {
                    @Override public void onError(String name, ClassLoader loader, net.bytebuddy.utility.JavaModule module, boolean loaded, Throwable failure) { SqlRecorder.failed(name,failure.getClass().getName()); }
                })
                .type(hasSuperType(named("java.sql.Statement")).and(not(isInterface())))
                .transform((builder,type,loader,module,domain) -> builder.visit(Advice.to(JdbcExecute.class).on(isMethod()
                        .and(namedOneOf("execute","executeQuery","executeUpdate","executeLargeUpdate","executeBatch","executeLargeBatch"))
                        .and(not(isAbstract())).and(not(isNative())))))
                .type(hasSuperType(named("java.sql.Connection")).and(not(isInterface())))
                .transform((builder,type,loader,module,domain) -> builder.visit(Advice.to(JdbcPrepare.class).on(isMethod()
                        .and(namedOneOf("prepareStatement","prepareCall","createStatement")).and(not(isAbstract())).and(not(isNative())))))
                .type(hasSuperType(named("javax.sql.DataSource")).and(not(isInterface())))
                .transform((builder,type,loader,module,domain) -> builder.visit(Advice.to(JdbcConnection.class).on(named("getConnection").and(not(isAbstract())).and(not(isNative())))))
                .type(named("org.springframework.web.servlet.DispatcherServlet"))
                .transform((builder,type,loader,module,domain) -> builder.visit(Advice.to(HttpDispatch.class).on(named("doDispatch").and(takesArguments(2)))))
                .installOn(instrumentation);
        SqlRecorder.installed();
        new AgentBuilder.Default().disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(new AgentBuilder.Listener.Adapter() {
                    @Override public void onError(String name,ClassLoader loader,net.bytebuddy.utility.JavaModule module,boolean loaded,Throwable error) { HibernateRecorder.failed(name,error.getClass().getName()); }
                })
                .type(named("org.hibernate.internal.SessionImpl"))
                .transform((builder,type,loader,module,domain)->builder.visit(Advice.to(HibernateSession.class).on(isConstructor()))
                        .visit(Advice.to(HibernateOperation.class).on(named("autoFlushIfRequired").and(takesArguments(2)))))
                .type(named("org.hibernate.internal.StatelessSessionImpl"))
                .transform((builder,type,loader,module,domain)->builder.visit(Advice.to(UnsupportedHibernateSession.class)
                        .on(isMethod().and(not(isAbstract())).and(not(isNative())))))
                .type(namedOneOf("org.hibernate.proxy.AbstractLazyInitializer","org.hibernate.collection.spi.AbstractPersistentCollection"))
                .transform((builder,type,loader,module,domain)->builder.visit(Advice.to(HibernateVoidOperation.class).on(named("initialize"))))
                .type(named("org.hibernate.bytecode.enhance.spi.interceptor.LazyAttributeLoadingInterceptor"))
                .transform((builder,type,loader,module,domain)->builder.visit(Advice.to(HibernateOperation.class).on(named("fetchAttribute"))))
                .type(named("org.hibernate.bytecode.enhance.spi.interceptor.EnhancementAsProxyLazinessInterceptor"))
                .transform((builder,type,loader,module,domain)->builder.visit(Advice.to(HibernateOperation.class).on(named("forceInitialize").and(takesArguments(4)))))
                .type(named("org.hibernate.engine.transaction.internal.TransactionImpl"))
                .transform((builder,type,loader,module,domain)->builder.visit(Advice.to(HibernateVoidOperation.class).on(namedOneOf("begin","commit","rollback").and(takesArguments(0)))))
                .type(named("org.hibernate.query.spi.AbstractSelectionQuery"))
                .transform((builder,type,loader,module,domain)->builder.visit(Advice.to(HibernateOperation.class).on(named("list").and(takesArguments(0)))))
                .type(named("org.hibernate.cache.internal.QueryResultsCacheImpl"))
                .transform((builder,type,loader,module,domain)->builder.visit(Advice.to(HibernateOperation.class).on(namedOneOf("get","put").and(takesArguments(3)))))
                .type(named("org.springframework.web.method.support.HandlerMethodReturnValueHandlerComposite"))
                .transform((builder,type,loader,module,domain)->builder.visit(Advice.to(ResponseHandling.class).on(named("handleReturnValue"))))
                .installOn(instrumentation);
        HibernateRecorder.installed();
        try {Class.forName("com.baader.devrt.bootstrap.AsyncBridge",false,null);installAsync(instrumentation);}
        catch(Exception|LinkageError failure){AsyncRecorder.failed();System.err.println("[sb-repl] Async instrumentation unavailable: "+failure.getClass().getSimpleName());}
    }
    private static void installAsync(Instrumentation instrumentation){
        java.util.concurrent.atomic.AtomicBoolean failed=new java.util.concurrent.atomic.AtomicBoolean();
        new AgentBuilder.Default().disableClassFormatChanges().ignore(nameStartsWith("net.bytebuddy."))
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(new AgentBuilder.Listener.Adapter(){@Override public void onError(String name,ClassLoader loader,net.bytebuddy.utility.JavaModule module,boolean loaded,Throwable error){failed.set(true);AsyncRecorder.failed();System.err.println("[sb-repl] Async capture unavailable for "+name+": "+error.getClass().getSimpleName());}})
            .type(named("java.util.concurrent.ThreadPoolExecutor"))
            .transform((builder,type,loader,module,domain)->builder
                .visit(Advice.to(AsyncSubmit.class).on(named("execute").and(takesArguments(Runnable.class))))
                .visit(Advice.to(AsyncBefore.class).on(named("beforeExecute")))
                .visit(Advice.to(AsyncAfter.class).on(named("afterExecute"))))
            .type(hasSuperType(named("java.util.concurrent.ThreadPoolExecutor")).and(not(named("java.util.concurrent.ThreadPoolExecutor"))))
            .transform((builder,type,loader,module,domain)->builder
                .visit(Advice.to(AsyncBefore.class).on(named("beforeExecute")))
                .visit(Advice.to(AsyncAfter.class).on(named("afterExecute"))))
            .type(named("java.util.concurrent.ForkJoinPool"))
            .transform((builder,type,loader,module,domain)->builder
                .visit(Advice.to(AsyncSubmit.class).on(named("externalSubmit").and(isPrivate()).and(takesArguments(1))))
                .visit(Advice.to(ForkSubmit.class).on(named("poolSubmit").and(takesArguments(2)))))
            .type(named("java.util.concurrent.ForkJoinTask"))
            .transform((builder,type,loader,module,domain)->builder
                .visit(Advice.to(AsyncFork.class).on(named("fork").and(takesArguments(0))))
                .visit(Advice.to(AsyncExecute.class).on(named("doExec").and(takesArguments(0)))))
            .type(named("org.springframework.core.task.SimpleAsyncTaskExecutor"))
            .transform((builder,type,loader,module,domain)->builder.visit(Advice.to(SimpleAsyncExecute.class).on(named("doExecute").and(takesArguments(Runnable.class)))))
            .installOn(instrumentation);
        if(!failed.get())AsyncRecorder.installed();
    }
    public static final class AsyncSubmit {
        @Advice.OnMethodEnter(suppress=Throwable.class) public static void enter(@Advice.Argument(0) Object task){com.baader.devrt.bootstrap.AsyncBridge.event(0,task,null);}
        @Advice.OnMethodExit(onThrowable=Throwable.class,suppress=Throwable.class) public static void exit(@Advice.Argument(0) Object task,@Advice.Thrown Throwable error){if(error!=null)com.baader.devrt.bootstrap.AsyncBridge.event(3,task,error);}
    }
    public static final class ForkSubmit {
        @Advice.OnMethodEnter(suppress=Throwable.class) public static void enter(@Advice.Argument(1) Object task){com.baader.devrt.bootstrap.AsyncBridge.event(0,task,null);}
        @Advice.OnMethodExit(onThrowable=Throwable.class,suppress=Throwable.class) public static void exit(@Advice.Argument(1) Object task,@Advice.Thrown Throwable error){if(error!=null)com.baader.devrt.bootstrap.AsyncBridge.event(3,task,error);}
    }
    public static final class AsyncFork {
        @Advice.OnMethodEnter(suppress=Throwable.class) public static void enter(@Advice.This Object task){com.baader.devrt.bootstrap.AsyncBridge.event(0,task,null);}
    }
    public static final class AsyncBefore {
        @Advice.OnMethodExit(suppress=Throwable.class) public static void exit(@Advice.Argument(1) Object task){com.baader.devrt.bootstrap.AsyncBridge.event(1,task,null);}
    }
    public static final class AsyncAfter {
        @Advice.OnMethodExit(onThrowable=Throwable.class,suppress=Throwable.class) public static void exit(@Advice.Argument(0) Object task,@Advice.Argument(1) Throwable error){com.baader.devrt.bootstrap.AsyncBridge.event(2,task,error);}
    }
    public static final class AsyncExecute {
        @Advice.OnMethodEnter(suppress=Throwable.class) public static Object enter(@Advice.This Object task){return com.baader.devrt.bootstrap.AsyncBridge.event(1,task,null);}
        @Advice.OnMethodExit(onThrowable=Throwable.class,suppress=Throwable.class) public static void exit(@Advice.This Object task,@Advice.Enter Object frame,@Advice.Thrown Throwable error){if(frame!=null)com.baader.devrt.bootstrap.AsyncBridge.event(2,task,error);}
    }
    public static final class SimpleAsyncExecute {
        @Advice.OnMethodEnter(suppress=Throwable.class) public static void enter(@Advice.Argument(value=0,readOnly=false) Runnable task){Object wrapped=com.baader.devrt.bootstrap.AsyncBridge.event(4,task,null);if(wrapped instanceof Runnable)task=(Runnable)wrapped;}
    }
    public static final class HibernateSession {
        @Advice.OnMethodExit(suppress=Throwable.class)
        public static void exit(@Advice.This Object session){HibernateRecorder.sessionCreated(session);}
    }
    public static final class UnsupportedHibernateSession {
        @Advice.OnMethodEnter(suppress=Throwable.class)
        public static void enter(){HibernateRecorder.unsupportedSession();}
    }
    public static final class HibernateOperation {
        @Advice.OnMethodEnter(suppress=Throwable.class)
        public static Object enter(@Advice.This Object target,@Advice.Origin("#m") String operation,@Advice.AllArguments Object[] args){return HibernateRecorder.enter(target,operation,args);}
        @Advice.OnMethodExit(onThrowable=Throwable.class,suppress=Throwable.class)
        public static void exit(@Advice.Enter Object token,@Advice.Return(typing=Assigner.Typing.DYNAMIC) Object result,@Advice.Thrown Throwable error){HibernateRecorder.exit(token,result,error);}
    }
    public static final class HibernateVoidOperation {
        @Advice.OnMethodEnter(suppress=Throwable.class)
        public static Object enter(@Advice.This Object target,@Advice.Origin("#m") String operation,@Advice.AllArguments Object[] args){return HibernateRecorder.enter(target,operation,args);}
        @Advice.OnMethodExit(onThrowable=Throwable.class,suppress=Throwable.class)
        public static void exit(@Advice.Enter Object token,@Advice.Thrown Throwable error){HibernateRecorder.exit(token,null,error);}
    }
    public static final class ResponseHandling {
        @Advice.OnMethodEnter(suppress=Throwable.class)
        public static void enter(){TraceRecorder.responseStarted();}
    }
    public static final class JdbcExecute {
        @Advice.OnMethodEnter(suppress=Throwable.class)
        public static Object enter(@Advice.This Object statement,@Advice.Origin("#m") String method,@Advice.AllArguments Object[] args) { return SqlRecorder.enter(statement,method,args); }
        @Advice.OnMethodExit(onThrowable=Throwable.class,suppress=Throwable.class)
        public static void exit(@Advice.Enter Object token,@Advice.Thrown Throwable error) { SqlRecorder.exit(token,error); }
    }
    public static final class JdbcPrepare {
        @Advice.OnMethodExit(suppress=Throwable.class)
        public static void exit(@Advice.This Object connection,@Advice.AllArguments Object[] args,@Advice.Return(typing=Assigner.Typing.DYNAMIC) Object statement) { SqlRecorder.prepared(connection,statement,args); }
    }
    public static final class JdbcConnection {
        @Advice.OnMethodEnter(suppress=Throwable.class)
        public static Object enter() { return SqlRecorder.connectionEnter(); }
        @Advice.OnMethodExit(onThrowable=Throwable.class,suppress=Throwable.class)
        public static void exit(@Advice.Enter Object token,@Advice.This Object ds,@Advice.Return(typing=Assigner.Typing.DYNAMIC) Object connection,@Advice.Thrown Throwable error) { SqlRecorder.connectionExit(token,ds,connection,error); }
    }
    public static final class HttpDispatch {
        @Advice.OnMethodEnter(suppress=Throwable.class)
        public static Object enter(@Advice.Argument(0) Object request) { return TraceRecorder.httpEnter(request); }
        @Advice.OnMethodExit(onThrowable=Throwable.class,suppress=Throwable.class)
        public static void exit(@Advice.Enter Object token,@Advice.Thrown Throwable error) { TraceRecorder.httpExit(token,error); }
    }
    public static synchronized void configureTrace(Class<?> type, Set<String> methods) {
        // A queued stop cleanup may run after a new recording starts. Do not briefly uninstrument
        // an unchanged live class by resetting and reinstalling exactly the same advice.
        if (methods.equals(TRACE_METHODS.getOrDefault(type, Set.of()))) return;
        if (runtime == null || !runtime.isRetransformClassesSupported() || !runtime.isModifiableClass(type))
            throw new IllegalStateException("Class cannot be instrumented in this JVM");
        ResettableClassFileTransformer previous = TRACES.get(type);
        if (previous != null && !previous.reset(runtime, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION))
            throw new IllegalStateException("Cannot remove previous trace transformer");
        TRACES.remove(type);
        TRACE_METHODS.remove(type);
        if (methods.isEmpty()) return;
        java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();
        ResettableClassFileTransformer transformer = new AgentBuilder.Default().disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(new AgentBuilder.Listener.Adapter() {
                    @Override public void onError(String name, ClassLoader loader, net.bytebuddy.utility.JavaModule module, boolean loaded, Throwable failure) { error.set(failure); }
                })
                .type(named(type.getName()), is(type.getClassLoader()))
                .transform((builder, description, loader, module, domain) -> builder.visit(Advice.to(Traced.class)
                        .on(isMethod().and(namedOneOf(methods.toArray(String[]::new))).and(not(isAbstract())).and(not(isNative())).and(not(isSynthetic())))))
                .installOn(runtime);
        if (error.get() != null) {
            transformer.reset(runtime, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
            throw new IllegalStateException("Trace instrumentation failed", error.get());
        }
        TRACES.put(type, transformer);
        TRACE_METHODS.put(type, Set.copyOf(methods));
    }
    public static final class Traced {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static Object enter(@Advice.Origin Class<?> type, @Advice.Origin("#m") String method, @Advice.Origin("#d") String descriptor, @Advice.AllArguments Object[] args) {
            return TraceRecorder.enter(type, method, descriptor, args);
        }
        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Advice.Enter Object ticket, @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object result, @Advice.Thrown Throwable error) {
            TraceRecorder.exit(ticket, result, error);
        }
    }
    public static final class Started {
        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void exit(@Advice.Return(typing = Assigner.Typing.DYNAMIC) Object context) {
            if (context != null) SpringContextHolder.set(context);
        }
    }
    public static final class Closed {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void enter(@Advice.This Object context) { SpringContextHolder.clear(context); }
    }
}
