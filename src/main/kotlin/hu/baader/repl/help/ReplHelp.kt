package hu.baader.repl.help

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import java.awt.Desktop
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** The offline guide is extracted from the plugin JAR before handing it to a PDF viewer. */
object ReplHelp {
    enum class Language(val code: String, private val label: String) {
        ENGLISH("en", "English"),
        HUNGARIAN("hu", "Magyar");

        val resource: String get() = "/help/spring-boot-repl-guide-$code.pdf"
        override fun toString(): String = label
    }

    fun open(project: Project?) {
        JBPopupFactory.getInstance().createPopupChooserBuilder(Language.values().toList())
            .setTitle("PDF manual / PDF kézikönyv")
            .setItemChosenCallback { language -> open(project, language) }
            .createPopup().showInFocusCenter()
    }

    fun open(project: Project?, language: Language) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val file = extract(PathManager.getSystemDir().resolve("spring-boot-repl/help"), language)
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    try {
                        Desktop.getDesktop().open(file.toFile())
                    } catch (_: java.io.IOException) {
                        BrowserUtil.browse(file)
                    }
                } else BrowserUtil.browse(file)
            } catch (failure: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    if (project?.isDisposed != true)
                        Messages.showErrorDialog(project, "Could not open the bundled PDF guide: ${failure.message}", "Spring Boot REPL Help")
                }
            }
        }
    }

    internal fun extract(directory: Path, language: Language): Path {
        val bytes = checkNotNull(ReplHelp::class.java.getResourceAsStream(language.resource)) { "$language PDF guide is missing from the plugin" }
            .use { it.readAllBytes() }
        check(bytes.size > 5 && String(bytes, 0, 5, Charsets.US_ASCII) == "%PDF-") { "Invalid bundled PDF guide" }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        Files.createDirectories(directory)
        val target = directory.resolve("spring-boot-repl-guide-${language.code}-$digest.pdf")
        if (Files.isRegularFile(target) && Files.size(target) == bytes.size.toLong() && Files.readAllBytes(target).contentEquals(bytes)) return target
        val temporary = Files.createTempFile(directory, "guide-", ".pdf")
        try {
            Files.write(temporary, bytes)
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { Files.deleteIfExists(temporary) }
        return target
    }
}
