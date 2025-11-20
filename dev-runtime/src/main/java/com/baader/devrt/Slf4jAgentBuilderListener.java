package com.baader.devrt;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Slf4jAgentBuilderListener extends AgentBuilder.Listener.Adapter {

    private final Logger logger = LoggerFactory.getLogger(Slf4jAgentBuilderListener.class);

    @Override
    public void onDiscovery(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
        logger.trace("[Byte Buddy] DISCOVERY: {}", typeName);
    }

    @Override
    public void onTransformation(TypeDescription typeDescription, ClassLoader classLoader, JavaModule module, boolean loaded, DynamicType dynamicType) {
        logger.info("[Byte Buddy] TRANSFORM: {}", typeDescription.getName());
    }

    @Override
    public void onIgnored(TypeDescription typeDescription, ClassLoader classLoader, JavaModule module, boolean loaded) {
        logger.trace("[Byte Buddy] IGNORE: {}", typeDescription.getName());
    }

    @Override
    public void onError(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded, Throwable throwable) {
        logger.error("[Byte Buddy] ERROR for type {}: {}", typeName, throwable.getMessage(), throwable);
    }

    @Override
    public void onComplete(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
        logger.trace("[Byte Buddy] COMPLETE: {}", typeName);
    }
}
