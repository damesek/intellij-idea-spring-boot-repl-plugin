package com.baader.devrt;

import hu.baader.repl.protocol.*;
import hu.baader.repl.fixture.AgentSmokeApp;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import static org.junit.jupiter.api.Assertions.*;

class AgentPackagingTest {
    @TempDir Path temp;
    private Path agent() { return Path.of(Objects.requireNonNull(System.getProperty("sb.repl.agentJar"),"Set sb.repl.agentJar to the packaged agent")); }
    @Test void artifactContainsIsolatedDependenciesAndMatchingProtocol() throws Exception {
        try(var jar=new JarFile(agent().toFile())) {
            assertEquals("1",jar.getManifest().getMainAttributes().getValue("SB-Repl-Protocol"));
            assertNotNull(jar.getEntry("com/baader/devrt/AgentInstrumentation.class"));
            assertNotNull(jar.getEntry("hu/baader/repl/protocol/Bencode.class"));
            assertTrue(jar.stream().anyMatch(e->e.getName().startsWith("agent-libs/byte-buddy-") && e.getName().endsWith(".jar")));
            assertFalse(jar.stream().anyMatch(e->e.getName().startsWith("net/bytebuddy/")));
            assertFalse(jar.stream().anyMatch(e->e.getName().startsWith("org/springframework/")));
        }
    }
    private Process start(String mode,Path endpoint,Path log) throws Exception {
        Set<String> excluded=new HashSet<>(List.of(Path.of(Agent.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
            Path.of(Bencode.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString()));
        String classpath=Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
            .filter(p->!p.contains("byte-buddy") && !excluded.contains(Path.of(p).toAbsolutePath().normalize().toString()))
            .reduce((a,b)->a+File.pathSeparator+b).orElseThrow();
        String encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(endpoint.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
            "-javaagent:"+agent()+"=port=0,endpoint64="+encoded,"-cp",classpath,AgentSmokeApp.class.getName(),mode)
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }
    @Test void springStartsWithOnlyThePackagedAgentAndKeepsProfiles() throws Exception {
        Path log=temp.resolve("bootstrap.log");
        var process=start("bootstrap",temp.resolve("endpoint.properties"),log);
        try {
            assertTrue(process.waitFor(30,TimeUnit.SECONDS),"Agent/Spring startup timed out");
            String text=Files.readString(log);
            assertEquals(0,process.exitValue(),text);
            assertTrue(text.contains("AGENT_SPRING_READY"),text);
            assertTrue(text.contains("AGENT_HOTSWAP_OK"),text);
            assertTrue(text.contains("AGENT_TRACE_OK"),text);
            assertTrue(text.contains("AGENT_RECORDING_OK"),text);
            assertTrue(text.contains("AGENT_CONTEXT_RELEASED"),text);
        } finally { process.destroyForcibly(); }
    }
    @Test void executableBootJarUsesApplicationClassIdentity() throws Exception {
        Class<?> launcher;
        try { launcher=Class.forName("org.springframework.boot.loader.launch.JarLauncher"); }
        catch (ClassNotFoundException modernMissing) { launcher=Class.forName("org.springframework.boot.loader.JarLauncher"); }
        Path boot=temp.resolve("application.jar");
        var manifest=new java.util.jar.Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version","1.0");
        manifest.getMainAttributes().putValue("Main-Class",launcher.getName());
        manifest.getMainAttributes().putValue("Start-Class",AgentSmokeApp.class.getName());
        Path loader=Path.of(launcher.getProtectionDomain().getCodeSource().getLocation().toURI());
        try(var jar=new java.util.jar.JarOutputStream(Files.newOutputStream(boot),manifest);var loaderJar=new JarFile(loader.toFile())) {
            for(var entry:loaderJar.stream().filter(e->e.getName().startsWith("org/springframework/boot/loader/") && !e.isDirectory()).toList())
                try(var input=loaderJar.getInputStream(entry)) { put(jar,entry.getName(),input.readAllBytes(),false); }
            for(Class<?> type:List.of(AgentSmokeApp.class,AgentSmokeApp.Greeting.class,hu.baader.repl.fixture.HotSwapFixture.class,hu.baader.repl.fixture.TraceFixture.class)) {
                String resource=type.getName().replace('.','/')+".class";
                try(var input=type.getClassLoader().getResourceAsStream(resource)) { put(jar,"BOOT-INF/classes/"+resource,input.readAllBytes(),false); }
            }
            jar.putNextEntry(new java.util.jar.JarEntry("BOOT-INF/classes/"));jar.closeEntry();
            int index=0;
            for(String value:System.getProperty("java.class.path").split(File.pathSeparator)) {
                Path dependency=Path.of(value);
                if(Files.isRegularFile(dependency) && value.endsWith(".jar") && !value.contains("byte-buddy") && !dependency.equals(loader) && !value.contains("dev-runtime-agent"))
                    put(jar,"BOOT-INF/lib/"+(index++)+"-"+dependency.getFileName(),Files.readAllBytes(dependency),true);
            }
        }
        Path log=temp.resolve("boot-jar.log"),endpoint=temp.resolve("boot-endpoint.properties");
        String encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(endpoint.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
            "-javaagent:"+agent()+"=port=0,endpoint64="+encoded,"-jar",boot.toString(),"bootstrap")
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(30,TimeUnit.SECONDS),"Boot JAR timed out");
            String output=Files.readString(log);
            assertEquals(0,process.exitValue(),output);assertTrue(output.contains("AGENT_SPRING_READY"),output);
        } finally { process.destroyForcibly(); }
    }
    private static void put(java.util.jar.JarOutputStream jar,String name,byte[] bytes,boolean stored) throws IOException {
        var entry=new java.util.jar.JarEntry(name);
        if(stored) { var crc=new java.util.zip.CRC32();crc.update(bytes);entry.setMethod(java.util.zip.ZipEntry.STORED);entry.setSize(bytes.length);entry.setCrc(crc.getValue()); }
        jar.putNextEntry(entry);jar.write(bytes);jar.closeEntry();
    }
    @Test void authenticatedWireSessionUsesThePackagedAgent() throws Exception {
        Assumptions.assumeFalse(Boolean.getBoolean("sb.repl.tests.noSockets"),"Sandbox forbids loopback sockets; run this test in CI or a normal local build");
        Path log=temp.resolve("wire.log"),file=temp.resolve("endpoint.properties");
        var process=start("wire",file,log);
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while ((!Files.exists(file) || !Files.readString(log).contains("AGENT_SPRING_READY")) && process.isAlive() && System.nanoTime()<deadline) Thread.sleep(50);
            assertTrue(Files.exists(file),Files.readString(log));
            var endpoint=EndpointFile.read(file);
            try(var socket=new Socket(InetAddress.getLoopbackAddress(),endpoint.port())) {
                socket.setSoTimeout(10000);
                Bencode.write(Map.of("op","clone","id","clone","token",endpoint.token()),socket.getOutputStream());
                var cloned=Bencode.read(socket.getInputStream());String session=(String)cloned.get("new-session");assertNotNull(session,cloned.toString());
                Bencode.write(Map.of("op","eval","id","eval","token",endpoint.token(),"session",session,"code","ctx.getBean(\"answer\", Integer.class) + 1"),socket.getOutputStream());
                var evaluated=Bencode.read(socket.getInputStream());
                assertFalse(ReplProtocol.error(evaluated),evaluated.toString());assertEquals("43",evaluated.get("value"));
                Bencode.write(Map.of("op","trace/record","id","record","token",endpoint.token(),"session",session,"classes","hu.baader.repl.fixture.TraceFixture"),socket.getOutputStream());
                var started=Bencode.read(socket.getInputStream());assertFalse(ReplProtocol.error(started),started.toString());
                Bencode.write(Map.of("op","eval","id","slow-eval","token",endpoint.token(),"session",session,"code","new hu.baader.repl.fixture.TraceFixture().pause(1500L)"),socket.getOutputStream());
                RecordedCall running=null;long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
                while(running==null && System.nanoTime()<limit) {
                    Bencode.write(Map.of("op","trace/history","id","history","token",endpoint.token(),"session",session),socket.getOutputStream());
                    var listed=Bencode.read(socket.getInputStream());assertEquals("history",listed.get("id"),listed.toString());
                    running=listed.get("value").toString().lines().filter(s->!s.isEmpty()).map(RecordedCall::decode).findFirst().orElse(null);
                    if(running==null)Thread.sleep(20);
                }
                assertNotNull(running);assertEquals("RUNNING",running.status());
                Bencode.write(Map.of("op","trace/stop","id","stop","token",endpoint.token(),"session",session),socket.getOutputStream());
                var stopped=Bencode.read(socket.getInputStream());assertEquals("stop",stopped.get("id"));assertEquals("false",stopped.get("active"));
                var completed=Bencode.read(socket.getInputStream());assertEquals("slow-eval",completed.get("id"));assertEquals("42",completed.get("value"));
                Bencode.write(Map.of("op","trace/call","id","call-request","token",endpoint.token(),"session",session,"recording",running.recording(),"call-id",""+running.id()),socket.getOutputStream());
                var value=Bencode.read(socket.getInputStream());assertEquals("call-request",value.get("id"));
                var recorded=RecordedCall.decode(value.get("value").toString());assertEquals("SUCCESS",recorded.status());assertEquals("42",ValueTree.decode(recorded.output()).text());
            }
            try(var unauthenticated=new Socket(InetAddress.getLoopbackAddress(),endpoint.port())) {
                unauthenticated.setSoTimeout(10000);Bencode.write(Map.of("op","eval","id","bad","code","1"),unauthenticated.getOutputStream());
                assertTrue(ReplProtocol.error(Bencode.read(unauthenticated.getInputStream())));
            }
            process.getOutputStream().write('\n');process.getOutputStream().flush();
            assertTrue(process.waitFor(10,TimeUnit.SECONDS),Files.readString(log));assertEquals(0,process.exitValue(),Files.readString(log));
        } finally { process.destroyForcibly(); }
    }
}
