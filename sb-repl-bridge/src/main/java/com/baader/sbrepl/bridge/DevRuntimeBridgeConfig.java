package com.baader.sbrepl.bridge;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.*;
import org.springframework.context.event.ContextClosedEvent;

/** Registers only a fully started context; also supports attaching the agent after startup. */
public final class DevRuntimeBridgeConfig implements ApplicationContextAware, ApplicationListener<ApplicationEvent> {
    private static volatile ApplicationContext ready;
    private ApplicationContext owner;
    @Override public void setApplicationContext(ApplicationContext context) { owner = context; }
    public static ApplicationContext readyContext() { return ready; }
    @Override public void onApplicationEvent(ApplicationEvent event) {
        if (event instanceof ApplicationReadyEvent started && started.getApplicationContext() == owner) {
            ready = owner;
            publish("set", owner);
        } else if (event instanceof ContextClosedEvent closed && closed.getApplicationContext() == owner) {
            if (ready == owner) ready = null;
            publish("clear", owner);
            owner = null;
        }
    }
    private static void publish(String method, Object context) {
        try {
            Class.forName("com.baader.devrt.SpringContextHolder", false, ClassLoader.getSystemClassLoader())
                .getMethod(method, Object.class).invoke(null, context);
        } catch (ClassNotFoundException ignored) {
            // Agent may be attached later; readyContext remains available to its explicit bridge lookup.
        } catch (ReflectiveOperationException failure) {
            org.slf4j.LoggerFactory.getLogger(DevRuntimeBridgeConfig.class).warn("REPL context bridge failed", failure);
        }
    }
}
