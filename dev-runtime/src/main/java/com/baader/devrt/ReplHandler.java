package com.baader.devrt;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Handles the logic for all REPL operations, managing the JShell session state.
 */
public class ReplHandler {

    private final AtomicReference<JShellSession> sessionRef = new AtomicReference<>();

    public ReplHandler() {
        // Initialize with a session that has no Spring context
        this.sessionRef.set(new JShellSession(null));
    }

    public Map<String, Object> handle(String op, Map<String, String> message) {
        return switch (op) {
            case ReplOps.EVAL, ReplOps.JAVA_EVAL -> handleEval(message);
            case ReplOps.IMPORTS_GET -> handleGetImports();
            case ReplOps.IMPORTS_ADD -> handleAddImports(message);
            case ReplOps.SESSION_RESET -> handleResetSession();
            case ReplOps.BIND_SPRING -> handleBindSpring(message);
            case ReplOps.CLASS_RELOAD -> handleClassReload(message);
            case ReplOps.LIST_BEANS -> handleListBeans();
            // Snapshot ops can be added here later
            default -> Map.of("status", "error", "message", "Unknown op: " + op);
        };
    }

    private Map<String, Object> handleEval(Map<String, String> msg) {
        String code = msg.getOrDefault("code", "");
        JShellSession.EvalResult res = sessionRef.get().eval(code);
        
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("values", res.values());
        response.put("output", res.output());
        response.put("imports", res.imports());
        // Elsődleges visszatérési érték: az utolsó nem-null value
        if (!res.values().isEmpty()) {
            String last = res.values().get(res.values().size() - 1);
            if (last != null) {
                response.put("value", last);
            }
        }
        return response;
    }

    private Map<String, Object> handleGetImports() {
        return Map.of("imports", sessionRef.get().getImports());
    }

    private Map<String, Object> handleAddImports(Map<String, String> msg) {
        String importsStr = msg.getOrDefault("imports", "");
        List<String> imports = List.of(importsStr.split("\\R"));
        sessionRef.get().addImports(imports);
        return Map.of("imports", sessionRef.get().getImports());
    }

    private Map<String, Object> handleResetSession() {
        // Re-create the session, preserving the current ApplicationContext from ReplBindings
        Object currentCtx = ReplBindings.applicationContext();
        JShellSession oldSession = sessionRef.getAndSet(new JShellSession(currentCtx));
        if (oldSession != null) {
            oldSession.close();
        }
        return Map.of("reset", true);
    }

    private Map<String, Object> handleClassReload(Map<String, String> msg) {
        String code = msg.getOrDefault("code", "");
        JavaCodeEvaluator.HotSwapResult res = JavaCodeEvaluator.hotSwap(code);
        if (!res.success) {
            if (res.error != null) {
                return Map.of("status", "error", "err", res.error);
            }
            return Map.of("status", "error", "message", res.message != null ? res.message : "HotSwap failed");
        }
        return Map.of("value", res.message != null ? res.message : "HotSwap completed");
    }

    private Map<String, Object> handleListBeans() {
        Object ctx = ReplBindings.applicationContext();
        if (ctx == null) {
            ctx = SpringContextHolder.get();
        }
        if (ctx == null) {
            return Map.of("status", "error", "err", "No ApplicationContext bound in dev-runtime");
        }
        try {
            // Avoid hard failure if Spring is not on the classpath
            Class<?> appCtxClass = Class.forName("org.springframework.context.ApplicationContext");
            if (!appCtxClass.isInstance(ctx)) {
                return Map.of("status", "error", "err", "Bound context is not a Spring ApplicationContext");
            }
            Object appCtx = appCtxClass.cast(ctx);
            // getBeanDefinitionNames()
            String[] names;
            try {
                java.lang.reflect.Method getNames = appCtxClass.getMethod("getBeanDefinitionNames");
                Object res = getNames.invoke(appCtx);
                names = (String[]) res;
            } catch (Throwable t) {
                return Map.of("status", "error", "err", "Cannot list beans via ApplicationContext: " + t);
            }
            java.util.Arrays.sort(names);
            StringBuilder sb = new StringBuilder();
            for (String name : names) {
                if (name == null || name.isEmpty()) continue;
                String className = "";
                try {
                    java.lang.reflect.Method getBean = appCtxClass.getMethod("getBean", String.class);
                    Object bean = getBean.invoke(appCtx, name);
                    if (bean != null) {
                        className = bean.getClass().getName();
                    }
                } catch (Throwable ignored) {}
                sb.append(name).append('\t').append(className).append('\n');
            }
            return Map.of("value", sb.toString());
        } catch (Throwable t) {
            return Map.of("status", "error", "err", t.toString());
        }
    }

    private Map<String, Object> handleBindSpring(Map<String, String> msg) {
        try {
            // Prefer context captured by the transformer; if not present, fall back to
            // a one-shot auto-bind attempt (LiveBeansView-based).
            boolean bound = SpringContextHolder.get() != null;
            if (!bound) {
                System.out.println("[dev-runtime] bind-spring: no context in holder yet, trying AutoBinder...");
                bound = AutoBinder.tryBindOnce();
            } else {
                System.out.println("[dev-runtime] bind-spring: context already present in holder.");
            }
            if (bound) {
                // If bound, reset the session to include the ApplicationContext
                Object ctx = SpringContextHolder.get();
                JShellSession oldSession = sessionRef.getAndSet(new JShellSession(ctx));
                if (oldSession != null) {
                    oldSession.close();
                }
                System.out.println("[dev-runtime] bind-spring: session updated with ApplicationContext: " +
                        (ctx != null ? ctx.getClass().getName() : "null"));
                return Map.of("value", "true", "message", "Spring context bound and session updated.");
            }
            System.out.println("[dev-runtime] bind-spring: no ApplicationContext available (transformer + AutoBinder failed).");
            return Map.of("value", "false", "message", "Spring context could not be bound.");
        } catch (Throwable t) {
            System.out.println("[dev-runtime] bind-spring: error: " + t);
            return Map.of("status", "error", "err", t.toString());
        }
    }
}
