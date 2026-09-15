package com.baader.devrt;

import hu.baader.repl.protocol.WorkspaceArchive;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceBundleTest {
    @TempDir Path home;String oldHome,oldApp;
    public static class Dto {
        public static int constructors; public int count;
        public Dto(){constructors++;} public Dto(int n){constructors++;count=n;}
    }
    @BeforeEach void setup(){oldHome=System.getProperty("user.home");oldApp=System.getProperty("sb.repl.applicationId");System.setProperty("user.home",home.toString());System.setProperty("sb.repl.applicationId","workspace-test");SpringContextHolder.set(null);SnapshotManager.clearLive();}
    @AfterEach void cleanup(){SnapshotManager.clearLive();System.setProperty("user.home",oldHome);if(oldApp==null)System.clearProperty("sb.repl.applicationId");else System.setProperty("sb.repl.applicationId",oldApp);}
    private Path state(String version) throws Exception {
        Path state=home.resolve("state.json");Files.writeString(state,CaseJson.write(Map.of("formatVersion",1,"notebook",Map.of("text","// %% cell\nthrow new RuntimeException();"),"bindings",version.isEmpty()?List.of():List.of(Map.of("variable","saved","snapshot","input","version",version,"restorable",true)))));return state;
    }
    @Test void portableWorkspaceRemapsParameterizedCasesAndHistoricalBindingsWithoutExecution() throws Exception {
        SnapshotManager.save("input",new Dto(1));String old=SnapshotManager.provenance("input","").get("version").toString();SnapshotManager.save("input",new Dto(2));SnapshotManager.save("expected",2);
        SnapshotManager.saveRecipe("recipe","throw new RuntimeException(\"must not run\");");
        SnapshotManager.saveCase("case",SnapshotCases.definition(Map.of("input","input","expected","expected","code","input.count", "parameters-json","[{\"id\":\"one\",\"input\":\"input\",\"expected\":\"expected\"}]")));
        Dto.constructors=0;Path zip=home.resolve("saved.sbrepl-workspace");WorkspaceBundle.exportFile(zip,state(old),List.of("import java.util.*;"));assertEquals(0,Dto.constructors);
        System.setProperty("sb.repl.applicationId","another-application");
        Map<String,Object> result=WorkspaceBundle.importFile(zip,"imported");assertEquals(0,Dto.constructors);
        Map<String,Object> names=CaseJson.object(result.get("names-json").toString());
        var definition=SnapshotManager.loadCase(names.get("case").toString());assertEquals(names.get("input"),definition.get("input"));assertEquals(names.get("expected"),definition.get("expected"));
        assertTrue(definition.get("parameters-json").contains(names.get("input").toString()));
        var bindings=CaseJson.object(result.get("bindings-json").toString());String historical=bindings.get("input\n"+old).toString();
        assertEquals(1,SnapshotManager.<Dto>load(historical).count);assertEquals(1,Dto.constructors);
        assertEquals("workspace-test",SnapshotManager.provenance(historical,"").get("sourceApplicationId"));
        try(var archive=WorkspaceArchive.read(zip)) {
            Map<String,Object> manifest=CaseJson.object(Files.readString(archive.files().get("manifest.json")));
            assertEquals(5,((List<?>)manifest.get("entries")).size());
        }
    }
    @Test void importCollisionPreservesEveryExistingValue() throws Exception {
        SnapshotManager.save("input",1);SnapshotManager.save("other",2);Path zip=home.resolve("case.zip");WorkspaceBundle.exportFile(zip,state(""),List.of());
        var first=WorkspaceBundle.importFile(zip,"duplicate");int size=SnapshotManager.list().size();
        assertThrows(java.io.IOException.class,()->WorkspaceBundle.importFile(zip,"duplicate"));assertEquals(size,SnapshotManager.list().size());
        var names=CaseJson.object(first.get("names-json").toString());assertEquals(1,(Object)SnapshotManager.load(names.get("input").toString()));
    }
    @Test void checksumTamperingRejectsWholeImportBeforeAnyRepositoryWrite() throws Exception {
        SnapshotManager.save("input",1);Path zip=home.resolve("case.zip");WorkspaceBundle.exportFile(zip,state(""),List.of());
        Path bad=home.resolve("bad.zip");
        try(var archive=WorkspaceArchive.read(zip)){var files=new LinkedHashMap<>(archive.files());Files.writeString(files.get("workspace.json"),"{\"formatVersion\":1}");WorkspaceArchive.write(bad,files);}
        List<String> before=SnapshotManager.list();assertThrows(java.io.IOException.class,()->WorkspaceBundle.importFile(bad,"bad"));assertEquals(before,SnapshotManager.list());
    }
    @Test void archiveTraversalAndDuplicateObjectsNeverExtractIntoTheProject() throws Exception {
        Path zip=home.resolve("traversal.zip");try(var out=new ZipOutputStream(Files.newOutputStream(zip))){out.putNextEntry(new ZipEntry("../outside"));out.write(1);out.closeEntry();}
        assertThrows(java.io.IOException.class,()->WorkspaceArchive.read(zip));assertFalse(Files.exists(home.resolve("outside")));
    }
    @Test void metadataOnlyExportRetainsUnicodeAndNeverRunsSource() throws Exception {
        Path zip=home.resolve("empty.zip");WorkspaceBundle.exportFile(zip,state(""),List.of("import java.time.*;"));
        try(var archive=WorkspaceArchive.read(zip)){String json=Files.readString(archive.files().get("workspace.json"));assertTrue(json.contains("throw new RuntimeException"));assertTrue(json.contains("import java.time.*;"));}
    }
}
