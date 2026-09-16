package com.baader.devrt;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;

class CaseJUnitExportTest {
    @TempDir Path home;
    String oldHome;
    @BeforeEach void setup(){oldHome=System.getProperty("user.home");System.setProperty("user.home",home.toString());SnapshotManager.save("input",1);SnapshotManager.save("expected",1);}
    @AfterEach void cleanup(){System.setProperty("user.home",oldHome);}
    Map<String,String> definition(){return new HashMap<>(Map.of("input","input","expected","expected","type","java.lang.Integer","code","ctx.getBean(org.springframework.jdbc.core.JdbcTemplate.class).update(\"insert into entries values (?)\", input);","result-expression","ctx.getBean(org.springframework.jdbc.core.JdbcTemplate.class).queryForObject(\"select count(*) from entries\", Integer.class)","parameters-json","[{\"id\":\"one\",\"input\":\"input\",\"expected\":\"expected\"},{\"id\":\"two\",\"input\":\"input\",\"expected\":\"expected\"}]"));}
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void exportedTestsCompileAndRunWithRealJUnitSpringRollbackAndAssertionsWithoutTheAgent(boolean sql) throws Exception {
        var saved=definition();saved.put("type","int");saved.put("assertions-json","{\"numericTolerance\":{\"\":0.01}}");
        if(sql) { saved.put("max-sql-count","2");saved.put("max-sql-repetitions","1"); }
        SnapshotManager.saveCase("exported",SnapshotCases.definition(saved));
        var files=CaseJUnitExport.files("exported","com.baader.devrt.exportfixture","GeneratedCaseTest",Map.of("execution-mode","ROLLBACK"));
        Path classes=home.resolve("classes");Files.createDirectory(classes);List<String> sources=new ArrayList<>();
        for(var file:files.entrySet()) {
            Path path;
            if(file.getKey().startsWith("src/test/resources/"))path=classes.resolve(file.getKey().substring("src/test/resources/".length()));
            else path=home.resolve(file.getKey());
            Files.createDirectories(path.getParent());Files.writeString(path,file.getValue());if(path.toString().endsWith(".java"))sources.add(path.toString());
        }
        Set<String> paths=new LinkedHashSet<>(Arrays.asList(System.getProperty("java.class.path").split(java.io.File.pathSeparator)));
        for(ClassLoader loader=getClass().getClassLoader();loader!=null;loader=loader.getParent())if(loader instanceof URLClassLoader urls)for(URL url:urls.getURLs())if(url.getProtocol().equals("file"))paths.add(Path.of(url.toURI()).toString());
        var options=new ArrayList<>(List.of("-proc:none","-classpath",String.join(java.io.File.pathSeparator,paths),"-d",classes.toString()));options.addAll(sources);
        var diagnostics=new java.io.ByteArrayOutputStream();assertEquals(0,javax.tools.ToolProvider.getSystemJavaCompiler().run(null,diagnostics,diagnostics,options.toArray(String[]::new)),diagnostics.toString());
        ClassLoader previous=Thread.currentThread().getContextClassLoader();
        try(URLClassLoader loader=new URLClassLoader(new URL[]{classes.toUri().toURL()},getClass().getClassLoader())) {
            Thread.currentThread().setContextClassLoader(loader);
            Class<?> test=loader.loadClass("com.baader.devrt.exportfixture.GeneratedCaseTest");
            if(sql) {
                String counter=files.get("src/test/java/com/baader/devrt/exportfixture/GeneratedCaseTestSqlCounter.java");
                assertNotNull(counter);assertTrue(files.get("src/test/java/com/baader/devrt/exportfixture/GeneratedCaseTest.java").contains("sqlMeasurement.assertLimits(2L, 1L)"));
                assertFalse(counter.contains("com.baader.devrt.CaseSqlCounter"));
            }
            var summary=run(test);assertEquals(2,summary.getTestsSucceededCount(),summary.getFailures().toString());
            // A wrong expected resource must fail the generated test (not just compile successfully).
            Files.writeString(classes.resolve("sbrepl/com/baader/devrt/exportfixture/GeneratedCaseTest/1-expected.json"),"99");
            var failed=run(test);assertEquals(1,failed.getTestsFailedCount());assertEquals(1,failed.getTestsSucceededCount());
        } finally {Thread.currentThread().setContextClassLoader(previous);}
        assertEquals(1,SnapshotManager.<Integer>load("input"));
    }
    private org.junit.platform.launcher.listeners.TestExecutionSummary run(Class<?> test){var listener=new SummaryGeneratingListener();LauncherFactory.create().execute(LauncherDiscoveryRequestBuilder.request().selectors(DiscoverySelectors.selectClass(test)).build(),listener);return listener.getSummary();}
    @Test void definitionAndDataExportDoNotUseApplicationMapDeserializers() {
        SnapshotManager.saveCase("plain",SnapshotCases.definition(definition()));
        try(var context=new org.springframework.context.support.GenericApplicationContext()) {
            var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
            var module=new com.fasterxml.jackson.databind.module.SimpleModule();
            module.addDeserializer(Map.class,new com.fasterxml.jackson.databind.JsonDeserializer<Map>() {
                public Map deserialize(com.fasterxml.jackson.core.JsonParser parser,com.fasterxml.jackson.databind.DeserializationContext ignored){throw new AssertionError("App deserializer executed during export");}
            });
            mapper.registerModule(module);context.registerBean("objectMapper",com.fasterxml.jackson.databind.ObjectMapper.class,()->mapper);context.refresh();
            SpringContextHolder.set(context);
            try {assertTrue(CaseJUnitExport.files("plain","example","PlainTest",Map.of()).containsKey("src/test/java/example/PlainTest.java"));}
            finally {SpringContextHolder.clear(context);}
        }
    }
    @Test void exportIsNonExecutingValidatesSourceAndLeavesExistingFilesIntactOnFailure() throws Exception {
        var saved=definition();saved.put("code","System.setProperty(\"sbrepl.export.executed\",\"yes\");");SnapshotManager.saveCase("case",SnapshotCases.definition(saved));
        var zip=home.resolve("test.zip");CaseJUnitExport.export("case","example","CaseTest",Map.of(),zip);
        assertNull(System.getProperty("sbrepl.export.executed"));
        try(var archive=new ZipFile(zip.toFile())){assertTrue(archive.size()>=8);assertNotNull(archive.getEntry("src/test/java/example/CaseTestAssertions.java"));}
        byte[] original=Files.readAllBytes(zip);
        saved.put("result-expression","");SnapshotManager.saveCase("case",SnapshotCases.definition(saved));
        assertThrows(IllegalArgumentException.class,()->CaseJUnitExport.export("case","example","CaseTest",Map.of(),zip));
        assertArrayEquals(original,Files.readAllBytes(zip));
        saved.put("result-expression","input");saved.put("code","input");SnapshotManager.saveCase("case",SnapshotCases.definition(saved));
        assertThrows(IllegalArgumentException.class,()->CaseJUnitExport.files("case","example","CaseTest",Map.of()));
        assertThrows(IllegalArgumentException.class,()->CaseJUnitExport.files("case","../bad","CaseTest",Map.of()));
    }
}
