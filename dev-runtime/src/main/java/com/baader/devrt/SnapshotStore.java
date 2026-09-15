package com.baader.devrt;

/** Compatibility facade. Storage and lifetime are owned only by SnapshotManager. */
public final class SnapshotStore {
    private SnapshotStore() {}
    public static void pin(String name, Object value) { SnapshotManager.pin(name, value); }
    public static Object get(String name) { return SnapshotManager.load(name); }
    public static void saveJson(String name, Object value) {
        if (value instanceof String json) SnapshotManager.importJson(name, json);
        else SnapshotManager.save(name, value);
    }
    public static String getJson(String name) { return SnapshotManager.json(name); }
    public static String listAsTsv() { return String.join("\n", SnapshotManager.list()); }
    public static void delete(String name) { SnapshotManager.delete(name); }
    static void clearLive() { SnapshotManager.clearLive(); }
}
