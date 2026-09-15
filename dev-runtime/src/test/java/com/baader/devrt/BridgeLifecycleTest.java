package com.baader.devrt;

import com.baader.sbrepl.bridge.DevRuntimeBridgeConfig;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.support.GenericApplicationContext;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class BridgeLifecycleTest {
    @AfterEach void cleanup() { SpringContextHolder.set(null); }
    private static ApplicationReadyEvent ready(GenericApplicationContext context) {
        return new ApplicationReadyEvent(new SpringApplication(Object.class),new String[0],context,Duration.ZERO);
    }
    @Test void onlyTheOwnersReadyEventBindsAndClosingReleasesIt() {
        var bridge=new DevRuntimeBridgeConfig();
        try(var owner=new GenericApplicationContext();var child=new GenericApplicationContext()) {
            owner.refresh();child.refresh();bridge.setApplicationContext(owner);
            bridge.onApplicationEvent(new ContextRefreshedEvent(owner));assertNull(SpringContextHolder.get());
            bridge.onApplicationEvent(ready(child));assertNull(SpringContextHolder.get());
            bridge.onApplicationEvent(ready(owner));assertSame(owner,SpringContextHolder.get());assertSame(owner,DevRuntimeBridgeConfig.readyContext());
            bridge.onApplicationEvent(new ContextClosedEvent(child));assertSame(owner,SpringContextHolder.get());
            bridge.onApplicationEvent(new ContextClosedEvent(owner));assertNull(SpringContextHolder.get());assertNull(DevRuntimeBridgeConfig.readyContext());
        }
    }
    @Test void ClosingAnOldContextDoesNotClearItsReplacement() {
        var first=new DevRuntimeBridgeConfig();var second=new DevRuntimeBridgeConfig();
        try(var oldContext=new GenericApplicationContext();var newContext=new GenericApplicationContext()) {
            oldContext.refresh();newContext.refresh();first.setApplicationContext(oldContext);second.setApplicationContext(newContext);
            first.onApplicationEvent(ready(oldContext));second.onApplicationEvent(ready(newContext));
            first.onApplicationEvent(new ContextClosedEvent(oldContext));assertSame(newContext,SpringContextHolder.get());assertSame(newContext,DevRuntimeBridgeConfig.readyContext());
            second.onApplicationEvent(new ContextClosedEvent(newContext));assertNull(SpringContextHolder.get());
        }
    }
}
