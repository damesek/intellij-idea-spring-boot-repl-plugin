package com.baader.devrt;

/**
 * Minimal holder shared between the sb-repl agent and the host Spring
 * application. This copy ensures both sides see the exact same FQN.
 */
public final class SpringContextHolder {
    private static volatile Object applicationContext;

    private SpringContextHolder() {}

    public static void set(Object ctx) {
        applicationContext = ctx;
    }

    public static Object get() {
        return applicationContext;
    }
}
