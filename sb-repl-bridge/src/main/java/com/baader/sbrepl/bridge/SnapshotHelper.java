package com.baader.sbrepl.bridge;

import java.lang.reflect.InvocationTargetException;

/** Uses one canonical repository. Failures are explicit and late attachment is supported. */
public final class SnapshotHelper {
    private SnapshotHelper() {}
    public static void pin(String name, Object value) { invoke("pin", new Class<?>[]{String.class, Object.class}, name, value); }
    public static void save(String name, Object value) { invoke("save", new Class<?>[]{String.class, Object.class}, name, value); }
    public static void save(String name, Object value, String declaredType) { invoke("save", new Class<?>[]{String.class, Object.class, String.class}, name, value, declaredType); }
    @SuppressWarnings("unchecked")
    public static <T> T load(String name) { return (T) invoke("load", new Class<?>[]{String.class}, name); }
    public static void delete(String name) { invoke("delete", new Class<?>[]{String.class}, name); }
    /** Publish a live value to explicitly subscribed REPL sessions. No serialization or disk write. */
    public static boolean tap(String label, Object value) {
        try {
            return Boolean.TRUE.equals(Class.forName("com.baader.devrt.RuntimeEvents", true, ClassLoader.getSystemClassLoader())
                    .getMethod("tap", String.class, Object.class).invoke(null, label, value));
        } catch (ReflectiveOperationException absentAgent) { return false; }
    }
    /** Capture only the next armed matching call. An absent agent or unarmed trigger returns false. */
    public static boolean capture(String point, Object value) { return capture(point, "", value); }
    public static boolean capture(String point, String caseId, Object value) { return captureLazy(point, caseId, () -> value); }
    /** Explicit tenant/flag/request metadata. Secrets with recognized field names are redacted by the agent. */
    public static boolean captureLazy(String point, String caseId, java.util.function.Supplier<?> value, java.util.Map<String,String> metadata) {
        try {
            return Boolean.TRUE.equals(Class.forName("com.baader.devrt.SnapshotManager", true, ClassLoader.getSystemClassLoader())
                    .getMethod("capture", String.class, String.class, java.util.function.Supplier.class, java.util.Map.class).invoke(null, point, caseId, value, metadata));
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Error error) throw error;
            return false;
        } catch (ReflectiveOperationException absentAgent) { return false; }
    }
    /** The supplier is invoked synchronously, once, only after the capture slot is claimed. */
    public static boolean captureLazy(String point, String caseId, java.util.function.Supplier<?> value) {
        try {
            return Boolean.TRUE.equals(Class.forName("com.baader.devrt.SnapshotManager", true, ClassLoader.getSystemClassLoader())
                    .getMethod("capture", String.class, String.class, java.util.function.Supplier.class).invoke(null, point, caseId, value));
        } catch (ClassNotFoundException | NoSuchMethodException absentAgent) { return false; }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Error error) throw error;
            return false;
        } catch (ReflectiveOperationException failure) { return false; }
    }
    private static Object invoke(String method, Class<?>[] signature, Object... args) {
        try {
            return Class.forName("com.baader.devrt.SnapshotManager", true, ClassLoader.getSystemClassLoader())
                .getMethod(method, signature).invoke(null, args);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException exception) throw exception;
            throw new IllegalStateException("Snapshot operation failed", failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Matching sb-repl agent is not available; attach it first", failure);
        }
    }
}
