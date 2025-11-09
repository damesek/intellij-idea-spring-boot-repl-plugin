package com.example.springboot.nrepl;

import org.springframework.context.ApplicationContext;

/**
 * Holds the Spring ApplicationContext for use inside REPL-evaluated code.
 */
public final class EvalEnvironment {
    private static volatile ApplicationContext applicationContext;

    private EvalEnvironment() {}

    public static void setApplicationContext(ApplicationContext ctx) {
        applicationContext = ctx;
    }

    public static ApplicationContext getApplicationContext() {
        return applicationContext;
    }
}

