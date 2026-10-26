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

    /** What every module sees without importing it: Inko's prelude (compiler/src/type_check/define_types.rs, InsertPrelude). */
    private val PRELUDE = setOf("Int", "Float", "String", "Array", "Bool", "Nil", "InlineArray", "ByteArray", "Option", "Result", "Map", "Self", "Never")

    /**
     * [type] (the compiler's), if it can be written into [file]: every type it names is in the prelude, declared
     * or imported there, a type parameter, or the only type or trait of that name the index knows, whose import
     * is added to [imports]. Null otherwise: the name wouldn't compile there.
     */
    fun writable(file: InkoFile, type: String, imports: MutableSet<Pair<String, String>>): String? {
        val text = file.text
        val existing = InkoImports.parse(text)
        val declared = file.inkoOutline.roots.map { it.label }.toSet()
        val needed = mutableSetOf<Pair<String, String>>()
        for (name in Regex("\\b[A-Z]\\w*").findAll(type).map { it.value }.distinct()) {
            if (name in PRELUDE || name in declared || existing.symbol(name) != null) continue
            // A type parameter of the type or method it's in (`[T]`, `[K: Hash, V]`).
            if (Regex("[\\[,]\\s*$name\\s*[:\\],]").containsMatchIn(text)) continue
            val path = Inko.pathOf(file.virtualFile) ?: return null
            val symbols = InkoDocIndex.getInstance(file.project).symbols(Inko.projectRoot(path)) ?: return null
            val module = symbols.named(name, InkoDefinitionKind.TYPE, InkoDefinitionKind.TRAIT).map { it.module }.distinct().singleOrNull() ?: return null
            needed += module to name
        }
        imports += needed
        return type
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
