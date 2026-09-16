package com.baader.devrt;

import hu.baader.repl.protocol.Bencode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class SqlAgentIntegrationTest {
    @TempDir Path home;
    @Test void realMvcJdbcAgentAndCaseAssertions() throws Exception {
        Set<String> excluded=new HashSet<>(List.of(Path.of(Agent.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
                Path.of(Bencode.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString()));
        String cp=String.join(java.io.File.pathSeparator,Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                .filter(p->!excluded.contains(Path.of(p).toAbsolutePath().normalize().toString())).toList());
        Path log=home.resolve("agent.log");
        Process process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Duser.home="+home,
                "-Dsb.repl.audit.dir="+home.resolve("audit"),"-javaagent:"+System.getProperty("sb.repl.agentJar")+"=port=0","-cp",cp,"hu.baader.repl.fixture.SqlAgentProbe")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try { boolean done=process.waitFor(50,TimeUnit.SECONDS); assertTrue(done,"SQL probe timed out: "+Files.readString(log));
            String output=Files.readString(log); assertEquals(0,process.exitValue(),output);assertTrue(output.contains("SQL_MVC_JDBC_CASE_OK"),output);
        } finally {process.destroyForcibly();}
    }
}
