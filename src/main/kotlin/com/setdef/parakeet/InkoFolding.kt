package com.setdef.parakeet

import com.intellij.codeInsight.folding.CodeFoldingSettings
import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

/** Folds multi-line `{ }` blocks, runs of comment lines, and groups of imports. */
class InkoFoldingBuilder : FoldingBuilderEx(), DumbAware {
    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val leaves = generateSequence(PsiTreeUtil.firstChild(root)) { PsiTreeUtil.nextLeaf(it) }.toList()
        val settings = CodeFoldingSettings.getInstance()
        return (braces(leaves, document) + comments(leaves, document, settings) + imports(root, leaves, settings)).toTypedArray()
    }

    private fun spansLines(document: Document, range: TextRange) =
        document.getLineNumber(range.startOffset) != document.getLineNumber(range.endOffset)

    private fun fold(node: ASTNode, range: TextRange, placeholder: String, collapsed: Boolean) =
        FoldingDescriptor(node, range, null, placeholder, collapsed, emptySet())

    private fun braces(leaves: List<PsiElement>, document: Document): List<FoldingDescriptor> {
        val open = ArrayDeque<PsiElement>()
        val result = mutableListOf<FoldingDescriptor>()
        for (leaf in leaves) {
            when {
                leaf.isToken(InkoTokens.LBRACE) -> open.addLast(leaf)
                leaf.isToken(InkoTokens.RBRACE) -> {
                    val start = open.removeLastOrNull() ?: continue
                    val range = TextRange(start.textRange.startOffset, leaf.textRange.endOffset)
                    if (spansLines(document, range)) result += fold(start.node, range, "{...}", false)
                }
            }
        }
        return result
    }

    /** Runs of two or more comment lines, each starting its own line. */
    private fun comments(leaves: List<PsiElement>, document: Document, settings: CodeFoldingSettings): List<FoldingDescriptor> {
        val result = mutableListOf<FoldingDescriptor>()
        var run = mutableListOf<PsiElement>()
        fun flush() {
            if (run.size >= 2) {
                val range = TextRange(run.first().textRange.startOffset, run.last().textRange.endOffset)
                val first = run.first().text.trimEnd().let { if (it.length > 60) it.take(60) + "…" else it }
                result += fold(run.first().node, range, "$first ...", settings.COLLAPSE_FILE_HEADER && range.startOffset == 0)
            }
            run = mutableListOf()
        }
        for (leaf in leaves) {
            when {
                leaf.isToken(InkoTokens.COMMENT) -> {
                    val line = document.getLineNumber(leaf.textOffset)
                    val previous = run.lastOrNull()
                    if (previous != null && document.getLineNumber(previous.textOffset) != line - 1) flush()
                    run += leaf
                }
                leaf.isToken(InkoTokens.WHITE_SPACE) && leaf.text.count { it == '\n' } <= 1 -> {}
                else -> flush()
            }
        }
        flush()
        return result
    }

    /** Consecutive `import` statements (with only whitespace between them). */
    private fun imports(root: PsiElement, leaves: List<PsiElement>, settings: CodeFoldingSettings): List<FoldingDescriptor> {
        val text = root.text
        val statements = InkoImports.parse(text).statements.map { it.range }
        val groups = mutableListOf<MutableList<TextRange>>()
        for (range in statements) {
            val last = groups.lastOrNull()?.last()
            if (last != null && text.substring(last.endOffset, range.startOffset).isBlank()) groups.last() += range else groups += mutableListOf(range)
        }
        return groups.filter { it.size >= 2 }.mapNotNull { group ->
            val start = group.first().startOffset + (text.substring(group.first().startOffset).length - text.substring(group.first().startOffset).trimStart().length)
            val keyword = leaves.firstOrNull { it.textOffset == start && it.isKeyword("import") } ?: return@mapNotNull null
            fold(keyword.node, TextRange(start, group.last().endOffset), "import ...", settings.COLLAPSE_IMPORTS)
        }
    }

    override fun getPlaceholderText(node: ASTNode): String = "..."

    override fun isCollapsedByDefault(node: ASTNode): Boolean = false
}
