package com.baader.devrt;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JShellSessionTest {

    @Test
    void importsAndDefsPersist() {
        // Using null for ApplicationContext in this test
        try (var s = new JShellSession(null)) {
            var r1 = s.eval("import java.time.Instant;");
            assertTrue(r1.imports().stream().anyMatch(i -> i.contains("java.time.Instant")));

            s.eval("int f(int x) { return x + 1; }");
            var r3 = s.eval("f(41)");
            assertTrue(r3.values().contains("42"), "Function definition should persist and be callable.");

            var r4 = s.eval("Instant.now()");
            assertFalse(r4.values().isEmpty(), "Imported class Instant should be available.");
        }
    }

    @Test
    void sessionResetClearsState() {
        var s1 = new JShellSession(null);
        s1.eval("import java.util.UUID; String myVar = \"hello\";");
        assertTrue(s1.getImports().stream().anyMatch(i -> i.contains("UUID")));
        s1.close();

        // A new session simulates a reset
        try (var s2 = new JShellSession(null)) {
            var r = s2.eval("myVar");
            // Expect an error because myVar is not defined in the new session
            assertTrue(r.output().contains("cannot find symbol") || r.output().contains("REJECTED"),
                "State from the old session should not exist in the new one.");
            assertFalse(s2.getImports().stream().anyMatch(i -> i.contains("UUID")),
                "Imports from the old session should not exist in the new one.");
        }
    }
    
    @Test
    void evalSeparatesImportsAndCode() {
        try (var s = new JShellSession(null)) {
            var result = s.eval(
                "import java.util.concurrent.atomic.AtomicInteger;\n" +
                "var i = new AtomicInteger(10);\n" +
                "i.get()"
            );
            
            assertTrue(s.getImports().stream().anyMatch(i -> i.contains("AtomicInteger")), "Import should be extracted and remembered.");
            assertTrue(result.values().contains("10"), "Code should be evaluated and produce a result.");
        }
    }
}