package com.setdef.parakeet

import com.intellij.lang.ExpressionTypeProvider
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.FakePsiElement

/**
 * Expression Type (⌃⇧P / Ctrl+Shift+P): the compiler's type, from Cuttlebone.
 * Without its answer it says the type is unknown, and why.
 */
class InkoExpressionTypeProvider : ExpressionTypeProvider<PsiElement>() {
    override fun getInformationHint(element: PsiElement): String {
        val expression = element as? InkoExpression ?: return UNKNOWN
        val file = expression.containingFile as? InkoFile ?: return UNKNOWN
        val type = InkoCompilerTypes.of(file, expression.textRange, stale = true)
        if (type != null) return StringUtil.escapeXmlEntities(if (type.stale) "${type.type} (${CuttleboneQueries.OUTDATED})" else type.type)
        return when {
            CuttleboneService.getInstance(file.project).unavailable -> NO_CUTTLEBONE
            CuttleboneQueries.getInstance(file.project).hover(file, expression.textRange.startOffset)?.editing == true -> EDITING
            else -> UNKNOWN
        }
    }

    override fun getErrorHint(): String = "No Inko expression at the caret"

    override fun getExpressionsAt(elementAt: PsiElement): List<PsiElement> {
        val file = elementAt.containingFile as? InkoFile ?: return emptyList()
        val stream = InkoTokenStream(file.text)
        return InkoIntroduceVariable.candidatesAt(stream, elementAt.textRange.startOffset).map { InkoExpression(file, it) }
    }

    private companion object {
        const val UNKNOWN = "Unknown: the compiler has no type for this expression"
        const val EDITING = "Unknown: Cuttlebone can't type check the code here as it is"
        const val NO_CUTTLEBONE = "Unknown: Expression Type needs Cuttlebone, which isn't running"
    }
}

/** The compiler's types for ranges of code, from Cuttlebone's hover. */
object InkoCompilerTypes {
    class Type(val type: String, val stale: Boolean)

    /**
     * The compiler's type for exactly [range]. Hover gives the innermost expression at a position, so it's
     * asked at the range's start and at its last token's, and only an answer whose range is [range] counts.
     * A [stale] one (from the last version that type checked) counts only if asked for: what writes code doesn't.
     * Null too for a type the compiler couldn't infer fully (with a `?`).
     */
    fun of(file: InkoFile, range: TextRange, stale: Boolean): Type? {
        val document = file.viewProvider.document ?: return null
        val last = InkoTokenStream(file.text).tokens.lastOrNull { it.end <= range.endOffset && it.start >= range.startOffset }?.start
        val queries = CuttleboneQueries.getInstance(file.project)
        for (offset in listOfNotNull(range.startOffset, last).distinct()) {
            val answer = queries.hover(file, offset) ?: continue
            if (answer.stale && !stale) continue
            val hover = answer.value ?: continue
            val hovered = hover.range ?: continue
            if (CuttleboneQueries.offset(document, hovered.start) != range.startOffset || CuttleboneQueries.offset(document, hovered.end) != range.endOffset) continue
            return InkoHover.type(hover)?.takeIf { '?' !in it }?.let { Type(it, answer.stale) }
        }
        return null
    }

    /** The compiler's type for the variable a binding's name at [offset] declares (`x` in `let x = …`). Fresh only. */
    fun ofBinding(file: InkoFile, offset: Int): String? {
        val answer = CuttleboneQueries.getInstance(file.project).hover(file, offset)?.takeIf { !it.stale } ?: return null
        return answer.value?.let(InkoHover::type)?.takeIf { '?' !in it }
    }
}

/** An expression in Inko's flat PSI: a range of tokens in [file]. */
class InkoExpression(private val file: PsiFile, private val range: TextRange) : FakePsiElement() {
    override fun getParent(): PsiElement = file
    override fun getContainingFile(): PsiFile = file
    override fun getTextRange(): TextRange = range
    override fun getTextOffset(): Int = range.startOffset
    override fun getText(): String = range.substring(file.text)
    override fun isValid(): Boolean = file.isValid
}
