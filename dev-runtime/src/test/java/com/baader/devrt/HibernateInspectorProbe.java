package com.baader.devrt;

import hu.baader.repl.protocol.ValueTree;
import java.nio.file.*;
import java.net.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Runs in the real packaged-agent process, sharing the runtime's actual metadata registry. */
public final class HibernateInspectorProbe {
    public static void enhanced(Object entity,boolean loaded) {
        var page=new ObjectInspector().start(entity,"enhanced");
        assertEquals(loaded?"":"text",page.get("hibernate-unfetchedAttributes"));
        assertEquals(!loaded,page.get("value").toString().contains("Unfetched Hibernate attribute"));
        var tree=ValueTree.decode(ValuePresentation.present(entity).get("view-data").toString());
        assertEquals(!loaded,tree.children().stream().filter(c->c.label().equals("contents")).flatMap(c->c.children().stream()).anyMatch(c->c.label().equals("text")&&c.kind().equals("LIMIT")));
    }
    public static void inspect(Object entity,Object proxy,Object collection,boolean attached) {
        ObjectInspector inspector=new ObjectInspector();
        var e=inspector.start(entity,"entity");
        assertEquals(attached?"managed":"detached",e.get("hibernate-state"),e.toString());
        var p=inspector.start(proxy,"proxy");assertEquals("false",p.get("hibernate-initialized"));assertEquals(false,p.get("hibernate-expanded"));assertEquals("",p.get("value"));
        var c=inspector.start(collection,"collection");assertEquals("false",c.get("hibernate-initialized"));assertEquals(false,c.get("hibernate-expanded"));assertEquals("",c.get("value"));
        String wire=ValuePresentation.present(entity).get("view-data").toString();
        assertTrue(ValueTree.decode(wire).children().stream().anyMatch(v->v.label().equals("hibernate.state")));
    }
    public static void exportedCase(String name) throws Exception { exportedCase(name,true); }
    public static void exportedCase(String name,boolean pass) throws Exception {
        String pkg="com.baader.devrt.exportfixture", cls="HibernateGeneratedCaseTest";
        var files=CaseJUnitExport.files(name,pkg,cls,Map.of("execution-mode","LIVE"));
        assertTrue(files.containsKey("runtime/sb-repl-agent.jar"));
        Path home=Files.createTempDirectory(Path.of(System.getProperty("user.home")),"hibernate-export-");
        Path classes=Files.createDirectories(home.resolve("classes"));List<String> sources=new ArrayList<>();
        for(var file:files.entrySet()) {
            if(file.getKey().equals("runtime/sb-repl-agent.jar"))continue;
            Path p=file.getKey().startsWith("src/test/resources/")?classes.resolve(file.getKey().substring(19)):home.resolve(file.getKey());
            Files.createDirectories(p.getParent());Files.writeString(p,file.getValue());if(p.toString().endsWith(".java"))sources.add(p.toString());
        }
        List<String> options=new ArrayList<>(List.of("-proc:none","-classpath",System.getProperty("java.class.path"),"-d",classes.toString()));options.addAll(sources);
        var errors=new java.io.ByteArrayOutputStream();assertEquals(0,javax.tools.ToolProvider.getSystemJavaCompiler().run(null,errors,errors,options.toArray(String[]::new)),errors.toString());
        ClassLoader old=Thread.currentThread().getContextClassLoader();
        try(var loader=new URLClassLoader(new URL[]{classes.toUri().toURL()},old)) {
            Thread.currentThread().setContextClassLoader(loader);
            var summary=new org.junit.platform.launcher.listeners.SummaryGeneratingListener();
            org.junit.platform.launcher.core.LauncherFactory.create().execute(org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request()
                    .selectors(org.junit.platform.engine.discovery.DiscoverySelectors.selectClass(loader.loadClass(pkg+"."+cls))).build(),summary);
            assertEquals(pass?1:0,summary.getSummary().getTestsSucceededCount(),summary.getSummary().getFailures().stream().map(f->f.getException().toString()).toList().toString());
            assertEquals(pass?0:1,summary.getSummary().getTestsFailedCount());
            if(!pass)assertTrue(summary.getSummary().getFailures().get(0).getException().toString().contains("Hibernate lazy budget exceeded"));
        }finally{Thread.currentThread().setContextClassLoader(old);}
        Path zip=home.resolve("case.zip");CaseJUnitExport.export(name,pkg,cls,Map.of("execution-mode","LIVE"),zip);
        try(var archive=new java.util.zip.ZipFile(zip.toFile())) {
            byte[] agent=archive.getInputStream(archive.getEntry("runtime/sb-repl-agent.jar")).readAllBytes();
            try(var jar=new java.util.jar.JarInputStream(new java.io.ByteArrayInputStream(agent))) {assertEquals("com.baader.devrt.Agent",jar.getManifest().getMainAttributes().getValue("Premain-Class"));}
        }
    }
}
