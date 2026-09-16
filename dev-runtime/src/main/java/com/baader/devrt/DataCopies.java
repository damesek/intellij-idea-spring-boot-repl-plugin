package com.baader.devrt;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Editing always starts from detached DATA and commits to a different, unused name. */
final class DataCopies {
    static final int MAX_JSON=2*1024*1024;
    private record Source(Map<String,Object> data,String version){}
    private static Source source(String name) throws Exception {
        return SnapshotVersions.locked(SnapshotManager.path(name),()->{
            SnapshotManager.requireData(name);
            if(SnapshotManager.size(name)>MAX_JSON)throw new IllegalArgumentException("Interactive editing supports DATA up to 2 MiB; export a smaller projection first");
            return new Source(SnapshotManager.detachedEnvelope(name),SnapshotVersions.checksum(SnapshotManager.path(name)));
        });
    }
    static Map<String,Object> read(String name) throws Exception {
        Source source=source(name);Map<String,Object> data=source.data();
        return Map.of("json",CaseJson.write(data.get("payload")),"type",data.get("declaredType"),
                "version",source.version());
    }
    static Map<String,Object> edit(Map<String,String> request,boolean save) throws Exception {
        String from=SnapshotManager.checkedName(request.get("name"));
        Source source=source(from);
        String version=request.get("version");
        if(version==null||!version.equals(source.version()))
            throw new IllegalArgumentException("Source DATA changed; reopen the editor before saving");
        String json=request.get("json");
        if(request.containsKey("json-file")) {
            Path file=Path.of(request.get("json-file"));
            try(var input=SnapshotIO.input(file,MAX_JSON)){json=new String(input.readAllBytes(),StandardCharsets.UTF_8);}
        }
        if(json==null||json.getBytes(StandardCharsets.UTF_8).length>MAX_JSON)throw new IllegalArgumentException("Edited JSON exceeds 2 MiB");
        Map<String,Object> envelope=source.data();
        String type=request.getOrDefault("type",envelope.get("declaredType").toString());
        envelope.put("declaredType",SnapshotManager.checkedType(type));envelope.put("payload",CaseJson.parse(json,MAX_JSON));
        // Runs the selected DTO's deserializer/constructor, but never mutates the original application object.
        SnapshotManager.materialize(envelope,type);
        if(!save)return Map.of("value","JSON can be restored as "+type,"type",type);
        String target=SnapshotManager.checkedName(request.get("target"));
        if(target.equals(from)||Files.exists(SnapshotManager.path(target)))throw new IllegalArgumentException("Choose an unused name for the edited copy");
        if(!version.equals(SnapshotVersions.checksum(SnapshotManager.path(from))))throw new IllegalArgumentException("Source DATA changed during validation; reopen it");
        envelope.put("copiedFrom",from);envelope.put("copiedFromVersion",version);
        envelope.put("capturedAt",java.time.Instant.now().toString());
        SnapshotManager.createNew(Map.of(target,envelope),Map.of(from,version));
        return Map.of("value","Edited DATA copy saved: "+target,"name",target,"type",type);
    }
    static Map<String,Object> variants(Map<String,String> request) {
        String source=SnapshotManager.checkedName(request.get("name")),target=SnapshotManager.checkedName(request.get("target"));
        if(Files.exists(SnapshotManager.path(target)))throw new IllegalArgumentException("Choose an unused CASE name");
        Map<String,String> definition=new LinkedHashMap<>(SnapshotManager.loadCase(source));
        List<String> inputs=request.getOrDefault("inputs","").lines().map(String::trim).filter(s->!s.isEmpty()).toList();
        if(inputs.isEmpty()||inputs.size()>20||new HashSet<>(inputs).size()!=inputs.size())throw new IllegalArgumentException("Select 1–20 distinct DATA inputs");
        List<Map<String,String>> rows=new ArrayList<>();int index=0;
        for(String input:inputs){SnapshotManager.requireData(input);rows.add(Map.of("id","variant-"+(++index),"input",input,"expected",definition.get("expected")));}
        definition.put("parameters-json",CaseJson.write(rows));definition.put("input",inputs.get(0));
        Map<String,Object> envelope=SnapshotManager.envelope(target,"CASE");
        envelope.put("payload",SnapshotCases.definition(definition));
        SnapshotManager.createNew(Map.of(target,envelope),Map.of());
        return Map.of("value","Parameterized CASE saved. Each row initially uses the original expectation; review expected DATA before running.","name",target,"rows",rows.size());
    }
}
