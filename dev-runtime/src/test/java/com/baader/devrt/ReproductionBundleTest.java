package com.baader.devrt;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

class ReproductionBundleTest {
    @TempDir Path home;
    String previous;
    @BeforeEach void setup() { previous=System.getProperty("user.home"); System.setProperty("user.home",home.toString()); SpringContextHolder.set(null); SnapshotManager.clearLive(); RuntimeEvents.register("test"); }
    @AfterEach void cleanup() { RuntimeEvents.release("test"); SnapshotTriggers.clear(); SnapshotManager.clearLive(); System.setProperty("user.home",previous); System.clearProperty("sb.repl.reproduction.probe"); }
    private Map<String,Object> create(boolean failure) {
        SnapshotManager.save("original",Map.of("x",4));
        try(JShellSession shell=new JShellSession(null)) {
            shell.bindValue("input",Map.of("x",4),"java.util.Map");
            String code="System.setProperty(\"sb.repl.reproduction.probe\", \"executed\"); " + (failure?"throw new IllegalArgumentException(\"sample failure\");":"((Number)input.get(\"x\")).intValue()+1");
            var result=shell.eval(code);
            var created=ReproductionBundle.create(Map.of("name","bug-142","input","original"),shell,result,code);
            System.setProperty("sb.repl.reproduction.probe","not rerun");
            return created;
        }
    }
    @Test void roundTripPreservesExecutableCaseAndDoesNotRerunDuringExportImport() {
        Map<String,Object> created=create(false); String id=created.get("bundle-id").toString();
        Path file=home.resolve("bug.sbrepl-bundle"); ReproductionBundle.exportFile(id,file);
        SnapshotManager.save("original",Map.of("x",999)); // The reproduction has an independent immutable-by-name input copy.
        var imported=ReproductionBundle.importFile(file,"imported");
        assertEquals("not rerun",System.getProperty("sb.repl.reproduction.probe"));
        assertNotEquals(created.get("case"),imported.get("case"));
        var result = SnapshotCases.run("test",imported.get("case").toString(),null,shell -> {});
        assertEquals("PASSED", result.get("outcome"), result.toString());
        assertEquals("executed",System.getProperty("sb.repl.reproduction.probe"));
    }
    @Test void exceptionReproductionsAssertTheExactTypeAndMessage() {
        var created=create(true);
        assertEquals("PASSED",SnapshotCases.run("test",created.get("case").toString(),null,shell -> {}).get("outcome"));
        var definition=new LinkedHashMap<>(SnapshotManager.loadCase(created.get("case").toString()));
        definition.put("expected-message","different"); SnapshotManager.saveCase("different-exception",definition);
        assertEquals("FAILED",SnapshotCases.run("test","different-exception",null,shell -> {}).get("outcome"));
    }
    @Test void rejectsZipTraversalAndChecksumMismatchWithoutImportingAnything() throws Exception {
        Path traversal=home.resolve("traversal.sbrepl-bundle");
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(traversal))) { zip.putNextEntry(new ZipEntry("../escaped")); zip.write(1); zip.closeEntry(); }
        assertThrows(IllegalStateException.class,()->ReproductionBundle.importFile(traversal,"bad"));
        assertFalse(Files.exists(home.resolve("escaped")));
        var created=create(false); Path original=home.resolve("original.sbrepl-bundle"); ReproductionBundle.exportFile(created.get("bundle-id").toString(),original);
        Path corrupt=home.resolve("corrupt.sbrepl-bundle");
        try(ZipFile input=new ZipFile(original.toFile()); ZipOutputStream output=new ZipOutputStream(Files.newOutputStream(corrupt))) {
            for(var entry:Collections.list(input.entries())) {
                output.putNextEntry(new ZipEntry(entry.getName()));
                byte[] bytes=input.getInputStream(entry).readAllBytes(); output.write(bytes);
                if(entry.getName().equals("snapshots/0.json"))output.write(' ');
                output.closeEntry();
            }
        }
        var before=SnapshotManager.list();
        assertThrows(IllegalStateException.class,()->ReproductionBundle.importFile(corrupt,"bad"));
        assertEquals(before,SnapshotManager.list());
    }
    @Test void contextMetadataIsCapturedOnTheApplicationThreadAndRedactsExplicitSecrets() {
        SnapshotTriggers.arm("owner","point","input","case-1","",300000,1,1);
        String thread=Thread.currentThread().getName();
        assertTrue(SnapshotManager.capture("point","case-1",()->Map.of("x",1),Map.of("tenant","tenant-7","feature.newFlow","true","Authorization","Bearer private-value")));
        var envelope=SnapshotManager.bundleEnvelope("input");
        var context=(Map<?,?>)envelope.get("executionContext");
        assertEquals(thread,context.get("thread"));
        var metadata=(Map<?,?>)context.get("applicationMetadata");
        assertEquals("tenant-7",metadata.get("tenant")); assertEquals("[REDACTED]",metadata.get("Authorization"));
        assertFalse(SnapshotManager.json("input").contains("private-value"));
    }
    @Test void protocolCreatesFromLastEvaluationAndResetForgetsIt() {
        SnapshotManager.save("input",Map.of("x",4));
        try(ReplHandler handler=new ReplHandler()) {
            String session=handler.createSession();
            var loaded=handler.handle("snapshot/load",Map.of("session",session,"name","input","var","input","type","java.util.Map"));
            assertFalse(hu.baader.repl.protocol.ReplProtocol.error(loaded),loaded.toString());
            var evaluated=handler.handle("eval",Map.of("session",session,"code","((Number)input.get(\"x\")).intValue()+1"));
            assertFalse(hu.baader.repl.protocol.ReplProtocol.error(evaluated),evaluated.toString());
            var created=handler.handle("reproduction/create",Map.of("session",session,"name","protocol-case","input","input"));
            assertFalse(hu.baader.repl.protocol.ReplProtocol.error(created),created.toString());
            assertEquals("PASSED",handler.handle("case/run",Map.of("session",session,"name",created.get("case").toString())).get("outcome"));
            handler.handle("session/reset",Map.of("session",session));
            assertTrue(hu.baader.repl.protocol.ReplProtocol.error(handler.handle("reproduction/create",Map.of("session",session,"name","after-reset","input","input"))));
        }
    }
}
