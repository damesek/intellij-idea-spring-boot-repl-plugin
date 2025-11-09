package com.baader.devrt;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SnapshotStore {
    enum Mode { LIVE, JSON }
    static final class Meta {
        final String name; final String type; final long ts; final long approxSize; final Mode mode;
        Meta(String n, String t, long ts, long sz, Mode m){this.name=n;this.type=t;this.ts=ts;this.approxSize=sz;this.mode=m;}
    }

    private static final Map<String,Object> live = new ConcurrentHashMap<>();
    private static final Map<String,String> json = new ConcurrentHashMap<>();
    private static final Map<String,Meta> meta = new ConcurrentHashMap<>();

    public static void pin(String name, Object obj){
        live.put(name, obj);
        meta.put(name, new Meta(name, obj!=null?obj.getClass().getName():"null", Instant.now().toEpochMilli(), estimate(obj), Mode.LIVE));
    }
    public static Object get(String name){ return live.get(name); }

    public static void saveJson(String name, Object obj){
        String j;
        if (obj instanceof String) {
            j = (String) obj; // assume JSON string provided by expr
        } else {
            j = toJson(obj);
        }
        json.put(name, j);
        meta.put(name, new Meta(name, obj!=null?obj.getClass().getName():"null", Instant.now().toEpochMilli(), j!=null?j.length():0, Mode.JSON));
    }
    public static String getJson(String name){ return json.get(name); }

    public static String listAsTsv(){
        StringBuilder sb = new StringBuilder();
        for (Meta m : meta.values()){
            sb.append(m.name).append('\t')
              .append(m.type).append('\t')
              .append(m.mode).append('\t')
              .append(m.ts).append('\t')
              .append(m.approxSize).append('\n');
        }
        return sb.toString();
    }

    public static void delete(String name){ live.remove(name); json.remove(name); meta.remove(name); }

    private static long estimate(Object o){ return 0L; }

    private static String toJson(Object obj){
        if (obj == null) return "null";
        // Try Jackson via app class loader
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) cl = SnapshotStore.class.getClassLoader();
            Class<?> om = Class.forName("com.fasterxml.jackson.databind.ObjectMapper", true, cl);
            Object mapper = om.getConstructor().newInstance();
            // enable pretty? skip for size
            Method write = om.getMethod("writeValueAsString", Object.class);
            return (String) write.invoke(mapper, obj);
        } catch (Throwable ignored) {}
        // Fallback
        return String.valueOf(obj);
    }
}
