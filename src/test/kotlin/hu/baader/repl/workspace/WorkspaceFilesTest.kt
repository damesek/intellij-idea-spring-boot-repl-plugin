package hu.baader.repl.workspace

import hu.baader.repl.protocol.WorkspaceArchive
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class WorkspaceFilesTest {
    @Test fun fullEditorMetadataRoundTripsOfflineWithoutExecutingSource() {
        val dir = Files.createTempDirectory("workspace-test-")
        try {
            val doc = WorkspaceDocument(); doc.notebook.sync("// %% Árvíz\nthrow new RuntimeException(\"do not run\");")
            doc.imports += "import java.time.*;"
            doc.bindings += WorkspaceDocument.Binding("dto", "input", "a".repeat(64), "example.Dto")
            doc.bookmarks += WorkspaceDocument.Bookmark("Cím", "dto", "ZmllbGQ")
            doc.httpRequests += hu.baader.repl.ui.HttpRequestCase(id="sample", url="http://localhost/should-not-be-called", body="árvíz")
            doc.transcript = "previous output"; doc.environment = "{\"activeProfiles\":[\"dev\"]}"
            val zip = dir.resolve("all.sbrepl-workspace"); WorkspaceFiles.writeOffline(zip, doc)
            val restored = WorkspaceFiles.read(zip).document
            assertEquals(doc.encode(), restored.encode()); assertEquals(0, WorkspaceFiles.read(zip).snapshots)
        } finally { WorkspaceArchive.removeDirectory(dir) }
    }
    @Test fun malformedMetadataAndForgedVersionHashesAreRejected() {
        for (json in listOf("{\"formatVersion\":999}", "{\"formatVersion\":1,\"notebook\":null}", "{\"formatVersion\":1,\"bindings\":[{\"variable\":\"x\",\"snapshot\":\"n\",\"version\":\"../bad\",\"type\":\"\",\"restorable\":true}]}")) {
            try { WorkspaceDocument.decode(json); fail("Invalid metadata accepted") } catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun badWorkspaceChecksumDoesNotReplaceTheInMemoryNotebook() {
        val dir = Files.createTempDirectory("workspace-test-")
        try {
            val current = WorkspaceDocument(); current.notebook.sync("int original = 1;")
            val before = current.encode(); val zip = dir.resolve("original.zip"); WorkspaceFiles.writeOffline(zip, current)
            WorkspaceArchive.read(zip).use { archive ->
                Files.writeString(archive.files().getValue("workspace.json"), "{\"formatVersion\":1}")
                WorkspaceArchive.write(dir.resolve("tampered.zip"), archive.files())
            }
            try { WorkspaceFiles.read(dir.resolve("tampered.zip")); fail("Checksum must fail") } catch (_: IllegalArgumentException) {}
            assertEquals(before, current.encode())
        } finally { WorkspaceArchive.removeDirectory(dir) }
    }
}
