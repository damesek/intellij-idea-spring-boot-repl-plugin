package com.baader.devrt;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ReplBindings {
    private record Binding(Object context, Object value) {}
    private static final ThreadLocal<Binding> CURRENT = new ThreadLocal<>();
    private static final Map<String, Object> NAMED = new ConcurrentHashMap<>();
    public static Object applicationContext() { Binding current = CURRENT.get(); return current == null ? SpringContextHolder.get() : current.context(); }
    public static void setApplicationContext(Object ctx) { SpringContextHolder.set(ctx); }
    public static Object transfer() { Binding current = CURRENT.get(); return current == null ? null : current.value(); }
    static Scope enter(Object context, Object value) { Binding previous = CURRENT.get(); CURRENT.set(new Binding(context, value)); return () -> { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }; }
    static Scope enterContext(Object context) { Binding current = CURRENT.get(); return enter(context, current == null ? null : current.value()); }
    interface Scope extends AutoCloseable { @Override void close(); }
    public static void put(String name, Object value) { if (value == null) NAMED.remove(name); else { if (NAMED.size() >= 100 && !NAMED.containsKey(name)) throw new IllegalStateException("Binding limit exceeded"); NAMED.put(name, value); } }
    public static Object get(String name) { return NAMED.get(name); }
    public static Map<String, Object> snapshot() { return Map.copyOf(NAMED); }
    static void clear() { NAMED.clear(); }
    private ReplBindings() {}
}
