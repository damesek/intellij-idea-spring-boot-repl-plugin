package hu.baader.repl.editor

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.LightVirtualFile

class JavaReplEditorProvider {
    companion object {
        val REPL_FILE = com.intellij.openapi.util.Key.create<Boolean>("sb-repl.workbook")
        fun createEnhancedEditor(project: Project): EditorEx {
            val file = LightVirtualFile("SpringRepl.java", JavaFileType.INSTANCE, "// %% Setup\n\n// %% Experiment\n")
            file.putUserData(REPL_FILE, true)
            val document = FileDocumentManager.getInstance().getDocument(file) ?: error("Cannot create REPL document")
            return (EditorFactory.getInstance().createEditor(document, project, file, false) as EditorEx).apply {
                caretModel.moveToOffset(ReplCells.at(document.text, 0).start)
                settings.isLineNumbersShown = true
                settings.isLineMarkerAreaShown = true
                settings.setGutterIconsShown(true)
                settings.isUseSoftWraps = true
                settings.isIndentGuidesShown = true
            }
        }
    }
}
