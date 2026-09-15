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
    }
    public static synchronized void configureTrace(Class<?> type, Set<String> methods) {
        if (runtime == null || !runtime.isRetransformClassesSupported() || !runtime.isModifiableClass(type))
            throw new IllegalStateException("Class cannot be instrumented in this JVM");
        ResettableClassFileTransformer previous = TRACES.get(type);
        if (previous != null && !previous.reset(runtime, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION))
            throw new IllegalStateException("Cannot remove previous trace transformer");
        TRACES.remove(type);
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
