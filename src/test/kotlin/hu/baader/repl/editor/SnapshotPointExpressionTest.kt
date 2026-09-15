package hu.baader.repl.editor

import com.baader.devrt.JShellSession
import com.baader.devrt.SnapshotManager
import hu.baader.repl.debug.SnapshotPointSpec
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class SnapshotPointExpressionTest {
    @Test fun generatedLogpointEvaluatesItsExpressionOnlyWhileArmedAndPersistsTheValue() {
        val home = System.getProperty("user.home"); val app = System.getProperty("sb.repl.applicationId")
        val tmp = Files.createTempDirectory("snapshot-point-expression")
        System.setProperty("user.home", tmp.toString()); System.setProperty("sb.repl.applicationId", "expression-test")
        try {
            JShellSession(null).use { shell ->
                assertEquals("", shell.eval("int calls = 0;").error())
                val spec = SnapshotPointSpec("Árvíz \"input\"", "++calls")
                val code = "if (${spec.condition()}) { ${spec.logExpression()}; }"
                val first = shell.eval(code); assertEquals(first.error(), "", first.error())
                assertEquals("", shell.eval(code).error())
                assertEquals(listOf("1"), shell.eval("calls").values())
                assertEquals(1, SnapshotManager.load<Any>(spec.name))
            }
        } finally {
            System.setProperty("user.home", home); if (app == null) System.clearProperty("sb.repl.applicationId") else System.setProperty("sb.repl.applicationId", app)
            tmp.toFile().deleteRecursively()
        }
    }
}
