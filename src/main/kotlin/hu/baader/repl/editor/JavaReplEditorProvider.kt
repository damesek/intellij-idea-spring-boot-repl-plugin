package hu.baader.repl.editor

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.*
import com.intellij.util.ProcessingContext
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorSettings
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.impl.source.codeStyle.JavaCodeStyleManagerImpl

/**
 * Provides enhanced Java editor for REPL with full IDE features
 */
class JavaReplEditorProvider {

    companion object {
        fun createEnhancedEditor(project: Project): EditorEx {
            val document = EditorFactory.getInstance().createDocument("")

            // Create the editor with Java file type
            val editor = EditorFactory.getInstance()
                .createEditor(document, project, JavaFileType.INSTANCE, false) as EditorEx

            // Configure editor settings for optimal Java development
            configureEditorSettings(editor)

            // Set up syntax highlighting
            setupSyntaxHighlighting(editor, project)

            // Add Java-specific features
            setupJavaFeatures(editor, project, document)

            return editor
        }

        private fun configureEditorSettings(editor: EditorEx) {
            editor.settings.apply {
                // Line numbers and guides
                isLineNumbersShown = true
                isIndentGuidesShown = true
                isCaretRowShown = true

                // Folding
                isAutoCodeFoldingEnabled = true
                isFoldingOutlineShown = true
                isAllowSingleLogicalLineFolding = false

                // Code style
                setTabSize(4)

                // Smart features
                isSmartHome = true
                isVirtualSpace = false
                isMouseClickSelectionHonorsCamelWords = true

                // Code assistance
                isVariableInplaceRenameEnabled = true
                isPreselectRename = true
                isShowIntentionBulb = true

                // Additional space for better visibility
                additionalColumnsCount = 2
                additionalLinesCount = 1
            }
        }

        private fun setupSyntaxHighlighting(editor: EditorEx, project: Project) {
            val highlighter = EditorHighlighterFactory.getInstance()
                .createEditorHighlighter(project, JavaFileType.INSTANCE)
            editor.highlighter = highlighter
        }

        private fun setupJavaFeatures(editor: EditorEx, project: Project, document: Document) {
            // Add document listener for auto-import suggestions
            document.addDocumentListener(object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    ApplicationManager.getApplication().invokeLater {
                        // Could trigger auto-import analysis here
                    }
                }
            })

            // Store project reference for later use
            editor.putUserData(ProjectKey, project)
        }

        private val ProjectKey = com.intellij.openapi.util.Key.create<Project>("JavaReplProject")
    }
}

/**
 * Custom completion contributor for REPL context
 */
class ReplCompletionContributor : CompletionContributor() {
    init {
        // Add completion for common Java patterns
        extend(CompletionType.BASIC,
            PlatformPatterns.psiElement(PsiIdentifier::class.java),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet
                ) {
                    // Add common REPL-specific completions
                    addReplSpecificCompletions(result)

                    // Add Spring-specific completions if Spring context is bound
                    addSpringCompletions(result, parameters)
                }
            })
    }

    private fun addReplSpecificCompletions(result: CompletionResultSet) {
        // Common REPL variables
        result.addElement(
            LookupElementBuilder.create("applicationContext")
                .withTypeText("ApplicationContext")
                .withTailText(" (Spring context)")
                .bold()
        )

        // Common methods
        result.addElement(
            LookupElementBuilder.create("return ")
                .withTypeText("keyword")
                .withTailText(" value;")
                .bold()
        )

        // System methods
        result.addElement(
            LookupElementBuilder.create("System.out.println()")
                .withTypeText("void")
                .withTailText(" - Print to console")
        )
    }

    private fun addSpringCompletions(result: CompletionResultSet, parameters: CompletionParameters) {
        val element = parameters.position
        val prevText = parameters.editor.document.text.substring(0, parameters.offset)

        // If typing after "applicationContext."
        if (prevText.endsWith("applicationContext.")) {
            result.addElement(
                LookupElementBuilder.create("getBean(\"")
                    .withTypeText("Object")
                    .withTailText("\")")
                    .withInsertHandler { context, _ ->
                        // Position cursor inside quotes
                        context.editor.caretModel.moveCaretRelatively(-2, 0, false, false, false)
                    }
            )

            result.addElement(
                LookupElementBuilder.create("getBean(\"")
                    .withPresentableText("getBean(name, Class)")
                    .withTypeText("T")
                    .withTailText("\", Class.class)")
                    .withInsertHandler { context, _ ->
                        context.editor.caretModel.moveCaretRelatively(-2, 0, false, false, false)
                    }
            )

            result.addElement(
                LookupElementBuilder.create("getBeanDefinitionNames()")
                    .withTypeText("String[]")
                    .withTailText(" - Get all bean names")
            )

            result.addElement(
                LookupElementBuilder.create("getBeansOfType(")
                    .withTypeText("Map<String, T>")
                    .withTailText("Class.class)")
            )
        }
    }
}

/**
 * Formatter for REPL Java code
 */
class ReplJavaFormatter {
    companion object {
        fun formatCode(project: Project, code: String): String {
            // Use simple formatting for REPL context
            // Full PSI formatting would require more setup
            return simpleFormat(code)
        }

        private fun simpleFormat(code: String): String {
            val lines = code.split("\n")
            val formatted = mutableListOf<String>()
            var indent = 0

            for (line in lines) {
                val trimmed = line.trim()

                // Decrease indent for closing braces
                if (trimmed.startsWith("}") || trimmed.startsWith(")")) {
                    indent = maxOf(0, indent - 1)
                }

                // Add formatted line
                if (trimmed.isNotEmpty()) {
                    formatted.add("    ".repeat(indent) + trimmed)
                } else {
                    formatted.add("")
                }

                // Increase indent for opening braces
                if (trimmed.endsWith("{") ||
                    (trimmed.endsWith("(") && !trimmed.endsWith("()")) ||
                    trimmed.endsWith("(") && trimmed.contains("if ") ||
                    trimmed.endsWith("(") && trimmed.contains("for ") ||
                    trimmed.endsWith("(") && trimmed.contains("while ")) {
                    indent++
                }
            }

            return formatted.joinToString("\n")
        }
    }
}