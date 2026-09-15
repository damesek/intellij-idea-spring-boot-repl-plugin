package com.baader.devrt;

import hu.baader.repl.protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AuditTrailTest {
    @TempDir Path home;
    String previous;
    @BeforeEach void setup() { previous=System.getProperty("sb.repl.audit.dir"); System.setProperty("sb.repl.audit.dir",home.resolve("audit").toString()); }
    @AfterEach void cleanup() { if(previous==null)System.clearProperty("sb.repl.audit.dir"); else System.setProperty("sb.repl.audit.dir",previous); }
    @Test void redactsSecretsAndKeepsCorrelatedValidPrivateJson() throws Exception {
        String code="String password = \"never-save-this\"; // árvíz\n1+1";
        String id=AuditTrail.append("test",Map.of("session","owner","phase","STARTED","code",code,"Authorization","also-private","codeSha256",AuditTrail.hash(code)));
        AuditTrail.append("test",Map.of("session","owner","phase","COMPLETED","request",id));
        AuditTrail.append("test",Map.of("session","other","phase","STARTED"));
        String text=Files.readString(AuditTrail.file("test"));
        assertFalse(text.contains("never-save-this")); assertFalse(text.contains("also-private")); assertTrue(text.contains(AuditTrail.hash(code)));
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        for(String line:text.lines().toList()) assertNotNull(mapper.readTree(line).get("timestamp"));
        var recent=AuditTrail.recent("test","owner",1);
        assertTrue(recent.contains(id)); assertTrue(recent.contains("COMPLETED")); assertFalse(recent.contains("other"));
        assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(AuditTrail.file("test")));
        assertEquals(PosixFilePermissions.fromString("rwx------"),Files.getPosixFilePermissions(AuditTrail.file("test").getParent()));
    }
    @Test void rejectsSymlinksWithoutChangingTheTarget() throws Exception {
        Path external=home.resolve("external"); Files.writeString(external,"unchanged");
        Files.createSymbolicLink(AuditTrail.file("test"),external);
        assertThrows(IllegalStateException.class,()->AuditTrail.append("test",Map.of("phase","STARTED")));
        assertEquals("unchanged",Files.readString(external));
    }
    @Test void auditFailurePreventsEvaluationAndNormalCallsHaveCompletionRecords() throws Exception {
        try(ReplHandler handler=new ReplHandler()) {
            String session=handler.createSession();
            var value=handler.handle("eval",Map.of("session",session,"code","1+1"));
            assertFalse(ReplProtocol.error(value),value.toString());
            String log=AuditTrail.recent("runtime-"+ProcessHandle.current().pid(),session,10);
            assertTrue(log.contains(value.get("audit-id").toString())); assertTrue(log.contains("COMPLETED"));
            Path invalid=home.resolve("file-not-directory"); Files.writeString(invalid,"x"); System.setProperty("sb.repl.audit.dir",invalid.toString());
            var rejected=handler.handle("eval",Map.of("session",session,"code","System.setProperty(\"sb.repl.audit.probe\",\"executed\");"));
            assertTrue(ReplProtocol.error(rejected)); assertNull(System.getProperty("sb.repl.audit.probe"));
        }
    }
}
