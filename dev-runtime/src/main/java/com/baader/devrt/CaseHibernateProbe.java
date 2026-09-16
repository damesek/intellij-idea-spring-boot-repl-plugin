package com.baader.devrt;

import java.lang.reflect.*;
import java.util.Map;

/** Portable JUnit bridge: a missing or incompatible agent fails before CASE code. */
public final class CaseHibernateProbe implements AutoCloseable {
    private final AutoCloseable scope;
    public CaseHibernateProbe() {
        try {
            Class<?> adapter=Class.forName("com.baader.devrt.HibernateRecorder",true,ClassLoader.getSystemClassLoader());
            scope=(AutoCloseable)adapter.getMethod("openCase").invoke(null);
        } catch(ReflectiveOperationException|LinkageError e) {
            throw new IllegalStateException("Hibernate CASE assertions require the matching sb-repl -javaagent and Hibernate 6.6; see the exported README",e);
        }
    }
    public void close() throws Exception { scope.close(); }
    public void assertLimits(long loads,long flushes,long lazy,long responseLazy) throws ReflectiveOperationException {
        @SuppressWarnings("unchecked") Map<String,Object> metrics=(Map<String,Object>)scope.getClass().getMethod("metrics").invoke(scope);
        for(var entry:Map.of("loads",loads,"flushes",flushes,"lazy",lazy,"responseLazy",responseLazy).entrySet()) {
            long actual=((Number)metrics.get(entry.getKey())).longValue();
            if(entry.getValue()>=0&&actual>entry.getValue())throw new AssertionError("Hibernate "+entry.getKey()+" budget exceeded: "+actual+" > "+entry.getValue());
        }
        if(!Boolean.TRUE.equals(metrics.get("available"))||Boolean.TRUE.equals(metrics.get("partial")))throw new AssertionError("Hibernate evidence is incomplete; an upper bound cannot pass");
    }
}
