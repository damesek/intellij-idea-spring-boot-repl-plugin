package hu.baader.repl.editor

import com.intellij.codeInsight.completion.CompletionConfidence
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.ThreeState

/** The REPL editor owns its asynchronous session popup; regular Java editors keep normal completion. */
class ReplCompletionConfidence : CompletionConfidence() {
    override fun shouldSkipAutopopup(contextElement: PsiElement, psiFile: PsiFile, offset: Int): ThreeState =
        if (psiFile.originalFile.virtualFile?.getUserData(JavaReplEditorProvider.REPL_FILE) == true) ThreeState.YES else ThreeState.UNSURE
}
