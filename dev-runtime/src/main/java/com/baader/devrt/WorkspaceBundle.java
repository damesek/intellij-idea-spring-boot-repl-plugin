package com.baader.devrt;

import hu.baader.repl.protocol.WorkspaceArchive;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Exports source and immutable snapshot bytes; import stages every entry before creating any current snapshot. */
final class WorkspaceBundle {
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Path p) throws IOException {
        if(Files.isSymbolicLink(p) || Files.size(p)>WorkspaceArchive.METADATA_BYTES)throw new IOException("Workspace metadata exceeds 8 MiB");
        Object value=CaseJson.parse(Files.readString(p),WorkspaceArchive.METADATA_BYTES);
        if(!(value instanceof Map<?,?>))throw new IOException("Expected workspace object");
        return (Map<String,Object>)value;
    }
    static Map<String,Object> exportFile(Path target, Path state, List<String> imports) throws IOException {
        return exportFile(target, state, imports, List.of());
    }
    static Map<String,Object> exportFile(Path target, Path state, List<String> imports, List<String> variables) throws IOException {
        Map<String,Object> doc=object(state);
        if(!"1".equals(String.valueOf(doc.get("formatVersion"))))throw new IOException("Unsupported workspace format");
        doc.put("imports",imports); doc.put("environment",CaseJson.write(ReproductionContext.capture()));
        Set<String> restorable = new HashSet<>();
        if (doc.get("bindings") instanceof List<?> bindings) for (Object item : bindings) if (item instanceof Map<?,?> b && Boolean.TRUE.equals(b.get("restorable"))) restorable.add(Objects.toString(b.get("variable"), ""));
        doc.put("unrestorableVariables", variables.stream().filter(v -> !restorable.contains(v.split("\t", 2)[0])).toList());
        Path temp=WorkspaceArchive.privateDirectory();
        try {
            Map<String,Path> files=new LinkedHashMap<>(); List<Map<String,Object>> entries=new ArrayList<>();
            // Copy stable current bytes while writers and other JVMs are excluded. Serialization happened earlier.
            SnapshotVersions.locked(SnapshotManager.path("workspace-lock"), () -> {
                Map<String,String> names=new LinkedHashMap<>();
                for(String row:SnapshotManager.list()) {
                    String[] p=row.split("\t"); if(p.length>=3 && !p[2].equals("LIVE"))names.put(p[0],SnapshotVersions.checksum(SnapshotManager.path(p[0])));
                }
                for(var entry:names.entrySet())add(temp,files,entries,entry.getKey(),entry.getValue(),true);
                if(doc.get("bindings") instanceof List<?> bindings)for(Object value:bindings) {
                    if(!(value instanceof Map<?,?> b))throw new IOException("Invalid workspace binding");
                    String name=Objects.toString(b.get("snapshot"),""), version=Objects.toString(b.get("version"),"");
                    if(!Boolean.FALSE.equals(b.get("restorable")) && !version.isBlank() && !version.equals(names.get(name)))add(temp,files,entries,name,version,false);
                }
                return null;
            });
            Path workspace=temp.resolve("workspace.json"); Files.writeString(workspace,CaseJson.write(doc)); files.put("workspace.json",workspace);
            Path manifest=temp.resolve("manifest.json"); Files.writeString(manifest,CaseJson.write(Map.of("format","sbrepl-workspace","formatVersion",1,"workspaceSha256",WorkspaceArchive.sha256(workspace),"entries",entries)));
            files.put("manifest.json",manifest); WorkspaceArchive.write(target,files);
            return Map.of("value","Workspace exported; no code executed", "snapshots",entries.size());
        } finally { WorkspaceArchive.removeDirectory(temp); }
    }
    private static void add(Path temp, Map<String,Path> files, List<Map<String,Object>> entries, String name, String version, boolean current) throws IOException {
        if(entries.stream().anyMatch(e->e.get("name").equals(name)&&e.get("version").equals(version)))return;
        Path source=SnapshotVersions.version(SnapshotManager.path(name),version);
        String path="objects/"+version+".json";
        if(!files.containsKey(path)) {
            long size=Files.size(source), total=size;
            for(Path existing:files.values())total+=Files.size(existing);
            if(total>hu.baader.repl.protocol.SnapshotLimits.MAX_BYTES)throw new IOException("Workspace snapshot data exceeds 200 MiB");
            Path copy=temp.resolve(version); Files.copy(source,copy); files.put(path,copy);
        }
        entries.add(Map.of("name",name,"version",version,"path",path,"current",current,"kind",SnapshotManager.metadata(source).get("kind")));
        if(entries.size()>1200)throw new IOException("Workspace snapshot entry limit exceeded");
    }
    static Map<String,Object> importFile(Path source, String prefix) throws IOException {
        SnapshotManager.checkedName(prefix); if(prefix.length()>32)throw new IOException("Import prefix exceeds 32 characters");
        try(WorkspaceArchive.Contents archive=WorkspaceArchive.read(source)) {
            Map<String,Object> manifest=object(archive.files().get("manifest.json"));
            if (!"1".equals(String.valueOf(object(archive.files().get("workspace.json")).get("formatVersion")))) throw new IOException("Unsupported workspace metadata version");
            if(!"sbrepl-workspace".equals(manifest.get("format")) || !"1".equals(String.valueOf(manifest.get("formatVersion"))) ||
                    !WorkspaceArchive.sha256(archive.files().get("workspace.json")).equals(manifest.get("workspaceSha256")) || !(manifest.get("entries") instanceof List<?> entries) || entries.size()>1200)
                throw new IOException("Workspace manifest or metadata checksum mismatch");
            Map<String,String> names=new LinkedHashMap<>(), byVersion=new LinkedHashMap<>();
            Map<String,Path> originals=new LinkedHashMap<>(); Set<String> referenced=new HashSet<>(Set.of("manifest.json","workspace.json"));
            for(Object value:entries) {
                if(!(value instanceof Map<?,?> entry))throw new IOException("Invalid workspace entry");
                String name=SnapshotManager.checkedName(Objects.toString(entry.get("name"),""));
                String version=Objects.toString(entry.get("version"),""), path=Objects.toString(entry.get("path"),"");
                if(!version.matches("[a-f0-9]{64}") || !path.equals("objects/"+version+".json") || !archive.files().containsKey(path) || !(entry.get("current") instanceof Boolean))throw new IOException("Invalid workspace snapshot reference");
                Map<String,Object> header=SnapshotManager.metadata(archive.files().get(path),false);
                if(!name.equals(header.get("name")) || !Objects.equals(entry.get("kind"),header.get("kind")))throw new IOException("Workspace snapshot name/kind mismatch");
                String imported=prefix+"-"+name.substring(0,Math.min(60,name.length()))+"-"+SnapshotManagerNameHash.hash(name).substring(0,8)+(Boolean.TRUE.equals(entry.get("current"))?"":"-v"+version.substring(0,8));
                if(byVersion.putIfAbsent(name+"\n"+version,imported)!=null || originals.putIfAbsent(imported,archive.files().get(path))!=null)throw new IOException("Duplicate workspace snapshot");
                if(Boolean.TRUE.equals(entry.get("current")) && names.putIfAbsent(name,imported)!=null)throw new IOException("Duplicate current snapshot");
                referenced.add(path);
            }
            if(!referenced.equals(archive.files().keySet()))throw new IOException("Unreferenced workspace object");
            Map<Path,Path> prepared=new LinkedHashMap<>(); Map<String,String> versions=new LinkedHashMap<>();
            for(var entry:originals.entrySet()) {
                String name=entry.getKey(); Path original=entry.getValue(); Map<String,Object> header=SnapshotManager.metadata(original,false);
                Object payload=null;
                if("CASE".equals(header.get("kind"))) {
                    Map<String,Object> envelope=object(original);
                    if(!(envelope.get("payload") instanceof Map<?,?> raw))throw new IOException("Invalid workspace CASE");
                    Map<String,String> definition=new LinkedHashMap<>();
                    for(var field:raw.entrySet()) { if(!(field.getKey() instanceof String) || !(field.getValue() instanceof String))throw new IOException("Invalid CASE field"); definition.put((String)field.getKey(),(String)field.getValue()); }
                    definition.put("input", mapped(names,definition.get("input"))); definition.put("expected",mapped(names,definition.get("expected")));
                    if(!definition.getOrDefault("parameters-json","").isBlank()) {
                        Object rows=CaseJson.parse(definition.get("parameters-json")); if(!(rows instanceof List<?> list))throw new IOException("Invalid CASE parameters");
                        List<Map<String,Object>> remapped=new ArrayList<>();
                        for(Object row:list) { if(!(row instanceof Map<?,?> m))throw new IOException("Invalid CASE row");
                            remapped.add(Map.of("id",Objects.toString(m.get("id"),""),"input",mapped(names,Objects.toString(m.get("input"),"")),"expected",mapped(names,Objects.toString(m.get("expected"),"")))); }
                        definition.put("parameters-json",CaseJson.write(remapped));
                    }
                    payload=SnapshotCases.definition(definition);
                }
                Path staging=archive.directory().resolve("prepared-"+prepared.size()); Object replacement=payload;
                SnapshotIO.atomicWrite(staging,false,out->SnapshotEnvelopeIO.rewrite(original,out,Map.of("applicationId",SnapshotManager.applicationId(),"name",name,"sourceApplicationId",header.get("applicationId"),"importedFrom",WorkspaceArchive.sha256(original),"recordedAt",Instant.now().toString()),replacement));
                // Parsing/rewrite checks syntax and preserves plain data; no application type is loaded.
                prepared.put(SnapshotManager.path(name),staging); versions.put(name,WorkspaceArchive.sha256(staging));
            }
            SnapshotVersions.importBatch(prepared);
            return Map.of("value","Workspace snapshots imported under new names; no Java executed", "names-json",CaseJson.write(names),"bindings-json",CaseJson.write(byVersion),"versions-json",CaseJson.write(versions));
        }
    }
    private static String mapped(Map<String,String> names,String source) throws IOException { String result=names.get(source); if(result==null)throw new IOException("CASE references DATA outside this workspace: "+source);return result; }
    private static final class SnapshotManagerNameHash {
        static String hash(String value) { try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); } catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);} }
    }
}
