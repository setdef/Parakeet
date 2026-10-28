package com.setdef.parakeet

import com.intellij.codeInsight.editorActions.ExtendWordSelectionHandlerBase
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement

/**
 * Extend Selection (⌥↑ / Ctrl+W) in Inko, from Cuttlebone's selection ranges (from the tokens and declarations, so
 * also in code that doesn't parse): the token, the chain of calls, an argument, a bracket's contents and the
 * brackets with them, the statement, and enclosing declarations. The IDE picks the smallest that's bigger than the
 * current selection.
 */
class InkoExtendSelectionHandler : ExtendWordSelectionHandlerBase() {
    override fun canSelect(e: PsiElement): Boolean = e.containingFile is InkoFile && e.firstChild == null

    override fun select(e: PsiElement, editorText: CharSequence, cursorOffset: Int, editor: Editor): List<TextRange> {
        val ranges = CuttleboneQueries.getInstance(e.project).selectionRanges(e.containingFile, cursorOffset)?.value ?: return emptyList()
        return ranges.mapNotNull { range ->
            val start = CuttleboneQueries.offset(editor.document, range.start) ?: return@mapNotNull null
            val end = CuttleboneQueries.offset(editor.document, range.end) ?: return@mapNotNull null
            TextRange(start, end).takeIf { !it.isEmpty }
        }
    }
}
