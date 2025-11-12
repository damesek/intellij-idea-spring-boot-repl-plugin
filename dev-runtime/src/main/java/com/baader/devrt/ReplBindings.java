package com.baader.devrt;

import org.springframework.context.ApplicationContext;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ReplBindings {
    private static volatile Object applicationContext;
    private static final Map<String, Object> NAMED = new ConcurrentHashMap<>();

    public static Object applicationContext() { return applicationContext; }
    public static void setApplicationContext(Object ctx) { applicationContext = ctx; }

    public static void put(String name, Object value) { NAMED.put(name, value); }
    public static Object get(String name) { return NAMED.get(name); }
    public static Map<String, Object> snapshot() { return Collections.unmodifiableMap(NAMED); }
    
    private ReplBindings() {}
}