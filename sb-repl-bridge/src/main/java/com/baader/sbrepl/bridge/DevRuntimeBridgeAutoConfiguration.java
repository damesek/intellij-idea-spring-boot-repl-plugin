package com.baader.sbrepl.bridge;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Registers {@link DevRuntimeBridgeConfig} automatically when the sb-repl agent
 * classes are present and bridge support is enabled.
 */
@AutoConfiguration
@ConditionalOnClass(name = "com.baader.devrt.SpringContextHolder")
@ConditionalOnProperty(prefix = "sb.repl.bridge", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DevRuntimeBridgeAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DevRuntimeBridgeConfig devRuntimeBridgeConfig() {
        return new DevRuntimeBridgeConfig();
    }
}
