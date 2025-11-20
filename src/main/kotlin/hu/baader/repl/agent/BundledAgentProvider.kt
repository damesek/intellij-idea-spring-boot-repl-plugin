package hu.baader.repl.agent

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Exposes the dev-runtime agent that is bundled inside the plugin and makes it available
 * as a temporary file path for VirtualMachine.loadAgent.
 */
object BundledAgentProvider {
    private const val RESOURCE_PATH = "/agent/dev-runtime-agent.jar"
    private val LOG = Logger.getInstance(BundledAgentProvider::class.java)

    @Volatile
    private var cachedPath: String? = null

    fun getBundledAgentJar(): String? {
        cachedPath?.let { if (File(it).exists()) return it }
        return try {
            extractToTemp()
        } catch (t: Throwable) {
            LOG.warn("Failed to access bundled dev-runtime agent.", t)
            null
        }
    }

    private fun extractToTemp(): String {
        val input = BundledAgentProvider::class.java.getResourceAsStream(RESOURCE_PATH)
            ?: throw IllegalStateException("Bundled dev-runtime agent missing at $RESOURCE_PATH")

        val tempFile = Files.createTempFile("dev-runtime-agent", ".jar")
        input.use { Files.copy(it, tempFile, StandardCopyOption.REPLACE_EXISTING) }
        tempFile.toFile().deleteOnExit()

        val path = tempFile.toAbsolutePath().toString()
        cachedPath = path
        return path
    }
}
