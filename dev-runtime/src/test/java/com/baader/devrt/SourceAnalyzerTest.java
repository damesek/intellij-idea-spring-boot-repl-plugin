package com.baader.devrt;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.GenericApplicationContext;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SourceAnalyzerTest {
    @TempDir Path temp;
    private static final String MARKER="sb.repl.test.analysis-side-effect";
    @AfterEach void clearMarker() { System.clearProperty(MARKER); }
    private String diagnostics(Map<String,Object> result) { return (String)result.get("diagnostics"); }
    private void clean(Map<String,Object> result) {
        assertEquals("",diagnostics(result),result.toString());
        assertEquals(false,result.get("analysis-limited")); assertEquals(false,result.get("context-incomplete"));
        assertEquals(false,result.get("analysis-executed"));
    }
    @Test void analyzingStatementsConstructorsAndStaticInitializersNeverExecutesThem() {
        try(JShellSession shell=new JShellSession(null)) {
            String source="System.setProperty(\""+MARKER+"\", \"statement\");\n"
                    +"class Dangerous { static { System.setProperty(\""+MARKER+"\", \"static\"); } Dangerous() { System.setProperty(\""+MARKER+"\", \"constructor\"); } }\n"
                    +"new Dangerous();\njava.nio.file.Files.writeString(java.nio.file.Path.of(\""+temp.resolve("must-not-exist")+"\"), \"bad\");";
            clean(shell.analyze(source)); assertNull(System.getProperty(MARKER)); assertFalse(Files.exists(temp.resolve("must-not-exist")));
            assertTrue(shell.eval("new Dangerous()").error().contains("Dangerous"),"Analysis declarations leaked into the working session");
        }
    }
    @Test void sessionVariablesImportsMethodsAndTypesAreAvailableWithoutReplayingInitializers() {
        try(JShellSession shell=new JShellSession(null)) {
            shell.eval("import java.math.BigDecimal; int preserved = 7; int twice(int x) { return x*2; } class Item { String name; }");
            System.setProperty(MARKER,"first");
            shell.eval("var recorded = System.setProperty(\""+MARKER+"\", \"once\");");
            clean(shell.analyze("BigDecimal.valueOf(twice(preserved)); new Item(); recorded.length()"));
            assertEquals("once",System.getProperty(MARKER));
            assertEquals("7",shell.eval("preserved").values().get(0));
        }
    }
    @Test void precedingUnexecutedWorkbookCellsProvideTypesWithoutChangingSessionValues() {
        try(JShellSession shell=new JShellSession(null)) {
            shell.eval("int count=5;");
            clean(shell.analyze("// %% Setup\nvar names = new java.util.ArrayList<String>();\n// %% Use\nnames.add(\"one\"); count = 99;"));
            assertEquals("5",shell.eval("count").values().get(0));
            assertTrue(shell.eval("names").error().contains("names"));
            assertTrue(diagnostics(shell.analyze("names.size()")).contains("names"),"Previous analysis polluted a later check");
        }
    }
    @Test void syntaxAndTypeErrorsUseDocumentOffsetsIncludingUnicodeAndPreviousSnippets() {
        try(JShellSession shell=new JShellSession(null)) {
            String source="String unicode=\"😀Á\";\nint number = missing;";
            String row=diagnostics(shell.analyze(source)).lines().filter(s->s.contains("missing")).findFirst().orElseThrow();
            String[] fields=row.split("\t",4);
            assertEquals(source.indexOf("missing"),Integer.parseInt(fields[1]));
            assertEquals("missing",source.substring(Integer.parseInt(fields[1]),Integer.parseInt(fields[2])));
            assertFalse(diagnostics(shell.analyze("int x = \"wrong\";")).isBlank());
            assertTrue(diagnostics(shell.analyze("if (true) {")).contains("Incomplete"));
        }
    }
    @Test void genericSessionVariablesAreCheckedAndAnalysisDoesNotReplaceLastResult() {
        try(JShellSession shell=new JShellSession(null)) {
            var evaluated=shell.eval("var names = new java.util.ArrayList<String>(); names");
            Object before=shell.value(evaluated.handle(),null);
            assertFalse(diagnostics(shell.analyze("names.add(123)")).isEmpty());
            assertSame(before,shell.value(null,null));
            clean(shell.analyze("names.add(\"value\")")); assertEquals(0,((List<?>)before).size());
        }
    }
    @Test void springContextAndBoundObjectTypesAreCheckedWithoutLookingUpBeans() {
        try(GenericApplicationContext context=new GenericApplicationContext()) {
            context.registerBean("probe",Object.class,()->{throw new AssertionError("Bean was instantiated");});
            // No refresh: the compiler only needs the API type, not a bean instance.
            try(JShellSession shell=new JShellSession(context)) {
                clean(shell.analyze("ctx.getBean(\"probe\"); ctx.getBeanDefinitionCount()"));
                assertTrue(diagnostics(shell.analyze("ctx.notAnApplicationContextMethod()")).contains("notAnApplicationContextMethod"));
            }
        }
    }
    @Test void droppedVariablesAndOversizedSourcesAreHandledExplicitly() {
        try(JShellSession shell=new JShellSession(null)) {
            shell.eval("int removable = 1;"); clean(shell.analyze("removable + 1"));
            shell.drop("removable"); assertTrue(diagnostics(shell.analyze("removable + 1")).contains("removable"));
            assertThrows(IllegalArgumentException.class,()->shell.analyze(" ".repeat(SourceAnalyzer.MAX_SOURCE+1)));
        }
    }
    @Test void unresolvedMethodDependenciesAreReportedButLaterWorkbookDefinitionsResolveThem() {
        try(JShellSession shell=new JShellSession(null)) {
            assertTrue(diagnostics(shell.analyze("int total() { return absent + 1; }")).contains("absent"));
            clean(shell.analyze("int total() { return supplied + 1; }\nint supplied = 3;\ntotal()"));
        }
    }
    @Test void protocolAnalysisIsAdditiveAndNeverCreatesValuesInTheSession() {
        try(ReplHandler handler=new ReplHandler()) {
            String session=handler.createSession();
            var reply=handler.handle("analyze",Map.of("session",session,"code","int analysisOnly=1; analysisOnly+1"));
            assertFalse(hu.baader.repl.protocol.ReplProtocol.error(reply),reply.toString());
            assertEquals(false,reply.get("analysis-executed"));
            var variables=handler.handle("vars/list",Map.of("session",session));
            assertFalse(variables.toString().contains("analysisOnly"));
        }
    }
    @Test void annotationProcessorsFromTheApplicationClasspathAreDisabled() throws Exception {
        String marker="sb.repl.test.analysis-processor";
        System.clearProperty(marker);
        Path jar=temp.resolve("processor.jar");
        String processor="hu.baader.repl.fixture.AnalysisProcessorProbe";
        try(var out=new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new java.util.jar.JarEntry("META-INF/services/javax.annotation.processing.Processor"));
            out.write(processor.getBytes(java.nio.charset.StandardCharsets.UTF_8)); out.closeEntry();
            String resource=processor.replace('.','/')+".class";
            out.putNextEntry(new java.util.jar.JarEntry(resource));
            try(var in=getClass().getClassLoader().getResourceAsStream(resource)) { in.transferTo(out); }
            out.closeEntry();
        }
        try(jdk.jshell.JShell live=jdk.jshell.JShell.builder().executionEngine("local").build()) {
            var result=SourceAnalyzer.analyze(live,List.of(jar.toString()),"@Deprecated class Annotated {}\nnew Annotated();");
            assertFalse(diagnostics(result).contains("ERROR"),result.toString());
            assertTrue(diagnostics(result).contains("WARNING"),"Compiler warnings should remain visible");
            assertEquals(false,result.get("analysis-executed"));
            assertNull(System.getProperty(marker));
        } finally { System.clearProperty(marker); }
    }
}
