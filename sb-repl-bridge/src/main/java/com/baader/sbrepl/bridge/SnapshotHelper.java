package com.baader.sbrepl.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * Lightweight wrapper around the dev-runtime SnapshotManager so Spring-side
 * code can save/load snapshots without dealing with reflection.
 */
public final class SnapshotHelper {

    private static final Logger log = LoggerFactory.getLogger(SnapshotHelper.class);
    private static final Method STORE_PIN = resolve("com.baader.devrt.SnapshotStore", "pin", String.class, Object.class);
    private static final Method STORE_GET = resolve("com.baader.devrt.SnapshotStore", "get", String.class);
    private static final Method MANAGER_SAVE = resolve("com.baader.devrt.SnapshotManager", "save", String.class, Object.class);
    private static final Method MANAGER_LOAD = resolve("com.baader.devrt.SnapshotManager", "load", String.class);

    private SnapshotHelper() {}

    public static void save(String name, Object value) {
        boolean stored = invokeVoid(STORE_PIN, name, value);
        boolean persisted = invokeVoid(MANAGER_SAVE, name, value);
        if (!stored && !persisted) {
            log.warn("Snapshot '{}' could not be saved (store/manager unavailable)", name);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T load(String name) {
        Object fromStore = invoke(STORE_GET, name);
        if (fromStore != null) {
            return (T) fromStore;
        }
        return (T) invoke(MANAGER_LOAD, name);
    }

    private static Object invoke(Method method, Object... args) {
        if (method == null) return null;
        try {
            return method.invoke(null, args);
        } catch (Exception e) {
            log.warn("Snapshot operation failed via method {}", method.getName(), e);
            return null;
        }
    }

    private static boolean invokeVoid(Method method, Object... args) {
        if (method == null) return false;
        try {
            method.invoke(null, args);
            return true;
        } catch (Exception e) {
            log.warn("Snapshot operation failed via method {}", method.getName(), e);
            return false;
        }
    }

    private static Method resolve(String className, String method, Class<?>... types) {
        try {
            Class<?> clazz = Class.forName(className);
            return clazz.getMethod(method, types);
        } catch (Exception e) {
            log.warn("{}#{} not available", className, method, e);
            return null;
        }
    }
}
