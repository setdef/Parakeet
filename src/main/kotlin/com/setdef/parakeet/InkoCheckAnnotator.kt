package com.setdef.parakeet

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import java.nio.file.Path

/** Shows `inko check` errors and warnings in the editor as you type. */
class InkoCheckAnnotator : ExternalAnnotator<InkoCheckAnnotator.Input, List<InkoDiagnostic>>() {
    class Input(val project: Project, val path: Path, val text: String)

    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): Input? = collectInformation(file)

    override fun collectInformation(file: PsiFile): Input? {
        val path = Inko.pathOf(file.virtualFile) ?: return null
        val document = file.viewProvider.document ?: return null
        return Input(file.project, path, document.text)
    }

    override fun doAnnotate(input: Input): List<InkoDiagnostic> = InkoCheck.run(input.project, input.path, input.text)

    override fun apply(file: PsiFile, diagnostics: List<InkoDiagnostic>, holder: AnnotationHolder) {
        val document = file.viewProvider.document ?: return
        for (diagnostic in diagnostics) {
            val severity = if (diagnostic.level == "warning") HighlightSeverity.WARNING else HighlightSeverity.ERROR
            holder.newAnnotation(severity, diagnostic.message)
                .range(rangeOf(document, diagnostic))
                .create()
        }
    }

    // The compiler reports 1-based lines and 1-based, inclusive columns.
    private fun rangeOf(document: Document, diagnostic: InkoDiagnostic): TextRange {
        fun offset(line: Int, column: Int): Int {
            val lineIndex = (line - 1).coerceIn(0, maxOf(document.lineCount - 1, 0))
            val lineStart = document.getLineStartOffset(lineIndex)
            val lineEnd = document.getLineEndOffset(lineIndex)
            return (lineStart + column).coerceIn(lineStart, lineEnd)
        }
        val start = offset(diagnostic.lineStart, diagnostic.columnStart - 1)
        val end = offset(diagnostic.lineEnd, diagnostic.columnEnd)
        return if (end > start) TextRange(start, end) else TextRange(start, minOf(start + 1, document.textLength))
    }
}
