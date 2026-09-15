package com.baader.devrt;

/** Late attach can discover the explicit bridge, never arbitrary application static fields/getters. */
final class AutoBinder {
    static boolean tryBindOnce() {
        if (SpringContextHolder.get() != null) return true;
        var instrumentation = AgentRuntime.getInstrumentation();
        if (instrumentation == null) return false;
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            if (!type.getName().equals("com.baader.sbrepl.bridge.DevRuntimeBridgeConfig")) continue;
            try {
                Object context = type.getMethod("readyContext").invoke(null);
                if (context != null) { SpringContextHolder.set(context); return true; }
            } catch (ReflectiveOperationException ignored) {}
        }
        return false;
    }
}
