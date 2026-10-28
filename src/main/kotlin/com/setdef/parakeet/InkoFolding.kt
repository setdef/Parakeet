package com.setdef.parakeet

import com.intellij.codeInsight.folding.CodeFoldingSettings
import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Cuttlebone's folding ranges (blocks and other brackets, comment runs, imports, whole lines, from the tokens) as
 * folds: each from the end of its first line, so that line stays in sight, to the end of its last.
 */
class InkoFoldingBuilder : FoldingBuilderEx() {
    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        // Quick is on the EDT, where nothing waits: the folding pass asks.
        val file = root as? PsiFile ?: return emptyArray()
        if (quick) return emptyArray()
        val ranges = CuttleboneQueries.getInstance(file.project).foldingRanges(file)?.value ?: return emptyArray()
        val settings = CodeFoldingSettings.getInstance()
        return ranges.mapNotNull { fold ->
            if (fold.endLine >= document.lineCount || fold.startLine >= fold.endLine) return@mapNotNull null
            val range = TextRange(document.getLineEndOffset(fold.startLine), document.getLineEndOffset(fold.endLine))
            val node = file.findElementAt(range.startOffset)?.node ?: return@mapNotNull null
            val collapsed = when (fold.kind) {
                "imports" -> settings.COLLAPSE_IMPORTS
                "comment" -> settings.COLLAPSE_FILE_HEADER && fold.startLine == 0
                else -> false
            }
            FoldingDescriptor(node, range, null, "...", collapsed, emptySet())
        }.toTypedArray()
    }

    override fun getPlaceholderText(node: ASTNode): String = "..."

    override fun isCollapsedByDefault(node: ASTNode): Boolean = false
}
