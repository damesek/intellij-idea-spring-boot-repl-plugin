package com.baader.devrt;

import hu.baader.repl.protocol.AuditTrail;
import hu.baader.repl.protocol.SnapshotLimits;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Portable, checksummed DATA/CASE/RECIPE bundle. Import never materializes Java types or evaluates source. */
final class ReproductionBundle {
    private static final List<String> ROLES = List.of("input", "expected", "case", "recipe", "environment");
    private ReproductionBundle() {}
    static Map<String,Object> create(Map<String,String> request, JShellSession shell, JShellSession.EvalResult evaluation, String code) {
        if (evaluation == null || code.isBlank()) throw new IllegalStateException("Run a Java cell before creating a reproduction");
        if (evaluation.interrupted()) throw new IllegalStateException("Interrupted execution cannot be used as a deterministic expectation");
        if (!evaluation.error().isBlank() && evaluation.exceptionType().isBlank()) throw new IllegalStateException("Fix compilation errors before creating an executable reproduction");
        if (evaluation.error().isBlank() && evaluation.handle().isBlank()) throw new IllegalStateException("The execution must produce a result or a runtime exception");
        String input = request.get("input"); SnapshotManager.requireData(input);
        String base = unique(request.getOrDefault("name", "reproduction"));
        Map<String,String> names = names(base); List<String> created = new ArrayList<>();
        try {
            SnapshotManager.copyData(input, names.get("input")); created.add(names.get("input"));
            Object expected = evaluation.error().isBlank() ? shell.value(evaluation.handle(), null) : Map.of("exception",evaluation.exceptionType(),"message",evaluation.exceptionMessage());
            SnapshotManager.save(names.get("expected"), expected); created.add(names.get("expected"));
            Map<String,String> definition = new LinkedHashMap<>();
            definition.put("input",names.get("input")); definition.put("expected",names.get("expected"));
            definition.put("variable",request.getOrDefault("variable","input")); definition.put("type",request.getOrDefault("type",""));
            definition.put("code",String.join("\n",evaluation.imports())+"\n"+code);
            definition.put("expected-exception",evaluation.exceptionType()); definition.put("expected-message",evaluation.exceptionMessage());
            SnapshotManager.saveCase(names.get("case"),SnapshotCases.definition(definition)); created.add(names.get("case"));
            SnapshotManager.saveRecipe(names.get("recipe"),"// Bind the bundle input to " + definition.get("variable") + " before running this recipe.\n" + definition.get("code")); created.add(names.get("recipe"));
            Map<String,Object> environment = ReproductionContext.capture();
            environment.put("inputCaptureContext", SnapshotManager.bundleEnvelope(input).getOrDefault("executionContext",Map.of()));
            environment.put("inputSource",input); environment.put("sourceSha256",AuditTrail.hash(code));
            environment.put("resultKind",evaluation.error().isBlank()?"VALUE":"EXCEPTION");
            environment.put("contextRestoration", "Metadata is evidence only. Profiles, principal, tenant, time, flags and external services are not restored or replayed automatically.");
            if (request.containsKey("metadata-json") && !request.get("metadata-json").isBlank()) {
                String json=request.get("metadata-json"); if(json.length()>65536)throw new IllegalArgumentException("Reproduction metadata exceeds 64 KiB");
                Object extra=parse(new ByteArrayInputStream(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                if (!(extra instanceof Map<?,?>)) throw new IllegalArgumentException("Reproduction metadata must be a JSON object");
                environment.put("userProvided",extra);
            }
            SnapshotManager.save(names.get("environment"),environment); created.add(names.get("environment"));
            saveIndex(base,names); created.add(base);
            return Map.of("bundle-id",base,"case",names.get("case"),"input",names.get("input"),"expected",names.get("expected"),
                    "recipe",names.get("recipe"),"value","Reproduction created from the last execution; code was not rerun. Input comes from the selected DATA snapshot.");
        } catch(Exception failure) { created.forEach(SnapshotManager::delete); throw new IllegalStateException("Reproduction was not created: "+failure.getMessage(),failure); }
    }
    static void exportFile(String bundle, Path destination) {
        SnapshotManager.requireData(bundle);
        Object index=SnapshotManager.bundleEnvelope(bundle).get("payload");
        if (!(index instanceof Map<?,?> data) || !"1".equals(String.valueOf(data.get("reproductionVersion"))) || !(data.get("entries") instanceof Map<?,?> names))
            throw new IllegalArgumentException("Not a reproduction bundle index");
        Map<String,String> savedCase=SnapshotCases.definition(SnapshotManager.loadCase(Objects.toString(names.get("case"),"")));
        if(!savedCase.get("parameters-json").isBlank() || !savedCase.get("input").equals(names.get("input")) || !savedCase.get("expected").equals(names.get("expected")))
            throw new IllegalArgumentException("Reproduction v1 bundles contain one frozen input/expected pair. Use JUnit ZIP export for parameterized or rebound CASEs.");
        Path directory = temporary();
        try {
            List<Map<String,Object>> entries=new ArrayList<>(); long total=0;
            for(int i=0;i<ROLES.size();i++) {
                String role=ROLES.get(i), name=Objects.toString(names.get(role), "");
                Path file=directory.resolve(i+".json"); SnapshotManager.exportFile(name,file);
                long bytes=Files.size(file); total+=bytes;
                if(total>SnapshotLimits.MAX_BYTES)throw new IOException("Bundle exceeds 200 MiB uncompressed; use smaller projections");
                entries.add(Map.of("path","snapshots/"+i+".json","role",role,"name",name,"size",bytes,"sha256",hash(file)));
            }
            byte[] manifest=json(Map.of("format","sbrepl-reproduction","schemaVersion",1,"entries",entries));
            SnapshotIO.atomicWrite(destination.toAbsolutePath().normalize(),false,output -> {
                try(ZipOutputStream zip=new ZipOutputStream(output)) {
                    zip.putNextEntry(new ZipEntry("manifest.json")); zip.write(manifest); zip.closeEntry();
                    for(int i=0;i<entries.size();i++) {
                        zip.putNextEntry(new ZipEntry(entries.get(i).get("path").toString())); Files.copy(directory.resolve(i+".json"),zip); zip.closeEntry();
                    }
                }
            });
        } catch(IOException failure) { throw new IllegalStateException("Bundle export failed; previous destination preserved",failure); }
        finally { remove(directory); }
    }
    static Map<String,Object> importFile(Path source, String requestedName) {
        Path directory=temporary(); List<String> created=new ArrayList<>();
        try {
            Map<String,Path> files=new LinkedHashMap<>(); long total=0;
            try(InputStream input=SnapshotIO.input(source,SnapshotLimits.MAX_BYTES); ZipInputStream zip=new ZipInputStream(input)) {
                for(ZipEntry entry; (entry=zip.getNextEntry())!=null;) {
                    String name=entry.getName();
                    if(entry.isDirectory() || !(name.equals("manifest.json") || name.matches("snapshots/[0-4]\\.json")) || files.containsKey(name))
                        throw new IOException("Unexpected or duplicate bundle entry");
                    Path file=directory.resolve(String.valueOf(files.size()));
                    long size=0;
                    try(OutputStream output=Files.newOutputStream(file,StandardOpenOption.CREATE_NEW)) {
                        byte[] buffer=new byte[8192];
                        for(int count; (count=zip.read(buffer))!=-1;) {
                            total+=count; size+=count;
                            if(total>SnapshotLimits.MAX_BYTES || name.equals("manifest.json") && size>65536)throw new IOException("Bundle decompression limit exceeded");
                            output.write(buffer,0,count);
                        }
                    }
                    files.put(name,file); zip.closeEntry();
                }
            }
            if(files.size()!=6 || !files.containsKey("manifest.json"))throw new IOException("Incomplete reproduction bundle");
            Map<?,?> manifest=map(files.get("manifest.json"));
            if(!"sbrepl-reproduction".equals(manifest.get("format")) || !"1".equals(String.valueOf(manifest.get("schemaVersion"))) || !(manifest.get("entries") instanceof List<?> entries) || entries.size()!=5)
                throw new IOException("Unsupported reproduction bundle schema");
            Map<String,Map<String,Object>> decoded=new LinkedHashMap<>(); Set<String> usedPaths=new HashSet<>();
            for(Object item:entries) {
                if(!(item instanceof Map<?,?> entry))throw new IOException("Invalid manifest entry");
                String role=String.valueOf(entry.get("role")); Path file=files.get(String.valueOf(entry.get("path")));
                if(!ROLES.contains(role) || decoded.containsKey(role) || !usedPaths.add(String.valueOf(entry.get("path"))) || file==null || file.equals(files.get("manifest.json")) || !hash(file).equals(entry.get("sha256"))
                        || !String.valueOf(Files.size(file)).equals(String.valueOf(entry.get("size"))))throw new IOException("Bundle checksum, size or role mismatch");
                Map<String,Object> envelope=new LinkedHashMap<>(); map(file).forEach((key,value)->envelope.put(String.valueOf(key),value));
                String kind=role.equals("case")?"CASE":role.equals("recipe")?"RECIPE":"DATA";
                if(!kind.equals(envelope.get("kind")) || !"1".equals(String.valueOf(envelope.get("schemaVersion"))))throw new IOException("Invalid bundle snapshot kind/version");
                decoded.put(role,envelope);
            }
            String base=unique(requestedName); Map<String,String> names=names(base);
            for(String role:ROLES) {
                Map<String,Object> envelope=decoded.get(role);
                if(role.equals("case")) {
                    if(!(envelope.get("payload") instanceof Map<?,?> raw))throw new IOException("Invalid CASE payload");
                    Map<String,String> definition=new LinkedHashMap<>();
                    for(var item:raw.entrySet()) { if(!(item.getValue() instanceof String text))throw new IOException("Invalid CASE field"); definition.put(item.getKey().toString(),text); }
                    if(!definition.getOrDefault("parameters-json","").isBlank())throw new IOException("Parameterized CASE needs a newer bundle format; no data imported");
                    definition.put("input",names.get("input")); definition.put("expected",names.get("expected"));
                    envelope.put("payload",SnapshotCases.definition(definition));
                }
                if(role.equals("recipe") && (!(envelope.get("source") instanceof String text) || text.length()>1000000))throw new IOException("Invalid RECIPE source");
                SnapshotManager.importBundleEnvelope(names.get(role),envelope); created.add(names.get(role));
            }
            saveIndex(base,names); created.add(base);
            return Map.of("bundle-id",base,"case",names.get("case"),"value","Imported with new names. No Java, constructors, HTTP or context restoration were executed.");
        } catch(Exception failure) { created.forEach(SnapshotManager::delete); throw new IllegalStateException("Bundle import rejected: "+failure.getMessage(),failure); }
        finally { remove(directory); }
    }
    private static void saveIndex(String base,Map<String,String> names) { SnapshotManager.save(base,Map.of("reproductionVersion",1,"entries",names)); }
    private static String unique(String name) { SnapshotManager.checkedName(name); return name.substring(0,Math.min(name.length(),60))+"-"+UUID.randomUUID().toString().replace("-",""); }
    private static Map<String,String> names(String base) { Map<String,String> result=new LinkedHashMap<>(); ROLES.forEach(role->result.put(role,base+"-"+role)); return result; }
    private static Path temporary() { try { return Files.createTempDirectory("sb-repl-bundle-"); } catch(IOException failure) { throw new IllegalStateException(failure); } }
    private static void remove(Path directory) { try(var files=Files.walk(directory)) { for(Path file:files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file); } catch(IOException ignored) {} }
    private static String hash(Path file) throws IOException {
        try { MessageDigest digest=MessageDigest.getInstance("SHA-256"); try(InputStream input=Files.newInputStream(file)) { byte[] buffer=new byte[8192]; for(int n;(n=input.read(buffer))!=-1;)digest.update(buffer,0,n); } return HexFormat.of().formatHex(digest.digest()); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static Map<?,?> map(Path file) throws IOException { try(InputStream input=Files.newInputStream(file)) { Object value=parse(input); if(value instanceof Map<?,?> map)return map; throw new IOException("Expected a JSON object"); } }
    private static Object parse(InputStream input) throws IOException { try { Object mapper=plainMapper(); return mapper.getClass().getMethod("readValue",InputStream.class,Class.class).invoke(mapper,input,Map.class); } catch(ReflectiveOperationException failure) { throw new IOException("Invalid JSON data",failure); } }
    private static byte[] json(Map<String,Object> value) throws IOException { try { Object mapper=plainMapper(); return (byte[])mapper.getClass().getMethod("writeValueAsBytes",Object.class).invoke(mapper,value); } catch(ReflectiveOperationException failure) { throw new IOException("Cannot encode bundle manifest",failure); } }
    private static Object plainMapper() throws ReflectiveOperationException {
        ClassLoader loader=AppClassPath.loader(ReplBindings.applicationContext());
        if(loader==null)loader=ReproductionBundle.class.getClassLoader();
        Class<?> type=Class.forName("com.fasterxml.jackson.databind.ObjectMapper",true,loader);
        Object mapper=type.getConstructor().newInstance(); // No application modules or polymorphic typing for imported bundle JSON.
        Class<?> feature=Class.forName("com.fasterxml.jackson.databind.DeserializationFeature",true,loader);
        for(String flag:List.of("FAIL_ON_TRAILING_TOKENS","USE_BIG_DECIMAL_FOR_FLOATS"))
            type.getMethod("configure",feature,boolean.class).invoke(mapper,feature.getField(flag).get(null),true);
        try {
            Class<?> constraints=Class.forName("com.fasterxml.jackson.core.StreamReadConstraints",true,loader);
            Object builder=constraints.getMethod("builder").invoke(null);
            builder.getClass().getMethod("maxStringLength",int.class).invoke(builder,SnapshotLimits.MAX_BYTES);
            builder.getClass().getMethod("maxNestingDepth",int.class).invoke(builder,100);
            Object factory=type.getMethod("getFactory").invoke(mapper);
            factory.getClass().getMethod("setStreamReadConstraints",constraints).invoke(factory,builder.getClass().getMethod("build").invoke(builder));
        } catch(ClassNotFoundException ignored) { /* Older Jackson keeps its own parser constraints. */ }
        return mapper;
    }
}
