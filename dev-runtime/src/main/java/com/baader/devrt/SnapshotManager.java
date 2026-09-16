package com.baader.devrt;

import java.io.*;
import hu.baader.repl.protocol.SnapshotLimits;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** One snapshot repository: scoped LIVE handles, atomic DATA envelopes and explicit RECIPE source. */
public final class SnapshotManager {
    private static final long TTL_MILLIS = 30 * 60_000L;
    private static final LinkedHashMap<String, Live> LIVE = new LinkedHashMap<>();
    private static final Map<String, Map<Class<?>, Class<?>>> MIXINS = new HashMap<>();
    private static final ThreadLocal<String> SCOPE = ThreadLocal.withInitial(() -> "application");
    private record Live(Object value, long time, long epoch) {}
    private SnapshotManager() {}
    static ReplBindings.Scope scope(String id) { String old = SCOPE.get(); SCOPE.set(id); return () -> SCOPE.set(old); }
    static synchronized void clearLive() { LIVE.clear(); MIXINS.clear(); SnapshotTriggers.clear(); RuntimeEvents.clearAll(); }
    static synchronized void release(String scope) { LIVE.keySet().removeIf(key -> key.startsWith(scope + "\n")); MIXINS.remove(scope); SnapshotTriggers.release(scope); }
    private static String key(String name) { return SCOPE.get() + "\n" + checkedName(name); }

    /** Snapshot-only mix-ins; the application's ObjectMapper is never modified. */
    public static synchronized void addMixIn(Class<?> target, Class<?> mixIn) {
        Map<Class<?>, Class<?>> configured = MIXINS.computeIfAbsent(SCOPE.get(), ignored -> new LinkedHashMap<>());
        if (configured.size() >= 100 && !configured.containsKey(target)) throw new IllegalStateException("Snapshot mix-in limit reached");
        configured.put(Objects.requireNonNull(target), Objects.requireNonNull(mixIn));
    }

    public static synchronized void pin(String name, Object value) {
        checkedName(name); expire();
        if (Files.exists(path(name))) throw new IllegalArgumentException("A persistent snapshot already has this name; choose another name or delete it explicitly");
        if (LIVE.size() >= 100 && !LIVE.containsKey(key(name))) throw new IllegalStateException("Live snapshot limit reached (100); release old pins");
        LIVE.put(key(name), new Live(value, System.currentTimeMillis(), SpringContextHolder.epoch()));
    }
    static synchronized Object live(String name) {
        expire();
        Live value = LIVE.get(key(name));
        if (value == null) throw new IllegalArgumentException("Live snapshot missing or expired: " + name);
        return value.value();
    }
    private static void expire() {
        long now = System.currentTimeMillis(), epoch = SpringContextHolder.epoch();
        LIVE.values().removeIf(value -> now - value.time() > TTL_MILLIS || value.epoch() != epoch);
    }
    /** Disabled captures are cheap no-ops; a matching armed capture is synchronous and attempted once. */
    public static boolean capture(String point, String caseId, java.util.function.Supplier<?> value) {
        return SnapshotTriggers.capture(point, caseId, value);
    }
    public static boolean capture(String point, String caseId, java.util.function.Supplier<?> value, Map<String,String> metadata) {
        try (var ignored = ReproductionContext.metadata(metadata)) { return SnapshotTriggers.capture(point, caseId, value); }
    }
    static long size(String name) throws IOException { return Files.size(path(name)); }
    public static void save(String name, Object value) { save(name, value, ""); }
    public static void save(String name, Object value, String declaredType) {
        rejectInfrastructure(value);
        savePrepared(name, value, declaredType, mapper(), envelope(name, "DATA"));
    }
    static void captureValue(String name, String declaredType, java.util.function.Supplier<?> supplier) {
        // Freeze codec/mix-ins and capture metadata before user projection code can block or reset the session.
        Object mapper = mapper();
        Map<String, Object> envelope = envelope(name, "DATA");
        Object value = supplier.get();
        rejectInfrastructure(value);
        savePrepared(name, value, declaredType, mapper, envelope);
    }
    private static void savePrepared(String name, Object value, String declaredType, Object mapper, Map<String, Object> envelope) {
        envelope.put("declaredType", declaredType == null || declaredType.isBlank() ? typeOf(value) : checkedType(declaredType));
        if (value != null) try {
            List<String> fields = new ArrayList<>();
            for (Class<?> type = value.getClass(); type != null && fields.size() < 256; type = type.getSuperclass())
                for (Field field : type.getDeclaredFields()) if (fields.size() < 256 && !field.isSynthetic() && !Modifier.isStatic(field.getModifiers()))
                    fields.add(type.getName() + "." + field.getName() + ":" + field.getType().getName());
            Collections.sort(fields); envelope.put("javaFieldSchemaSha256", hash(value.getClass().getName() + "\n" + String.join("\n", fields)));
        } catch (LinkageError | RuntimeException unavailable) { envelope.put("javaFieldSchemaStatus", "unavailable"); }
        envelope.put("payload", value);
        write(name, mapper, envelope); // Commit first; errors leave the previous value intact.
        synchronized (SnapshotManager.class) { LIVE.remove(key(name)); }
    }
    public static void saveRecipe(String name, String source) {
        if (source == null || source.length() > 1_000_000) throw new IllegalArgumentException("Recipe is empty or too large");
        Map<String, Object> envelope = envelope(name, "RECIPE");
        envelope.put("source", source);
        write(name, mapper(), envelope);
        synchronized (SnapshotManager.class) { LIVE.remove(key(name)); }
    }
    public static String loadRecipe(String name) {
        Map<String, Object> envelope = read(name);
        if (!"RECIPE".equals(envelope.get("kind"))) throw new IllegalArgumentException("Not a recipe");
        return String.valueOf(envelope.get("source"));
    }
    static void saveCase(String name, Map<String,String> definition) {
        SnapshotCases.requireData(definition);
        Path file=path(name);
        if(Files.exists(file) && !"CASE".equals(metadata(file).get("kind")))
            throw new IllegalArgumentException("This name is already used by a snapshot or recipe");
        Map<String,Object> envelope=envelope(name,"CASE");
        envelope.put("payload",definition); write(name,mapper(),envelope);
    }
    static void requireData(String name) {
        if(!"DATA".equals(metadata(path(name)).get("kind"))) throw new IllegalArgumentException("Select a persistent DATA snapshot: "+name);
    }
    static Map<String,String> loadCase(String name) {
        Map<String,Object> envelope=read(name,true);
        if(!"CASE".equals(envelope.get("kind")) || !(envelope.get("payload") instanceof Map<?,?> data)) throw new IllegalArgumentException("Not a saved test case");
        Map<String,String> result=new LinkedHashMap<>();
        data.forEach((key,value) -> { if(value instanceof String text) result.put(String.valueOf(key),text); });
        return result;
    }
    /** Compare the serialized result with a DATA expectation using the same bounded snapshot codec. */
    static Map<String,Object> compareValue(String expected,Object actual) {
        return SnapshotDiff.compare(dataPayload(expected), casePayload(actual), 0, 100);
    }
    static Object casePayload(Object actual) {
        rejectInfrastructure(actual);
        Path temporary=null;
        try {
            Object mapper=mapper();
            Map<String,Object> envelope=envelope("case-result","DATA"); envelope.put("payload",actual);
            temporary=Files.createTempFile("sb-repl-case-", ".json");
            SnapshotIO.atomicWrite(temporary,false,output -> encode(mapper,envelope,output));
            try(InputStream input=SnapshotIO.input(temporary,SnapshotLimits.MAX_BYTES)) {
                Class<?> feature=Class.forName("com.fasterxml.jackson.databind.DeserializationFeature",true,mapper.getClass().getClassLoader());
                mapper.getClass().getMethod("configure",feature,boolean.class).invoke(mapper,feature.getField("USE_BIG_DECIMAL_FOR_FLOATS").get(null),true);
                Map<?,?> data=(Map<?,?>)decode(mapper,input,Map.class);
                return data.get("payload");
            }
        } catch(IOException | ReflectiveOperationException failure) { throw failure("Cannot compare test result",failure); }
        finally { if(temporary!=null) try { Files.deleteIfExists(temporary); } catch(IOException ignored) {} }
    }
    @SuppressWarnings("unchecked")
    public static <T> T load(String name) {
        synchronized (SnapshotManager.class) {
            expire();
            if (LIVE.containsKey(key(name))) return (T) LIVE.get(key(name)).value();
        }
        Map<String, Object> envelope = read(name);
        if (!"DATA".equals(envelope.get("kind"))) throw new IllegalArgumentException("Use recipe/load for source; recipes are never executed automatically");
        return (T) materialize(envelope, String.valueOf(envelope.get("declaredType")));
    }
    public static <T> T load(String name, Class<T> type) { return type.cast(materialize(read(name), type.getName())); }
    public static Object loadTyped(String name, String type) { return materialize(read(name), checkedType(type)); }
    public static Object loadVersion(String name, String version, String type) {
        try {
            Map<String,Object> data = read(SnapshotVersions.version(path(name), version), false);
            return materialize(data, type == null || type.isBlank() ? checkedType(String.valueOf(data.get("declaredType"))) : checkedType(type));
        } catch (IOException e) { throw failure("Cannot load snapshot version", e); }
    }
    /** Preserve declared collection element types when binding a restored value into Java source. */
    static String sourceType(String name, Object value) {
        return sourceType(name, value, "");
    }
    static String sourceType(String name, Object value, String version) {
        synchronized (SnapshotManager.class) {
            expire();
            if (version.isBlank() && LIVE.containsKey(key(name))) return publicSourceType(value == null ? Object.class : value.getClass());
        }
        try {
            String declared = String.valueOf(metadata(version.isBlank() ? path(name) : SnapshotVersions.version(path(name), version)).get("declaredType"));
            Object mapper = mapper();
            Object factory = mapper.getClass().getMethod("getTypeFactory").invoke(mapper);
            Object javaType = factory.getClass().getMethod("constructFromCanonical", String.class).invoke(factory, checkedType(declared));
            return sourceType(javaType, 0);
        } catch (ReflectiveOperationException | IOException exception) { throw failure("Cannot resolve the snapshot's Java type", exception); }
    }
    private static String publicSourceType(Class<?> type) {
        if (type.getCanonicalName() == null) return "java.lang.Object";
        for (Class<?> enclosing=type; enclosing!=null; enclosing=enclosing.getEnclosingClass())
            if (!Modifier.isPublic(enclosing.getModifiers())) return "java.lang.Object";
        return type.getCanonicalName();
    }
    private static String sourceType(Object javaType, int depth) throws ReflectiveOperationException {
        if (javaType == null || depth > 32) return "java.lang.Object";
        Class<?> raw = (Class<?>) javaType.getClass().getMethod("getRawClass").invoke(javaType);
        String name = publicSourceType(raw);
        int count = (Integer) javaType.getClass().getMethod("containedTypeCount").invoke(javaType);
        if (name.equals("java.lang.Object") || count == 0 || raw.isArray()) return name;
        List<String> arguments = new ArrayList<>();
        for (int i=0;i<count;i++) arguments.add(sourceType(javaType.getClass().getMethod("containedType", int.class).invoke(javaType,i),depth+1));
        return name+"<"+String.join(",",arguments)+">";
    }
    static Object materialize(Map<String, Object> envelope, String type) {
        if (!"DATA".equals(envelope.get("kind"))) throw new IllegalArgumentException("Not a DATA snapshot");
        Object mapper = mapper();
        try {
            Object factory = mapper.getClass().getMethod("getTypeFactory").invoke(mapper);
            Object javaType = factory.getClass().getMethod("constructFromCanonical", String.class).invoke(factory, checkedType(type));
            Class<?> javaTypeClass = Class.forName("com.fasterxml.jackson.databind.JavaType", true, mapper.getClass().getClassLoader());
            return mapper.getClass().getMethod("convertValue", Object.class, javaTypeClass).invoke(mapper, envelope.get("payload"), javaType);
        } catch (ReflectiveOperationException exception) { throw failure("Snapshot type is unavailable or incompatible; import as data or specify a compatible type", exception); }
    }
    /** Small inline JSON for the protocol/editor. Use importFile for large captures. */
    public static Map<String, Object> diff(String before, String after, int offset, int limit) {
        return SnapshotDiff.compare(dataPayload(before), dataPayload(after), offset, limit);
    }
    static Object exportPayload(String name) {
        try { if(Files.size(path(name))>16*1024*1024)throw new IllegalArgumentException("JUnit DATA fixture exceeds 16 MiB; use a smaller projection"); }
        catch(IOException failure){throw new IllegalArgumentException("Cannot read JUnit DATA fixture",failure);}
        return dataPayload(name);
    }
    static Object dataPayload(String name) {
        Map<String, Object> envelope = read(name, true);
        if (!"DATA".equals(envelope.get("kind"))) throw new IllegalArgumentException("Select two DATA snapshots for comparison");
        return envelope.get("payload");
    }
    public static void importJson(String name, String json) {
        if (json == null || json.length() > SnapshotLimits.INLINE_JSON_BYTES || json.getBytes(StandardCharsets.UTF_8).length > SnapshotLimits.INLINE_JSON_BYTES)
            throw new IllegalArgumentException("Inline JSON exceeds 2 MiB; use file import for up to 200 MiB");
        Object mapper = mapper();
        importValue(name, mapper, decode(mapper, json, Object.class));
    }
    /** Local filesystem API: the IDE and application must share the selected path. */
    public static void importFile(String name, Path source) {
        checkedName(name);
        try (InputStream input = SnapshotIO.input(source, SnapshotLimits.MAX_BYTES)) {
            Object mapper = mapper();
            importValue(name, mapper, decode(mapper, input, Object.class));
        } catch (IOException exception) { throw failure("Cannot import snapshot file", exception); }
    }
    private static void importValue(String name, Object mapper, Object value) {
        Map<String, Object> envelope = envelope(name, "DATA");
        // Import payloads, never arbitrary on-disk type instructions.
        if (value instanceof Map<?, ?> map && map.containsKey("schemaVersion") && map.containsKey("payload")) value = map.get("payload");
        envelope.put("declaredType", typeOf(value)); envelope.put("payload", value);
        write(name, mapper, envelope);
        synchronized (SnapshotManager.class) { LIVE.remove(key(name)); }
    }
    public static void exportFile(String name, Path destination) {
        Path source = path(name);
        metadata(source); // Validate the envelope before exporting; do not materialize its payload.
        try {
            SnapshotIO.atomicWrite(destination.toAbsolutePath().normalize(), false, output -> {
                try (InputStream input = SnapshotIO.input(source, SnapshotLimits.MAX_BYTES)) { input.transferTo(output); }
            });
        } catch (IOException exception) { throw failure("Export failed; previous file preserved", exception); }
    }
    public static String json(String name) {
        Path source = path(name);
        try (InputStream input = SnapshotIO.input(source, SnapshotLimits.INLINE_JSON_BYTES)) {
            metadata(source);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) { throw failure("Cannot export inline JSON", exception); }
    }
    public static String info(String name) {
        Live live;
        synchronized (SnapshotManager.class) { expire(); live = LIVE.get(key(name)); }
        if (live != null) return name + "\nLIVE — mutable reference, expires after 30 minutes or context/session close\n" + typeOf(live.value());
        Map<String, Object> envelope = metadata(path(name));
        return name + "\n" + envelope.get("kind") + "\n" + envelope.getOrDefault("declaredType", "source") + "\n" + envelope.get("capturedAt") + "\n" + envelope.get("sizeBytes") + " bytes (limit: 200 MiB)\n" + CaseJson.write(provenance(name, ""));
    }
    public static List<String> list() {
        Map<String, String> entries = new TreeMap<>();
        synchronized (SnapshotManager.class) {
        expire();
        LIVE.forEach((key, value) -> {
            if (key.startsWith(SCOPE.get() + "\n")) {
                String name = key.substring(key.indexOf('\n') + 1);
                entries.put(name, name + "\t" + typeOf(value.value()) + "\tLIVE");
            }
        });
        }
        Path root = directory();
        try (var files = Files.list(root)) {
            for (Path file : files.filter(p -> p.getFileName().toString().matches("[a-f0-9]{64}\\.json")).limit(1001).toList()) {
                if (entries.size() >= 1000) throw new IllegalStateException("Snapshot count limit reached");
                if (Files.isSymbolicLink(file)) continue;
                Map<String, Object> envelope = metadata(file);
                String name = checkedName(String.valueOf(envelope.get("name")));
                if (!path(name).equals(file)) throw new IllegalStateException("Snapshot name does not match its file");
                entries.put(name, name + "\t" + envelope.getOrDefault("declaredType", "source") + "\t" + envelope.get("kind"));
            }
        } catch (IOException exception) { throw failure("Cannot list snapshots", exception); }
        return List.copyOf(entries.values());
    }
    public static void delete(String name) {
        synchronized (SnapshotManager.class) { LIVE.remove(key(name)); }
        try { SnapshotVersions.delete(path(name)); } catch (IOException exception) { throw failure("Cannot delete snapshot", exception); }
    }
    public static void clear() {
        release(SCOPE.get());
        for (String entry : list()) delete(entry.split("\t", 2)[0]);
    }
    /** Compatibility API: evaluate explicitly in the REPL, then save the resulting variable/handle. */
    public static void saveExpression(String name, String expression) { throw new UnsupportedOperationException("Evaluate the expression first, then save its result"); }

    static Map<String, Object> envelope(String name, String kind) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schemaVersion", 1); envelope.put("name", checkedName(name)); envelope.put("kind", kind);
        envelope.put("applicationId", applicationId()); envelope.put("contextEpoch", SpringContextHolder.epoch());
        envelope.put("capturedAt", Instant.now().toString()); envelope.put("codec", "jackson-data-v1");
        envelope.put("executionContext", ReproductionContext.capture());
        return envelope;
    }
    static String applicationId() {
        return System.getProperty("sb.repl.applicationId", System.getProperty("user.dir") + ":" + System.getProperty("sun.java.command", "application").split(" ", 2)[0]);
    }
    static Path directory() {
        Path path = Path.of(System.getProperty("user.home"), ".java-repl-snapshots", "v1", hash(applicationId()));
        try {
            for (Path directory : List.of(path.getParent().getParent(), path.getParent(), path)) {
                if (Files.isSymbolicLink(directory)) throw new IOException("Snapshot directories must not be symbolic links");
                Files.createDirectories(directory);
            }
            secure(path, true); return path.toAbsolutePath().normalize();
        } catch (IOException exception) { throw failure("Cannot create snapshot directory", exception); }
    }
    static Path path(String name) { return directory().resolve(hash(checkedName(name)) + ".json"); }
    public static List<Map<String,Object>> versions(String name) {
        try { return SnapshotVersions.list(path(name)); }
        catch (IOException e) { throw failure("Cannot list snapshot versions", e); }
    }
    public static Map<String,Object> provenance(String name, String version) {
        try {
            Path source = version == null || version.isBlank() ? path(name) : SnapshotVersions.version(path(name), version);
            Map<String,Object> data = new LinkedHashMap<>(metadata(source));
            data.put("version", SnapshotVersions.checksum(source)); return data;
        } catch (IOException e) { throw failure("Cannot read snapshot provenance", e); }
    }
    public static void restoreVersion(String name, String version) {
        try {
            Path source = SnapshotVersions.version(path(name), version);
            // Rewrite only envelope metadata; no DTO constructor, application deserializer or recipe executes.
            SnapshotIO.atomicWrite(path(name), true, out -> SnapshotEnvelopeIO.rewrite(source, out,
                    Map.of("restoredFrom", version, "recordedAt", Instant.now().toString(), "recordedBy", SCOPE.get()), null));
        } catch (IOException e) { throw failure("Cannot restore snapshot version", e); }
    }
    static Map<String,Object> bundleEnvelope(String name) { return read(name); }
    static Map<String,Object> detachedEnvelope(String name) { return read(name, true); }
    /** Publish detached JSON entries together, under the repository lock, without overwriting any name. */
    static void createNew(Map<String,Map<String,Object>> entries, Map<String,String> expectedVersions) {
        Map<Path,Path> prepared=new LinkedHashMap<>();
        try {
            Object codec=CaseJson.mapper();
            for(var entry:entries.entrySet()){
                String name=checkedName(entry.getKey());
                Map<String,Object> data=new LinkedHashMap<>(entry.getValue());
                data.put("schemaVersion",1);data.put("applicationId",applicationId());data.put("name",name);
                data.put("recordedAt",Instant.now().toString());data.put("recordedBy",SCOPE.get());
                validate(data);
                Path target=path(name), temporary=Files.createTempFile(target.getParent(),".prepared-",".tmp");
                prepared.put(target,temporary);
                SnapshotIO.atomicWrite(temporary,false,out->encode(codec,data,out));
            }
            Map<Path,String> versions=new LinkedHashMap<>();
            expectedVersions.forEach((name,version)->versions.put(path(name),version));
            SnapshotVersions.importBatch(prepared,versions);
        } catch(IOException failure){throw failure("New DATA/CASE was not saved; existing data preserved",failure);}
        finally { for(Path temporary:prepared.values())try{Files.deleteIfExists(temporary);}catch(IOException ignored){} }
    }
    /** Detached JSON captured with the snapshot codec. Bounded before allocating the final byte array. */
    static Map<String,Object> freezeData(Object value, String type, int limit) {
        rejectInfrastructure(value);
        Map<String,Object> data=envelope("recorded-value","DATA");
        data.put("declaredType",type==null||type.isBlank()?typeOf(value):checkedType(type)); data.put("payload",value);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        OutputStream bounded=new FilterOutputStream(bytes) {
            int count;
            private void reserve(int length) throws IOException { if(length>limit-count)throw new IOException("Full DATA capture exceeds "+limit+" bytes");count+=length; }
            @Override public void write(int value) throws IOException {reserve(1);out.write(value);}
            @Override public void write(byte[] value,int offset,int length) throws IOException {reserve(length);out.write(value,offset,length);}
        };
        encode(mapper(),data,bounded);
        @SuppressWarnings("unchecked") Map<String,Object> frozen=(Map<String,Object>)CaseJson.parse(bytes.toString(StandardCharsets.UTF_8),limit);
        return frozen;
    }
    static void copyData(String from, String to) {
        Map<String,Object> data = read(from);
        if (!"DATA".equals(data.get("kind"))) throw new IllegalArgumentException("Reproduction input must be a DATA snapshot");
        data.put("name", checkedName(to)); write(to, mapper(), data);
    }
    static void importBundleEnvelope(String name, Map<String,Object> data) {
        if (!Set.of("DATA", "RECIPE", "CASE").contains(data.get("kind"))) throw new IllegalArgumentException("Unsupported reproduction entry kind");
        data.put("schemaVersion", 1); data.put("applicationId", applicationId()); data.put("name", checkedName(name));
        if ("DATA".equals(data.get("kind"))) checkedType(String.valueOf(data.get("declaredType")));
        write(name, mapper(), data);
    }
    static String checkedName(String name) {
        if (name == null || name.isBlank() || name.length() > 128 || name.contains("/") || name.contains("\\") || name.contains("..") || name.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid snapshot name");
        return name;
    }
    static String checkedType(String type) {
        if (type == null || type.length() > 1024 || !type.matches("[\\w.$<>?, \\[\\];]+")) throw new IllegalArgumentException("Invalid snapshot type");
        if (type.contains("ClassLoader") || type.contains("ProcessBuilder") || type.contains("java.lang.Runtime") || type.contains("java.lang.Class")) throw new IllegalArgumentException("Infrastructure types cannot be restored");
        return type;
    }
    private static String typeOf(Object value) {
        if (value == null) return "java.lang.Object";
        if (value instanceof Map) return "java.util.LinkedHashMap";
        if (value instanceof List || value instanceof Set) return "java.util.ArrayList";
        return value.getClass().getName();
    }
    private static void rejectInfrastructure(Object value) {
        if (value == null) return;
        if (value == SpringContextHolder.get() || value instanceof Thread || value instanceof ClassLoader || value instanceof AutoCloseable || Proxy.isProxyClass(value.getClass()) || value.getClass().getName().contains("$$SpringCGLIB")) throw new IllegalArgumentException("Freeze a DTO/projection; pin this infrastructure object as LIVE");
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private static void secure(Path path, boolean directory) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
    }
    private static void write(String name, Object mapper, Map<String, Object> envelope) {
        envelope.put("recordedAt", Instant.now().toString()); envelope.put("recordedBy", SCOPE.get());
        try { SnapshotIO.atomicWrite(path(name), true, output -> encode(mapper, envelope, output)); }
        catch (IOException exception) { throw failure("Snapshot was not saved; previous data preserved", exception); }
    }
    private static Map<String, Object> read(String name) { return read(name, false); }
    private static Map<String, Object> read(String name, boolean preciseNumbers) {
        return read(path(name), preciseNumbers);
    }
    private static Map<String,Object> read(Path path, boolean preciseNumbers) {
        try (InputStream input = SnapshotIO.input(path, SnapshotLimits.MAX_BYTES)) {
            Object mapper = preciseNumbers ? CaseJson.mapper() : mapper();
            if (preciseNumbers) try {
                Class<?> featureType = Class.forName("com.fasterxml.jackson.databind.DeserializationFeature", true, mapper.getClass().getClassLoader());
                mapper.getClass().getMethod("configure", featureType, boolean.class).invoke(mapper, featureType.getField("USE_BIG_DECIMAL_FOR_FLOATS").get(null), true);
            } catch (ReflectiveOperationException exception) { throw failure("Cannot configure snapshot comparison", exception); }
            @SuppressWarnings("unchecked") Map<String, Object> envelope = (Map<String, Object>) decode(mapper, input, Map.class);
            validate(envelope);
            return envelope;
        } catch (IOException exception) { throw failure("Cannot read snapshot", exception); }
    }
    private static void validate(Map<String, Object> envelope) throws IOException {
        if (envelope == null || !(envelope.get("schemaVersion") instanceof Number version) || version.intValue() != 1
                || !applicationId().equals(envelope.get("applicationId")))
            throw new IOException("Unsupported snapshot version or application");
    }
    /** Headers are written before payloads, so refreshing the list never deserializes 200 MiB. */
    static Map<String, Object> metadata(Path source) { return metadata(source, true); }
    static Map<String, Object> metadata(Path source, boolean localApplication) {
        try (InputStream input = SnapshotIO.input(source, SnapshotLimits.MAX_BYTES)) {
            Object mapper = CaseJson.mapper();
            Object factory = mapper.getClass().getMethod("getFactory").invoke(mapper);
            Class<?> parserType = Class.forName("com.fasterxml.jackson.core.JsonParser", true, mapper.getClass().getClassLoader());
            try (Closeable parser = (Closeable) factory.getClass().getMethod("createParser", InputStream.class).invoke(factory, input)) {
                Method next = parserType.getMethod("nextToken"), text = parserType.getMethod("getText");
                if (!"START_OBJECT".equals(String.valueOf(next.invoke(parser)))) throw new IOException("Invalid snapshot envelope");
                Map<String, Object> result = new LinkedHashMap<>();
                while ("FIELD_NAME".equals(String.valueOf(next.invoke(parser)))) {
                    String field = (String) parserType.getMethod("getCurrentName").invoke(parser);
                    if ((field.equals("payload") || field.equals("source")) && result.containsKey("schemaVersion") && result.containsKey("applicationId")
                            && result.containsKey("name") && result.containsKey("kind") && result.containsKey("capturedAt")) break;
                    Object token = next.invoke(parser);
                    if (Set.of("schemaVersion", "name", "kind", "applicationId", "capturedAt", "declaredType", "recordedAt", "recordedBy", "restoredFrom", "importedFrom", "sourceApplicationId", "javaFieldSchemaSha256").contains(field)) {
                        String value = String.valueOf(text.invoke(parser));
                        if (value.length() > 8192) throw new IOException("Snapshot metadata is too large");
                        result.put(field, field.equals("schemaVersion") ? Integer.valueOf(value) : value);
                    } else if (field.equals("executionContext")) result.put(field, SnapshotEnvelopeIO.headerValue(parser, parserType, 0));
                    else if (token != null) parserType.getMethod("skipChildren").invoke(parser);
                }
                if (localApplication) validate(result);
                else if (!Objects.equals(result.get("schemaVersion"), 1) || !Set.of("DATA", "CASE", "RECIPE").contains(result.get("kind"))) throw new IOException("Invalid workspace snapshot envelope");
                result.put("sizeBytes", Files.size(source)); return result;
            }
        } catch (ReflectiveOperationException | IOException exception) { throw failure("Cannot read snapshot metadata", exception); }
    }
    static Object mapper() {
        try {
            Object context = SpringContextHolder.get();
            Class<?> type = Class.forName("com.fasterxml.jackson.databind.ObjectMapper", true, AppClassPath.loader(context));
            Object mapper = null;
            if (context != null) try {
                String[] names = (String[]) context.getClass().getMethod("getBeanNamesForType", Class.class, boolean.class, boolean.class).invoke(context, type, false, false);
                Object factory = context.getClass().getMethod("getBeanFactory").invoke(context);
                for (String name : names) {
                    Object existing = factory.getClass().getMethod("getSingleton", String.class).invoke(factory, name);
                    if (type.isInstance(existing)) { mapper = type.getMethod("copy").invoke(existing); break; }
                }
            } catch (ReflectiveOperationException ignored) {}
            if (mapper == null) { mapper = type.getConstructor().newInstance(); type.getMethod("findAndRegisterModules").invoke(mapper); }
            type.getMethod("deactivateDefaultTyping").invoke(mapper);
            // Configure only the private mapper copy. Older Jackson versions have no string constraint API.
            try {
                Object jsonFactory = type.getMethod("getFactory").invoke(mapper);
                Class<?> constraintsType = Class.forName("com.fasterxml.jackson.core.StreamReadConstraints", true, type.getClassLoader());
                Object constraints = jsonFactory.getClass().getMethod("streamReadConstraints").invoke(jsonFactory);
                Object builder = constraintsType.getMethod("rebuild").invoke(constraints);
                builder.getClass().getMethod("maxStringLength", int.class).invoke(builder, SnapshotLimits.MAX_BYTES);
                jsonFactory.getClass().getMethod("setStreamReadConstraints", constraintsType).invoke(jsonFactory, builder.getClass().getMethod("build").invoke(builder));
            } catch (ClassNotFoundException | NoSuchMethodException ignored) { /* Jackson before 2.15 */ }

            Object factory = type.getMethod("getTypeFactory").invoke(mapper);
            Class<?> factoryType = Class.forName("com.fasterxml.jackson.databind.type.TypeFactory", true, type.getClassLoader());
            Object appFactory = factoryType.getMethod("withClassLoader", ClassLoader.class).invoke(factory, AppClassPath.loader(context));
            type.getMethod("setTypeFactory", factoryType).invoke(mapper, appFactory);
            Map<Class<?>, Class<?>> configured;
            synchronized (SnapshotManager.class) { configured = Map.copyOf(MIXINS.getOrDefault(SCOPE.get(), Map.of())); }
            for (var mixIn : configured.entrySet())
                type.getMethod("addMixIn", Class.class, Class.class).invoke(mapper, mixIn.getKey(), mixIn.getValue());
            return mapper;
        } catch (ReflectiveOperationException exception) { throw failure("DATA snapshots require Jackson in the application; LIVE pins are available without it", exception); }
    }
    private static void encode(Object mapper, Map<String, Object> envelope, OutputStream output) {
        try {
            Object factory = mapper.getClass().getMethod("getFactory").invoke(mapper);
            Class<?> generatorType = Class.forName("com.fasterxml.jackson.core.JsonGenerator", true, mapper.getClass().getClassLoader());
            try (Closeable generator = (Closeable) factory.getClass().getMethod("createGenerator", OutputStream.class).invoke(factory, output)) {
                generatorType.getMethod("writeStartObject").invoke(generator);
                List<Map.Entry<String,Object>> fields = new ArrayList<>(envelope.entrySet());
                fields.sort(Comparator.comparingInt(e -> Set.of("payload", "source").contains(e.getKey()) ? 1 : 0));
                for (var entry : fields) {
                    generatorType.getMethod("writeFieldName", String.class).invoke(generator, entry.getKey());
                    mapper.getClass().getMethod("writeValue", generatorType, Object.class).invoke(mapper, generator, entry.getValue());
                }
                generatorType.getMethod("writeEndObject").invoke(generator);
            }
        } catch (ReflectiveOperationException | IOException exception) { throw failure("Cannot serialize snapshot; previous data preserved", exception); }
    }
    private static Object decode(Object mapper, Object source, Class<?> type) {
        try {
            // Reject trailing JSON values as well as malformed JSON.
            Class<?> featureType = Class.forName("com.fasterxml.jackson.databind.DeserializationFeature", true, mapper.getClass().getClassLoader());
            Object feature = featureType.getField("FAIL_ON_TRAILING_TOKENS").get(null);
            mapper.getClass().getMethod("configure", featureType, boolean.class).invoke(mapper, feature, true);
            return mapper.getClass().getMethod("readValue", source instanceof InputStream ? InputStream.class : String.class, Class.class).invoke(mapper, source, type);
        } catch (ReflectiveOperationException exception) { throw failure("Invalid snapshot JSON", exception); }
    }
    private static IllegalStateException failure(String message, Exception exception) {
        Throwable cause = exception instanceof InvocationTargetException invocation ? invocation.getCause() : exception;
        return new IllegalStateException(message + ": " + cause.getMessage(), cause);
    }
}
