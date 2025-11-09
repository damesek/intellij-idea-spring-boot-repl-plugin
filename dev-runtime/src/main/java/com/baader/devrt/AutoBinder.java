package com.baader.devrt;

import javax.management.MBeanServer;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;

final class AutoBinder {

    static void scheduleAutoBind() {
        Thread t = new Thread(() -> {
            for (int i = 0; i < 20; i++) {
                try {
                    if (tryBindOnce()) {
                        System.out.println("[dev-runtime] auto-bind: ctx set");
                        return;
                    }
                } catch (Throwable ignored) {}
                try { Thread.sleep(1000); } catch (InterruptedException ignored) { return; }
            }
            System.out.println("[dev-runtime] auto-bind: ctx not found");
        }, "dev-runtime-autobind");
        t.setDaemon(true);
        t.start();
    }

    static boolean tryBindOnce() {
        // Already set?
        if (SpringContextHolder.get() != null) return true;
        // 1) ContextLoader
        Object ctx = tryContextLoader();
        if (ctx != null) return setHolder(ctx);
        // 2) LiveBeansView (legacy approach)
        ctx = tryLiveBeansViewStaticSet();
        if (ctx != null) return setHolder(ctx);
        // 3) Scan static fields for ApplicationContext
        ctx = tryScanStaticFields();
        if (ctx != null) return setHolder(ctx);
        // 4) JMX MBean presence check (no direct ctx, but signals Spring is up)
        // Leave holder unset in this step, used only for logging/heuristic
        tryJmxPing();
        return false;
    }

    private static boolean setHolder(Object ctx) {
        try {
            SpringContextHolder.set(ctx);
            return SpringContextHolder.get() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object tryContextLoader() {
        try {
            Class<?> cl = Class.forName("org.springframework.web.context.ContextLoader");
            Method m = cl.getMethod("getCurrentWebApplicationContext");
            Object ctx = m.invoke(null);
            System.out.println("[auto-bind] ContextLoader: " + (ctx != null));
            return ctx;
        } catch (Throwable ignored) { return null; }
    }

    private static Object tryLiveBeansViewStaticSet() {
        try {
            Class<?> lv = Class.forName("org.springframework.context.support.LiveBeansView");
            try {
                Field f = lv.getDeclaredField("applicationContexts");
                f.setAccessible(true);
                Object coll = f.get(null);
                if (coll instanceof Set) {
                    Set<?> s = (Set<?>) coll;
                    Object ctx = s.isEmpty() ? null : s.iterator().next();
                    System.out.println("[auto-bind] LiveBeansView static set: " + (ctx != null));
                    return ctx;
                }
            } catch (NoSuchFieldException nsf) {
                // Newer Spring may not have this; ignore
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Object tryScanStaticFields() {
        Instrumentation inst = AgentRuntime.getInstrumentation();
        if (inst == null) return null;
        try {
            Class<?>[] all = inst.getAllLoadedClasses();
            for (Class<?> c : all) {
                String cn = c.getName();
                if (cn.startsWith("java.") || cn.startsWith("jdk.") || cn.startsWith("sun.")) continue;
                try {
                    Field[] fs = c.getDeclaredFields();
                    for (Field f : fs) {
                        int mod = f.getModifiers();
                        if (!java.lang.reflect.Modifier.isStatic(mod)) continue;
                        f.setAccessible(true);
                        Object v = null;
                        try { v = f.get(null); } catch (Throwable ignored) {}
                        if (v != null && isApplicationContextLike(v.getClass())) {
                            System.out.println("[auto-bind] static field instance hit: " + cn + "." + f.getName());
                            return v;
                        }
                        // If value is null, still check the declared type name to log
                        if (v == null && isApplicationContextLikeTypeName(f.getType())) {
                            // can't bind null, continue
                        }
                    }
                    // Also try common static getters with zero args
                    Object viaGetter = tryStaticGetterReturningCtx(c);
                    if (viaGetter != null) return viaGetter;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean isApplicationContextLikeTypeName(Class<?> t) {
        String tn = t.getName();
        return tn.equals("org.springframework.context.ApplicationContext") || tn.endsWith("ApplicationContext");
    }

    private static boolean isApplicationContextLike(Class<?> c) {
        // Walk interfaces and superclasses by name
        if (c == null) return false;
        if (c.getName().equals("org.springframework.context.ApplicationContext")) return true;
        for (Class<?> i : safeInterfaces(c)) if (isApplicationContextLike(i)) return true;
        return isApplicationContextLike(c.getSuperclass());
    }

    private static Class<?>[] safeInterfaces(Class<?> c) {
        try { return c.getInterfaces(); } catch (Throwable t) { return new Class<?>[0]; }
    }

    private static Object tryStaticGetterReturningCtx(Class<?> c) {
        try {
            Method[] ms = c.getDeclaredMethods();
            for (Method m : ms) {
                int mod = m.getModifiers();
                if (!java.lang.reflect.Modifier.isStatic(mod)) continue;
                if (m.getParameterCount() != 0) continue;
                String mn = m.getName();
                if (!(mn.equals("getApplicationContext") || mn.equals("applicationContext") || mn.equals("context") || mn.equals("getContext"))) continue;
                try {
                    m.setAccessible(true);
                    Object v = m.invoke(null);
                    if (v != null && isApplicationContextLike(v.getClass())) {
                        System.out.println("[auto-bind] static getter hit: " + c.getName() + "." + mn);
                        return v;
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void tryJmxPing() {
        try {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            // Nothing to do here for ctx, but presence may help later heuristics
            // e.g., search for ObjectNames containing "spring" if needed
        } catch (Throwable ignored) {}
    }
}
