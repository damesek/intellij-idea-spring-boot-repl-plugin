package com.baader.devrt;

import java.lang.instrument.Instrumentation;

final class AgentRuntime {
    private static volatile Instrumentation inst;

    static void setInstrumentation(Instrumentation i) { inst = i; }
    static Instrumentation getInstrumentation() { return inst; }
}

