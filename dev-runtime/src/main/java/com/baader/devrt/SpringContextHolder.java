package com.baader.devrt;

public final class SpringContextHolder {
    private static volatile Object applicationContext; // avoid hard Spring dep

    private SpringContextHolder() {}

    public static void set(Object ctx) { applicationContext = ctx; }
    public static Object get() { return applicationContext; }
}

