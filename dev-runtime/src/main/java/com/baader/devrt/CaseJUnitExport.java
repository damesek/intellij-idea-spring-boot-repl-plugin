package com.baader.devrt;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.lang.model.SourceVersion;

/** Creates ordinary, reviewable JUnit sources and JSON resources; never evaluates CASE code. */
final class CaseJUnitExport {
    static Map<String,String> files(String name,String packageName,String className,Map<String,String> settings) {
        if(!SourceVersion.isName(packageName)||packageName.length()>200||!SourceVersion.isIdentifier(className)||SourceVersion.isKeyword(className)||className.length()>100)
            throw new IllegalArgumentException("Choose a valid Java package and class name");
        Map<String,String> definition=SnapshotCases.definition(SnapshotManager.loadCase(name));
        SnapshotCases.requireData(definition);
        if(definition.get("type").isBlank())throw new IllegalArgumentException("Set and save an explicit input type before JUnit export");
        if(definition.get("result-expression").isBlank())throw new IllegalArgumentException("Move the final value into Result expression, keep Java statements in Code, and save before JUnit export");
        String imports=definition.get("imports");
        for(String statement:imports.split(";")) if(!statement.isBlank()&&!statement.trim().matches("import\\s+(static\\s+)?[\\w.$]+(\\.\\*)?"))throw new IllegalArgumentException("Imports must contain Java import statements only");
        if(!imports.isBlank()&&!imports.stripTrailing().endsWith(";"))throw new IllegalArgumentException("Finish imports with a semicolon");
        if(SnapshotCases.source(definition).contains("com.baader.devrt.")||definition.get("code").matches("(?s).*\\bimport\\s+.*"))throw new IllegalArgumentException("Move imports to Imports and replace agent/JShell helpers with application Java before export");
        ExecutionPolicy policy=ExecutionPolicy.from(settings);
        String resource="sbrepl/"+packageName.replace('.','/')+"/"+className;
        String prefix="src/test/resources/"+resource+"/";
        String javaPrefix="src/test/java/"+packageName.replace('.','/')+"/";
        Map<String,String> files=new LinkedHashMap<>();List<String> arguments=new ArrayList<>();int i=0;
        for(Map<String,String> row:SnapshotCases.rows(definition)) {
            String input=i+"-input.json",expected=i+"-expected.json";
            files.put(prefix+input,CaseJson.write(SnapshotManager.exportPayload(row.get("input"))));
            files.put(prefix+expected,CaseJson.write(SnapshotManager.exportPayload(row.get("expected"))));
            checkSize(files);
            arguments.add("org.junit.jupiter.params.provider.Arguments.of("+quote(row.get("id"))+", "+quote("/"+resource+"/"+input)+", "+quote("/"+resource+"/"+expected)+")");i++;
        }
        files.put(prefix+"assertions.json",definition.get("assertions-json").isBlank()?"{}":definition.get("assertions-json"));
        String assertions=className+"Assertions";
        try(InputStream source=CaseJUnitExport.class.getResourceAsStream("/case-export/CaseAssertions.java")) {
            if(source==null)throw new IllegalStateException("JUnit assertion source is missing from the runtime package");
            String helper=new String(source.readAllBytes(),StandardCharsets.UTF_8).replace("package com.baader.devrt;","package "+packageName+";").replace("CaseAssertions",assertions);
            files.put(javaPrefix+assertions+".java",helper);
        } catch(IOException failure){throw new UncheckedIOException(failure);}
        String annotations="";
        if("true".equals(definition.get("disabled")))annotations+="@org.junit.jupiter.api.Disabled(\"Disabled in saved CASE\")\n";
        for(String tag:definition.get("tags").split(","))if(!tag.isBlank())annotations+="@org.junit.jupiter.api.Tag("+quote(tag.trim())+")\n";
        String source="""
                package %s;
                %s
                @org.springframework.boot.test.context.SpringBootTest
                @org.junit.jupiter.api.parallel.Execution(org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD)
                %sclass %s {
                    @org.springframework.beans.factory.annotation.Autowired
                    org.springframework.context.ApplicationContext ctx;
                    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                        .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

                    private boolean setupCompleted;
                    private long codeStarted;
                    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> cases() {
                        return java.util.stream.Stream.of(%s);
                    }
                    @org.junit.jupiter.params.ParameterizedTest(name="{0}")
                    @org.junit.jupiter.params.provider.MethodSource("cases")
                    @org.junit.jupiter.api.Timeout(value=%d, unit=java.util.concurrent.TimeUnit.MILLISECONDS, threadMode=org.junit.jupiter.api.Timeout.ThreadMode.SAME_THREAD)
                    void reproduces(String label, String inputPath, String expectedPath) throws Throwable {
                        // Snapshot codecs/mix-ins are not automatically transferred; adapt mapper for your DTOs if needed.
                        org.springframework.transaction.TransactionStatus status = null;
                        org.springframework.transaction.PlatformTransactionManager manager = null;
                        if (!%s.equals("LIVE")) {
                            String selectedManager = %s;
                            if (selectedManager.isEmpty()) {
                                String[] candidates = ctx.getBeanNamesForType(org.springframework.transaction.PlatformTransactionManager.class, false, false);
                                if (candidates.length != 1) throw new IllegalStateException("Select a unique transaction manager in the export settings");
                                selectedManager = candidates[0];
                            }
                            manager = ctx.getBean(selectedManager, org.springframework.transaction.PlatformTransactionManager.class);
                            var tx = new org.springframework.transaction.support.DefaultTransactionDefinition();
                            tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                            tx.setReadOnly(%s.equals("READ_ONLY"));
                            tx.setTimeout(%d);
                            status = manager.getTransaction(tx);
                        }
                        try {
                            %s input = mapper.readValue(read(inputPath), new com.fasterxml.jackson.core.type.TypeReference<%s>() {});
                            Object actual = null; Throwable thrown = null;
                            try {
                                setupCompleted = false;
                                try { actual = exercise(input); } catch(Throwable failure) { thrown = failure; }
                                if (!setupCompleted) throw new AssertionError("CASE setup failed", thrown);
                                long duration = (System.nanoTime()-codeStarted)/1_000_000;
                                if (%s.isEmpty()) {
                                    if (thrown != null) throw thrown;
                                    Object expected = mapper.readValue(read(expectedPath), Object.class);
                                    Object actualJson = mapper.readValue(mapper.writeValueAsBytes(actual), Object.class);
                                    var options = mapper.readValue(read(%s), new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String,Object>>() {});
                                    var report = %s.compare(expected, actualJson, options);
                                    org.assertj.core.api.Assertions.assertThat(report.outcome()).withFailMessage(report.detail()+" "+report.failures()).isEqualTo("PASSED");
                                } else {
                                    org.assertj.core.api.Assertions.assertThat(thrown).withFailMessage("Expected exception").isNotNull();
                                    org.assertj.core.api.Assertions.assertThat(thrown.getClass().getName()).isEqualTo(%s);
                                    org.assertj.core.api.Assertions.assertThat(java.util.Objects.toString(thrown.getMessage(), "")).isEqualTo(%s);
                                }
                                if (%dL > 0) org.assertj.core.api.Assertions.assertThat(duration).as("Maximum code duration (ms)").isLessThanOrEqualTo(%dL);
                            } finally { if (!Thread.currentThread().isInterrupted()) cleanup(input); }
                        } finally {
                            if (status != null) {
                                if (status.isCompleted()) throw new IllegalStateException("Transaction was completed inside CASE; rollback cannot be confirmed");
                                manager.rollback(status);
                            }
                        }
                    }
                    private Object exercise(%s %s) throws Throwable {
                        %s
                        return (%s);
                    }
                    private void cleanup(%s %s) throws Throwable {
                        %s
                    }
                    private byte[] read(String path) throws java.io.IOException {
                        try (var stream = getClass().getResourceAsStream(path)) {
                            if(stream == null) throw new java.io.IOException("Missing resource: " + path);
                            return stream.readAllBytes();
                        }
                    }
                }
                """.formatted(packageName,imports,annotations,className,String.join(",\n            ",arguments),policy.timeoutMillis,
                quote(policy.mode.name()),quote(policy.manager),quote(policy.mode.name()),(policy.timeoutMillis+999)/1000,
                definition.get("type"),boxed(definition.get("type")),quote(definition.get("expected-exception")),quote("/"+resource+"/assertions.json"),assertions,
                quote(definition.get("expected-exception")),quote(definition.get("expected-message")),number(definition),number(definition),
                definition.get("type"),definition.get("variable"),definition.get("setup")+"\n setupCompleted = true; codeStarted = System.nanoTime();\n"+definition.get("code"),definition.get("result-expression"),
                definition.get("type"),definition.get("variable"),definition.get("teardown"));
        if(!definition.get("max-sql-count").isBlank() || !definition.get("max-sql-repetitions").isBlank()) {
            String counter=className+"SqlCounter", normalizer=className+"SqlText";
            for(String helper:List.of("CaseSqlCounter","SqlText")) {
                try(InputStream stream=CaseJUnitExport.class.getResourceAsStream("/case-export/"+helper+".java")) {
                    if(stream==null) throw new IllegalStateException("Missing portable SQL assertion source");
                    String text=new String(stream.readAllBytes(),StandardCharsets.UTF_8)
                        .replace("package com.baader.devrt;","package "+packageName+";")
                        .replace("package hu.baader.repl.protocol;","package "+packageName+";")
                        .replace("hu.baader.repl.protocol.SqlText",normalizer).replace("CaseSqlCounter",counter);
                    if(helper.equals("SqlText")) text=text.replace("SqlText",normalizer);
                    files.put(javaPrefix+(helper.equals("SqlText")?normalizer:counter)+".java",text);
                } catch(IOException failure) { throw new UncheckedIOException(failure); }
            }
            String config="""
                    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
                    static class SqlCaptureConfiguration {
                        @org.springframework.context.annotation.Bean
                        static org.springframework.beans.factory.config.BeanPostProcessor captureDataSources() {
                            return new org.springframework.beans.factory.config.BeanPostProcessor() {
                                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                                    return bean instanceof javax.sql.DataSource ds ? %s.wrap(ds) : bean;
                                }
                            };
                        }
                    }
                    private %s.Measurement sqlMeasurement;
                    """.formatted(counter,counter);
            source=source.replace("@org.springframework.boot.test.context.SpringBootTest", "@org.springframework.context.annotation.Import("+className+".SqlCaptureConfiguration.class)\n@org.springframework.boot.test.context.SpringBootTest")
                .replace("class "+className+" {","class "+className+" {\n"+config)
                .replace("setupCompleted = true; codeStarted = System.nanoTime();","setupCompleted = true; codeStarted = System.nanoTime(); sqlMeasurement = new "+counter+".Measurement();")
                .replace("try { actual = exercise(input); } catch(Throwable failure) { thrown = failure; }", "try { actual = exercise(input); } catch(Throwable failure) { thrown = failure; } finally { if(sqlMeasurement!=null) sqlMeasurement.close(); }")
                .replace("long duration = (System.nanoTime()-codeStarted)/1_000_000;", "long duration = (System.nanoTime()-codeStarted)/1_000_000;\n"+
                    "if(ctx.getBeansOfType(javax.sql.DataSource.class).isEmpty()) throw new AssertionError(\"SQL assertions require Spring-managed DataSource beans\");\n"+
                    "sqlMeasurement.assertLimits("+(definition.get("max-sql-count").isBlank()?"-1":definition.get("max-sql-count"))+"L, "+
                    (definition.get("max-sql-repetitions").isBlank()?"-1":definition.get("max-sql-repetitions"))+"L);");
        }
        if(SnapshotCases.hibernateAssertions(definition)) {
            String helper=className+"HibernateProbe";
            try(InputStream stream=CaseJUnitExport.class.getResourceAsStream("/case-export/CaseHibernateProbe.java")) {
                if(stream==null)throw new IllegalStateException("Missing Hibernate JUnit probe source");
                files.put(javaPrefix+helper+".java",new String(stream.readAllBytes(),StandardCharsets.UTF_8).replace("package com.baader.devrt;","package "+packageName+";").replace("CaseHibernateProbe",helper));
            }catch(IOException e){throw new UncheckedIOException(e);}
            source=source.replace("class "+className+" {","class "+className+" {\nprivate "+helper+" hibernateMeasurement;")
                    .replace("setupCompleted = true; codeStarted = System.nanoTime();","setupCompleted = true; codeStarted = System.nanoTime(); hibernateMeasurement = new "+helper+"();")
                    .replace("if (!setupCompleted)","if(hibernateMeasurement!=null) hibernateMeasurement.close();\nif (!setupCompleted)")
                    .replace("long duration = (System.nanoTime()-codeStarted)/1_000_000;","long duration = (System.nanoTime()-codeStarted)/1_000_000;\n"+
                            "if(hibernateMeasurement==null) throw new AssertionError(\"Hibernate capture did not start\",thrown);\n"+
                            "hibernateMeasurement.assertLimits("+String.join(",",SnapshotCases.HIBERNATE_FIELDS.stream().map(k->(definition.get(k).isBlank()?"-1":definition.get(k))+"L").toList())+");");
            files.put("runtime/sb-repl-agent.jar","Binary: matching agent included by ZIP export; not an executable source preview.");
        }
        validateSyntax(className,source);
        files.put(javaPrefix+className+".java",source);
        files.put("README-sbrepl-"+className+".md","""
                # Exported CASE: %s

                Copy src/test/java and src/test/resources into your application's test source tree.
                Requires Java 17+, spring-boot-starter-test (JUnit Jupiter + AssertJ), Jackson databind and spring-tx.
                Run: ./mvnw -Dtest=%s test (or ./gradlew test --tests '%s.%s').
                JUnit XML reports are produced by the normal build; no IDE or REPL agent is required.

                Execution mode: %s. Transaction manager: %s (blank requires exactly one manager).
                %s

                Review the application test profile and external services before running. No active runtime profiles or secrets are silently enabled.
                JSON resources are copied data, not redacted; inspect before sharing or committing.
                The portable assertion helper is the same source as the runtime assertion engine.
                This is a source export: Java syntax is checked, but types/dependencies must be compiled in your application.
                Setup precedes code inside exercise(); cleanup can refer to the input and ctx, not exercise-local variables.
                The generated duration and SQL assertions include code and result expression, excluding setup/cleanup/JSON comparison.
                SQL assertions use the included DataSource proxy helper, without a REPL agent. All JDBC access must use Spring-managed DataSource beans.
                Direct DriverManager connections, unwrapped native JDBC objects, asynchronous work and concrete datasource injection need adaptation.
                A JDBC batch counts as one execution; these counters do not measure database-internal plans or result row counts.
                App-specific Jackson serializers, mix-ins, autowired aliases and JShell-only declarations need manual adaptation.
                SAME_THREAD timeout is cooperative; arbitrary I/O, threads and independent transactions cannot be undone.
                """.formatted(name,className,packageName,className,policy.mode.name(),policy.manager,ExecutionPolicy.LIMITS));
        if(SnapshotCases.hibernateAssertions(definition)) {
            String readme="README-sbrepl-"+className+".md";
            files.put(readme,files.get(readme).replace("no IDE or REPL agent is required","no IDE is required; Hibernate assertions REQUIRE the matching agent")+"""

                    ## Hibernate assertions

                    This case includes ORM limits that must not be silently dropped. Hibernate 6.6 is required.
                    ZIP export includes runtime/sb-repl-agent.jar. Start the test JVM with:
                    -javaagent:/absolute/path/to/runtime/sb-repl-agent.jar=port=0
                    For Maven Surefire, add this option to argLine, preserving existing options (e.g. JaCoCo).
                    For Gradle, add it to the Test task's jvmArgs. Do not add it only to the build daemon.
                    The agent opens an authenticated local REPL endpoint; use only in the intended development/test environment.
                    The ORM measurement covers Code and Result expression, excluding setup, cleanup and JSON comparison.
                    Lazy counts include entity proxies, persistent collections and enhanced attributes. ORM durations overlap SQL.
                    Missing instrumentation, unsupported Hibernate or incomplete evidence fails the assertion.
                    These ORM assertions cannot run agent-free; ordinary SQL-only exports remain agent-free.
                    """);
        }
        checkSize(files);
        return files;
    }
    private static void checkSize(Map<String,String> files) {
        if(files.values().stream().mapToLong(s->s.getBytes(StandardCharsets.UTF_8).length).sum()>16*1024*1024)throw new IllegalArgumentException("JUnit source/resource export exceeds 16 MiB; use a smaller DATA fixture");
    }
    static Map<String,Object> preview(String name,String packageName,String className,Map<String,String> settings,String file) {
        Map<String,String> files=files(name,packageName,className,settings);
        if(file==null||file.isBlank())return Map.of("value",String.join("\n",files.keySet()),"detail","File manifest. Request one exact file name to read its source; no code executed.");
        if(!files.containsKey(file))throw new IllegalArgumentException("Choose a file from the export manifest");
        String content=files.get(file);
        if(content.length()>65536)throw new IllegalArgumentException("File exceeds 64 KiB inline limit; use IDE ZIP export");
        return Map.of("value",content,"file",file);
    }
    static void export(String name,String packageName,String className,Map<String,String> settings,Path destination) throws IOException {
        Map<String,String> files=files(name,packageName,className,settings);
        byte[] agent=files.containsKey("runtime/sb-repl-agent.jar")?exportAgent():null;
        SnapshotIO.atomicWrite(destination.toAbsolutePath().normalize(),false,output->{
            ZipOutputStream zip=new ZipOutputStream(output,StandardCharsets.UTF_8);
            for(var entry:files.entrySet()) {zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getKey().equals("runtime/sb-repl-agent.jar")?agent:entry.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
            zip.finish();
        });
    }
    private static byte[] exportAgent() throws IOException {
        try {
            Path path=Path.of(Agent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if(!Files.isRegularFile(path))path=Path.of(System.getProperty("sb.repl.agentJar",""));
            if(!Files.isRegularFile(path)||Files.size(path)>16*1024*1024)throw new IOException("Matching packaged agent is unavailable for Hibernate JUnit export");
            try(var jar=new java.util.jar.JarFile(path.toFile())) {
                if(!"com.baader.devrt.Agent".equals(jar.getManifest().getMainAttributes().getValue("Premain-Class")))throw new IOException("Invalid REPL agent package");
            }
            return Files.readAllBytes(path);
        }catch(java.net.URISyntaxException e){throw new IOException(e);}
    }
    private static String boxed(String type){return switch(type){case "int"->"java.lang.Integer";case "long"->"java.lang.Long";case "double"->"java.lang.Double";case "float"->"java.lang.Float";case "boolean"->"java.lang.Boolean";case "byte"->"java.lang.Byte";case "short"->"java.lang.Short";case "char"->"java.lang.Character";default->type;};}
    private static long number(Map<String,String> definition){String value=definition.get("max-duration-ms");return value.isBlank()?0:Long.parseLong(value);}
    private static String quote(String value){return CaseJson.write(value);}
    private static void validateSyntax(String name,String source) {
        var diagnostics=new javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>();
        var compiler=javax.tools.ToolProvider.getSystemJavaCompiler();if(compiler==null)throw new IllegalStateException("JUnit export requires a full JDK");
        var unit=new javax.tools.SimpleJavaFileObject(java.net.URI.create("string:///"+name+".java"),javax.tools.JavaFileObject.Kind.SOURCE){public CharSequence getCharContent(boolean ignore){return source;}};
        try(var manager=compiler.getStandardFileManager(diagnostics,null,StandardCharsets.UTF_8)) {
            ((com.sun.source.util.JavacTask)compiler.getTask(null,manager,diagnostics,List.of("-proc:none"),null,List.of(unit))).parse();
            if(diagnostics.getDiagnostics().stream().anyMatch(d->d.getKind()==javax.tools.Diagnostic.Kind.ERROR))throw new IllegalArgumentException("CASE is not valid Java method source. Move final value to Result expression, imports to Imports, and use Java statements in Code: "+diagnostics.getDiagnostics().get(0).getMessage(Locale.ROOT));
        } catch(IOException failure){throw new UncheckedIOException(failure);}
    }
}
