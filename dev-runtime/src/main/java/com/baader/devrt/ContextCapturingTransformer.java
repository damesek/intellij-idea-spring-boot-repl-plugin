package com.baader.devrt;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

public class ContextCapturingTransformer {

    public static final ElementMatcher.Junction<TypeDescription> MATCHER =
            ElementMatchers.named("org.springframework.boot.SpringApplication");

    public static final AgentBuilder.Transformer TRANSFORMER =
            (builder, typeDescription, classLoader, module, protectionDomain) ->
                    builder.method(ElementMatchers.named("run")
                                    .and(ElementMatchers.takesArguments(String[].class))
                                    .or(ElementMatchers.named("run").and(ElementMatchers.takesArguments(Class[].class, String[].class))))
                            .intercept(Advice.to(SpringApplicationRunAdvice.class));

}
