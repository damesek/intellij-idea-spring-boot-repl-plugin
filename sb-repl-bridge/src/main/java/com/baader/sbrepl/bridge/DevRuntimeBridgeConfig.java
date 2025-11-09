package com.baader.sbrepl.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.lang.reflect.Method;

/**
 * Bridges the running Spring {@link ApplicationContext} into the sb-repl agent's
 * {@code com.baader.devrt.SpringContextHolder}. Once the agent attaches, beans
 * are immediately available inside the REPL without manual reflection hacks.
 */
public class DevRuntimeBridgeConfig implements ApplicationContextAware {

    private static final Logger log = LoggerFactory.getLogger(DevRuntimeBridgeConfig.class);
    private static final String HOLDER_FQN = "com.baader.devrt.SpringContextHolder";
    private volatile boolean applied;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        if (applicationContext == null) {
            return;
        }
        if (applied) {
            return;
        }
        applied = bridgeContext(applicationContext);
    }

    boolean bridgeContext(ApplicationContext applicationContext) {
        try {
            Class<?> holder = Class.forName(HOLDER_FQN);
            Method setter = holder.getMethod("set", Object.class);
            setter.invoke(null, applicationContext);
            log.info("sb-repl agent detected – Spring context bridged successfully");
            return true;
        } catch (ClassNotFoundException ex) {
            log.debug("sb-repl agent not found on classpath – skipping context bridge");
        } catch (Throwable ex) {
            log.warn("Failed to bridge Spring context to sb-repl agent", ex);
        }
        return false;
    }
}
