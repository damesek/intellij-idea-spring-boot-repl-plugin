package com.baader.devrt;

import net.bytebuddy.asm.Advice;
import org.springframework.context.ConfigurableApplicationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SpringApplicationRunAdvice {

    public static final Logger logger = LoggerFactory.getLogger(SpringApplicationRunAdvice.class);

    @Advice.OnMethodExit
    public static void onExit(@Advice.Return ConfigurableApplicationContext context) {
        if (context != null) {
            SpringContextHolder.set(context);
            logger.info("[dev-runtime] Spring context captured via SpringApplication.run(): {}", context.getClass().getName());
            System.out.println("[dev-runtime] SpringApplication.run() captured context: " + context.getClass().getName());
        } else {
            logger.warn("[dev-runtime] SpringApplication.run() returned null context.");
            System.out.println("[dev-runtime] SpringApplication.run() returned null context.");
        }
    }
}
