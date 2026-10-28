package com.setdef.parakeet

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiFile

/** Shows the compiler's errors and warnings in the editor as you type, from Cuttlebone's pull ([InkoDiagnostic.of]). */
class InkoCheckAnnotator : ExternalAnnotator<PsiFile, List<InkoDiagnostic>>() {
    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): PsiFile? = collectInformation(file)

    // The standard library and dependencies aren't checked as if they were the project's code.
    override fun collectInformation(file: PsiFile): PsiFile? =
        file.takeIf { it is InkoFile && !ProjectFileIndex.getInstance(file.project).isInLibrary(file.virtualFile) }

    override fun doAnnotate(file: PsiFile): List<InkoDiagnostic> = InkoDiagnostic.of(file, file.viewProvider.document ?: return emptyList()).orEmpty()

    override fun apply(file: PsiFile, diagnostics: List<InkoDiagnostic>, holder: AnnotationHolder) {
        for (diagnostic in diagnostics) {
            val severity = if (diagnostic.isWarning) HighlightSeverity.WARNING else HighlightSeverity.ERROR
            val annotation = holder.newAnnotation(severity, diagnostic.message).range(diagnostic.range)
            // Asked of Cuttlebone after highlighting, for what's on screen (InkoQuickFixes), not by every pass.
            if (InkoQuickFixes.fixable(diagnostic)) annotation.withLazyQuickFix { registrar -> InkoQuickFixes.register(file, diagnostic, registrar) }
            annotation.create()
        }
    }
}
