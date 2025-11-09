package com.baader.devrt;


import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Simplified Snapshot Manager - auto-detects whether to save as JSON or Object
 */
public class SnapshotManager {
    private static final Map<String, Object> objectCache = new ConcurrentHashMap<>();
    private static final Map<String, String> typeCache = new ConcurrentHashMap<>();
    private static final Path SNAPSHOT_DIR = Paths.get(System.getProperty("user.home"), ".java-repl-snapshots");

    static {
        try {
            Files.createDirectories(SNAPSHOT_DIR);
        } catch (IOException e) {
            System.err.println("Failed to create snapshot directory: " + e);
        }
    }

    private static Object getObjectMapper() {
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) cl = SnapshotManager.class.getClassLoader();
            Class<?> omClass = Class.forName("com.fasterxml.jackson.databind.ObjectMapper", true, cl);
            Object mapper = omClass.getConstructor().newInstance();

            // Configure mapper
            try {
                Class<?> sfClass = Class.forName("com.fasterxml.jackson.databind.SerializationFeature", true, cl);
                java.lang.reflect.Method enable = omClass.getMethod("enable", sfClass);
                java.lang.reflect.Method disable = omClass.getMethod("disable", sfClass);

                Object indentOutput = sfClass.getField("INDENT_OUTPUT").get(null);
                Object failOnEmpty = sfClass.getField("FAIL_ON_EMPTY_BEANS").get(null);
                Object writeDates = sfClass.getField("WRITE_DATES_AS_TIMESTAMPS").get(null);

                enable.invoke(mapper, indentOutput);
                disable.invoke(mapper, failOnEmpty);
                disable.invoke(mapper, writeDates);
            } catch (Exception e) {
                // Ignore configuration errors
            }

            return mapper;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Save any object - automatically decides between JSON (persistent) or Memory
     */
    public static void save(String name, Object obj) {
        if (obj == null) {
            objectCache.remove(name);
            typeCache.remove(name);
            deleteJsonFile(name);
            return;
        }

        // Try to save as JSON first (for persistence)
        try {
            Object mapper = getObjectMapper();
            if (mapper != null) {
                java.lang.reflect.Method writeValueAsString = mapper.getClass().getMethod("writeValueAsString", Object.class);
                String json = (String) writeValueAsString.invoke(mapper, obj);
                saveJsonFile(name, json);
                typeCache.put(name, obj.getClass().getName());
                objectCache.remove(name); // Remove from memory cache if JSON save successful
                System.out.println("Saved as JSON: " + name + " (" + obj.getClass().getSimpleName() + ")");
            } else {
                throw new Exception("ObjectMapper not available");
            }
        } catch (Exception e) {
            // If JSON serialization fails, keep in memory
            objectCache.put(name, obj);
            typeCache.put(name, obj.getClass().getName());
            deleteJsonFile(name); // Clean up any old JSON file
            System.out.println("Saved in memory: " + name + " (" + obj.getClass().getSimpleName() + ")");
        }
    }

    /**
     * Load snapshot - automatically handles JSON or Object
     */
    @SuppressWarnings("unchecked")
    public static <T> T load(String name) {
        // Check memory cache first
        if (objectCache.containsKey(name)) {
            T cached = (T) objectCache.get(name);
            System.out.println("Loaded from memory cache: " + name + " = " + cached);
            return cached;
        }

        // Try loading from JSON file
        String json = loadJsonFile(name);
        if (json != null) {
            System.out.println("Found JSON for " + name + ": " + json.substring(0, Math.min(100, json.length())) + "...");
            String typeName = typeCache.get(name);
            if (typeName != null) {
                try {
                    ClassLoader cl = Thread.currentThread().getContextClassLoader();
                    if (cl == null) cl = SnapshotManager.class.getClassLoader();
                    Class<?> clazz = cl.loadClass(typeName);

                    Object mapper = getObjectMapper();
                    if (mapper != null) {
                        java.lang.reflect.Method readValue = mapper.getClass().getMethod("readValue", String.class, Class.class);
                        return (T) readValue.invoke(mapper, json, clazz);
                    }
                } catch (Exception e) {
                    // Try generic deserialization
                    try {
                        Object mapper = getObjectMapper();
                        if (mapper != null) {
                            java.lang.reflect.Method readValue = mapper.getClass().getMethod("readValue", String.class, Class.class);
                            return (T) readValue.invoke(mapper, json, Object.class);
                        }
                    } catch (Exception e2) {
                        System.err.println("Failed to deserialize " + name + ": " + e2);
                    }
                }
            }
        }

        return null;
    }

    /**
     * Load with explicit type
     */
    public static <T> T load(String name, Class<T> type) {
        Object obj = load(name);
        if (obj == null) return null;

        // If already correct type, return it
        if (type.isInstance(obj)) {
            return type.cast(obj);
        }

        // Try to convert via JSON
        try {
            Object mapper = getObjectMapper();
            if (mapper != null) {
                String json;
                if (obj instanceof String) {
                    json = (String) obj;
                } else {
                    java.lang.reflect.Method writeValueAsString = mapper.getClass().getMethod("writeValueAsString", Object.class);
                    json = (String) writeValueAsString.invoke(mapper, obj);
                }
                java.lang.reflect.Method readValue = mapper.getClass().getMethod("readValue", String.class, Class.class);
                return type.cast(readValue.invoke(mapper, json, type));
            }
        } catch (Exception e) {
            System.err.println("Failed to convert " + name + " to " + type.getName() + ": " + e);
        }
        return null;
    }

    /**
     * Quick save from expression result
     */
    public static Object saveExpr(String name, String expr) {
        try {
            JavaCodeEvaluator.EvalObj evalResult = JavaCodeEvaluator.evaluateObject(expr);
            if (evalResult.error != null) {
                System.err.println("Evaluation error: " + evalResult.error);
                return null;
            }
            Object result = evalResult.obj;
            System.out.println("Saving object: " + result + " (type: " + (result != null ? result.getClass() : "null") + ")");
            save(name, result);
            return result;
        } catch (Exception e) {
            System.err.println("Failed to evaluate and save: " + e);
            e.printStackTrace();
            return null;
        }
    }

    /**
     * List all snapshots
     */
    public static List<String> list() {
        Set<String> names = new HashSet<>();
        names.addAll(objectCache.keySet());

        // Add JSON files
        try {
            Files.list(SNAPSHOT_DIR)
                .filter(p -> p.toString().endsWith(".json"))
                .map(p -> p.getFileName().toString().replace(".json", ""))
                .forEach(names::add);
        } catch (IOException e) {
            // Ignore
        }

        List<String> sorted = new ArrayList<>(names);
        Collections.sort(sorted);
        return sorted;
    }

    /**
     * Get info about a snapshot
     */
    public static String info(String name) {
        StringBuilder sb = new StringBuilder();
        sb.append("Name: ").append(name).append("\n");

        if (objectCache.containsKey(name)) {
            Object obj = objectCache.get(name);
            sb.append("Storage: Memory\n");
            sb.append("Type: ").append(obj.getClass().getName()).append("\n");
            sb.append("Value: ").append(obj.toString()).append("\n");
        } else {
            Path jsonPath = SNAPSHOT_DIR.resolve(name + ".json");
            if (Files.exists(jsonPath)) {
                sb.append("Storage: JSON file\n");
                String typeName = typeCache.get(name);
                if (typeName != null) {
                    sb.append("Type: ").append(typeName).append("\n");
                }
                try {
                    long size = Files.size(jsonPath);
                    sb.append("Size: ").append(size).append(" bytes\n");
                } catch (IOException e) {
                    // Ignore
                }
            } else {
                sb.append("Not found\n");
            }
        }

        return sb.toString();
    }

    /**
     * Delete a snapshot
     */
    public static void delete(String name) {
        objectCache.remove(name);
        typeCache.remove(name);
        deleteJsonFile(name);
    }

    /**
     * Clear all snapshots
     */
    public static void clear() {
        objectCache.clear();
        typeCache.clear();

        try {
            Files.list(SNAPSHOT_DIR)
                .filter(p -> p.toString().endsWith(".json"))
                .forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        // Ignore
                    }
                });
        } catch (IOException e) {
            // Ignore
        }
    }

    // Helper methods
    private static void saveJsonFile(String name, String json) {
        try {
            Path path = SNAPSHOT_DIR.resolve(name + ".json");
            Files.writeString(path, json);

            // Save type info
            Path typePath = SNAPSHOT_DIR.resolve(name + ".type");
            String typeName = typeCache.get(name);
            if (typeName != null) {
                Files.writeString(typePath, typeName);
            }
        } catch (IOException e) {
            System.err.println("Failed to save JSON file: " + e);
        }
    }

    private static String loadJsonFile(String name) {
        try {
            Path path = SNAPSHOT_DIR.resolve(name + ".json");
            if (Files.exists(path)) {
                // Load type info if available
                Path typePath = SNAPSHOT_DIR.resolve(name + ".type");
                if (Files.exists(typePath)) {
                    String typeName = Files.readString(typePath);
                    typeCache.put(name, typeName);
                }
                return Files.readString(path);
            }
        } catch (IOException e) {
            System.err.println("Failed to load JSON file: " + e);
        }
        return null;
    }

    private static void deleteJsonFile(String name) {
        try {
            Files.deleteIfExists(SNAPSHOT_DIR.resolve(name + ".json"));
            Files.deleteIfExists(SNAPSHOT_DIR.resolve(name + ".type"));
        } catch (IOException e) {
            // Ignore
        }
    }
}