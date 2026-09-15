package com.baader.devrt;

import java.util.concurrent.atomic.AtomicLong;

public final class SpringContextHolder {
    private static volatile Object context;
    private static final AtomicLong epoch = new AtomicLong();
    private static final java.util.concurrent.CopyOnWriteArrayList<Runnable> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private SpringContextHolder() {}
    public static synchronized void set(Object value) {
        if (value == context) return;
        context = value; epoch.incrementAndGet(); ReplBindings.clear(); SnapshotStore.clearLive();
        for (Runnable listener : listeners) listener.run();
    }
    public static Object get() { return context; }
    public static long epoch() { return epoch.get(); }
    static ReplBindings.Scope observe(Runnable listener) { listeners.add(listener); return () -> listeners.remove(listener); }
    public static synchronized void clear(Object closing) { if (context == closing) set(null); }
}
