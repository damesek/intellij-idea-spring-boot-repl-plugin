package com.baader.devrt;

import hu.baader.repl.protocol.SensitiveValues;
import java.time.*;
import java.util.*;

/** Captures observable context on the calling thread; never restores authentication or application configuration. */
final class ReproductionContext {
    private static final ThreadLocal<Map<String,String>> EXTRA = new ThreadLocal<>();
    static ReplBindings.Scope metadata(Map<String,String> value) {
        if (value == null || value.size() > 32) throw new IllegalArgumentException("Capture metadata accepts at most 32 string fields");
        Map<String,String> safe = new LinkedHashMap<>();
        value.forEach((key, text) -> {
            if (key == null || text == null || key.length()>128 || text.length()>2048) throw new IllegalArgumentException("Capture metadata field too large");
            safe.put(key, SensitiveValues.sensitiveName(key) ? "[REDACTED]" : SensitiveValues.redact(text));
        });
        Map<String,String> previous = EXTRA.get(); EXTRA.set(safe);
        return () -> { if (previous == null) EXTRA.remove(); else EXTRA.set(previous); };
    }
    static Map<String,Object> capture() {
        Map<String,Object> data = new LinkedHashMap<>();
        data.put("capturedAt", Instant.now().toString()); data.put("javaVersion", System.getProperty("java.version"));
        data.put("timezone", ZoneId.systemDefault().getId()); data.put("locale", Locale.getDefault().toLanguageTag());
        data.put("applicationClass", System.getProperty("sun.java.command", "application").split(" ", 2)[0]);
        data.put("pid", ProcessHandle.current().pid()); data.put("contextEpoch", SpringContextHolder.epoch());
        data.put("thread", Thread.currentThread().getName());
        Object context = ReplBindings.applicationContext(); ClassLoader loader = AppClassPath.loader(context);
        if (loader == null) loader = ReproductionContext.class.getClassLoader();
        if (EXTRA.get() != null) data.put("applicationMetadata", new LinkedHashMap<>(EXTRA.get()));
        if (context != null) try {
            Object environment = context.getClass().getMethod("getEnvironment").invoke(context);
            Class<?> api = Class.forName("org.springframework.core.env.Environment", false, loader);
            data.put("activeProfiles", Arrays.asList((String[])api.getMethod("getActiveProfiles").invoke(environment)));
            data.put("defaultProfiles", Arrays.asList((String[])api.getMethod("getDefaultProfiles").invoke(environment)));
            for (String property : List.of("spring.application.name", "info.app.version", "git.commit.id", "build.version")) {
                Object value = api.getMethod("getProperty", String.class).invoke(environment, property);
                if (value instanceof String text) data.put(property, SensitiveValues.redact(text));
            }
        } catch (ReflectiveOperationException failure) { data.put("springEnvironment", "unavailable"); }
        for (String resource : List.of("git.properties", "META-INF/build-info.properties")) try (var input = loader.getResourceAsStream(resource)) {
            if (input != null) {
                Properties properties = new Properties(); properties.load(new java.io.ByteArrayInputStream(input.readNBytes(65536)));
                for (String key : List.of("git.commit.id", "git.commit.id.full", "build.version", "build.name"))
                    if (properties.getProperty(key) != null) data.put(key, properties.getProperty(key));
            }
        } catch (java.io.IOException ignored) { data.put(resource, "unavailable"); }
        try { data.put("springBootVersion", Class.forName("org.springframework.boot.SpringBootVersion",false,loader).getMethod("getVersion").invoke(null)); }
        catch (ReflectiveOperationException ignored) { data.put("springBootVersion", "unavailable"); }
        try {
            Class<?> holder = Class.forName("org.springframework.security.core.context.SecurityContextHolder",false,loader);
            Object security = holder.getMethod("getContext").invoke(null);
            Object authentication = Class.forName("org.springframework.security.core.context.SecurityContext",false,loader).getMethod("getAuthentication").invoke(security);
            if (authentication != null) {
                Class<?> api = Class.forName("org.springframework.security.core.Authentication",false,loader);
                data.put("principalName", SensitiveValues.redact(String.valueOf(api.getMethod("getName").invoke(authentication))));
                data.put("authenticated", api.getMethod("isAuthenticated").invoke(authentication));
                // No credentials, tokens, request headers, principal object or arbitrary details are read.
            }
        } catch (ReflectiveOperationException ignored) { data.put("securityContext", "unavailable on capture thread"); }
        try {
            Class<?> api = Class.forName("org.springframework.context.i18n.LocaleContextHolder",false,loader);
            data.put("requestLocale", api.getMethod("getLocale").invoke(null).toString());
            data.put("requestTimezone", ((TimeZone)api.getMethod("getTimeZone").invoke(null)).getID());
        } catch (ReflectiveOperationException ignored) {}
        data.put("missingContext", "Tenant, feature flags, current business clock, HTTP request and async context require explicit application-specific metadata. No context is automatically restored.");
        return data;
    }
}
