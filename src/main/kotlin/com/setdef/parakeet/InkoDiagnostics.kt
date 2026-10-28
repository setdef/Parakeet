package com.setdef.parakeet

import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

/** One of the compiler's errors or warnings, as `inko check` reports it: where, what, and its ID. */
data class InkoDiagnostic(
    val range: TextRange,
    val message: String,
    /** The compiler's diagnostic ID, e.g. `invalid-symbol` or `unused-symbol`. */
    val id: String,
    val isWarning: Boolean,
) {
    companion object {
        /**
         * [file]'s, from Cuttlebone's pull ([CuttleboneQueries.diagnostics]): the same as `inko check`'s, for the
         * documents exactly as they are. Null without its answer. Waits outside a read action, so as not to hold up typing.
         */
        fun of(file: PsiFile, document: Document): List<InkoDiagnostic>? {
            val diagnostics = CuttleboneQueries.getInstance(file.project).diagnostics(file)?.value ?: return null
            return com.intellij.openapi.application.ReadAction.compute<List<InkoDiagnostic>, RuntimeException> { diagnostics.mapNotNull { of(document, it) } }
        }

        fun of(document: Document, diagnostic: org.eclipse.lsp4j.Diagnostic): InkoDiagnostic? {
            val start = CuttleboneQueries.offset(document, diagnostic.range.start) ?: return null
            val end = CuttleboneQueries.offset(document, diagnostic.range.end) ?: return null
            // Somewhere to show an empty one: the character it's at.
            val range = if (end > start) TextRange(start, end) else TextRange(start, minOf(start + 1, document.textLength))
            val id = diagnostic.code?.let { if (it.isLeft) it.left else it.right.toString() }.orEmpty()
            return InkoDiagnostic(range, diagnostic.message, id, diagnostic.severity == org.eclipse.lsp4j.DiagnosticSeverity.Warning)
        }
    }
}
