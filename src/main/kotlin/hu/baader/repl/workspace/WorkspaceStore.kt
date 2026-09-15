package hu.baader.repl.workspace

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Private per-project recovery checkpoint, outside the project and VCS. No live object or endpoint token is persisted. */
@Service(Service.Level.PROJECT)
class WorkspaceStore(project: Project) : Disposable {
    private val path = Path.of(PathManager.getSystemPath(), "java-repl-workspaces", project.locationHash, "checkpoint.json")
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "REPL workspace checkpoint").apply { isDaemon = true } }
    private var future: ScheduledFuture<*>? = null
    @Volatile var lastError: String = ""
        private set
    var document: WorkspaceDocument = runCatching {
        require(!Files.isSymbolicLink(path) && Files.size(path) <= WorkspaceDocument.MAX_BYTES)
        WorkspaceDocument.decode(Files.readString(path)).also { it.notebook.restored() }
    }.getOrElse { WorkspaceDocument() }
    @Synchronized fun checkpoint() {
        val json = runCatching { document.encode() }.getOrElse { lastError = it.message.orEmpty(); return }
        future?.cancel(false)
        future = worker.schedule({ save(json) }, 600, TimeUnit.MILLISECONDS)
    }
    private fun save(json: String) {
        try { write(path, json); lastError = "" } catch (e: Exception) { lastError = "Checkpoint failed: ${e.message}" }
    }
    @Synchronized override fun dispose() {
        future?.cancel(false)
        // Finish the most recent immutable copy after any earlier queued write.
        val json = runCatching { document.encode() }.getOrNull()
        if (json != null) worker.execute { save(json) }
        worker.shutdown()
    }
    companion object {
        fun getInstance(project: Project): WorkspaceStore = project.service()
        fun write(path: Path, json: String) {
            Files.createDirectories(path.toAbsolutePath().parent)
            require(!Files.isSymbolicLink(path)) { "Workspace target must not be a symbolic link" }
            val temp = Files.createTempFile(path.toAbsolutePath().parent, ".workspace-", ".tmp")
            try {
                if (Files.getFileStore(temp).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"))
                Files.writeString(temp, json)
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temp) }
        }
    }
}
