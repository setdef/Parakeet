package com.setdef.parakeet

import com.intellij.lang.parameterInfo.CreateParameterInfoContext
import com.intellij.lang.parameterInfo.ParameterInfoHandler
import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.lang.parameterInfo.UpdateParameterInfoContext
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.eclipse.lsp4j.SignatureInformation
import com.setdef.parakeet.InkoDefinitionKind as Kind

/** A call around an offset: the callee's name, its `(`, and which argument the offset is in. */
class InkoCall(val callee: InkoName, val openParen: Int, val argument: Int)

object InkoCalls {
    private val FIELD_KEYWORDS = Regex("""^let\s+(pub\s+)?(mut\s+)?@""")

    /** The innermost call whose parentheses contain [offset], if any. */
    fun at(file: PsiFile, offset: Int): InkoCall? {
        val text = file.viewProvider.document?.charsSequence ?: return null
        val lexer = InkoLexer()
        lexer.start(text, 0, minOf(offset, text.length), 0)
        // Open brackets before the offset: (kind, start, top-level commas so far), and each one's callee (a name right before `(`).
        val open = ArrayDeque<Triple<Char, Int, Int>>()
        val calleeStarts = ArrayDeque<Int?>()
        var lastName: Int? = null
        while (lexer.tokenType != null) {
            when (lexer.tokenType) {
                InkoTokens.LPAREN -> { open.addLast(Triple('(', lexer.tokenStart, 0)); calleeStarts.addLast(lastName) }
                InkoTokens.LBRACKET, InkoTokens.LBRACE -> { open.addLast(Triple(text[lexer.tokenStart], lexer.tokenStart, 0)); calleeStarts.addLast(null) }
                InkoTokens.RPAREN, InkoTokens.RBRACKET, InkoTokens.RBRACE -> { open.removeLastOrNull(); calleeStarts.removeLastOrNull() }
                InkoTokens.COMMA -> open.removeLastOrNull()?.let { (kind, start, commas) -> open.addLast(Triple(kind, start, commas + 1)) }
            }
            if (lexer.tokenType != InkoTokens.WHITE_SPACE && lexer.tokenType != InkoTokens.COMMENT) {
                lastName = lexer.tokenStart.takeIf { lexer.tokenType == InkoTokens.IDENTIFIER || lexer.tokenType == InkoTokens.CONSTANT }
            }
            lexer.advance()
        }
        // The nearest enclosing call, looking past arrays and blocks (e.g. `f(1, [2, |])`).
        val index = open.indices.lastOrNull { open[it].first == '(' && calleeStarts[it] != null } ?: return null
        val (_, start, commas) = open[index]
        val callee = PsiTreeUtil.getParentOfType(file.findElementAt(calleeStarts[index]!!), InkoName::class.java, false) ?: return null
        return InkoCall(callee, start, commas)
    }

    /**
     * The parameters of what a call can invoke: from a method's signature, an
     * enum constructor's arguments, or (for `Type(…)`) the type's fields.
     */
    fun parameters(definition: InkoDefinition, symbols: InkoSymbols?): List<String> = when (definition.kind) {
        Kind.TYPE -> symbols?.ownedBy(definition.name, Kind.FIELD).orEmpty().filter { it.module == definition.module }.map { field ->
            // `let pub mut @count: Int` (compiler/src/docs.rs) gives `count: Int`.
            field.signature?.replace(FIELD_KEYWORDS, "") ?: field.name
        }
        else -> definition.signature?.let(::signatureParameters).orEmpty()
    }

    /** `a: Int, b: Array[Int]` out of `fn name(a: Int, b: Array[Int]) -> X` (or `Some(T)`). */
    private fun signatureParameters(signature: String): List<String> {
        val start = signature.indexOf('(').takeIf { it >= 0 } ?: return emptyList()
        val parameters = mutableListOf<String>()
        var depth = 0
        var current = StringBuilder()
        for (c in signature.substring(start + 1)) {
            when {
                (c == ')' || c == ',') && depth == 0 -> {
                    current.toString().trim().takeIf { it.isNotEmpty() }?.let(parameters::add)
                    current = StringBuilder()
                    if (c == ')') return parameters
                }
                else -> {
                    if (c == '(' || c == '[') depth++
                    if (c == ')' || c == ']') depth--
                    current.append(c)
                }
            }
        }
        return parameters
    }
}

/**
 * Parameter info (⌘P / Ctrl+P) inside a call's parentheses: Cuttlebone's
 * signature of the method the call resolves to, with the receiver's type
 * arguments filled in, else the `inko doc` definitions it may call. Each item
 * is a [CompiledSignature] or an [InkoDefinition].
 */
/** Cuttlebone's signature for a call, and whether it's from the last version that type checked ([stale]). */
data class CompiledSignature(val signature: SignatureInformation, val stale: Boolean)

class InkoParameterInfoHandler : ParameterInfoHandler<InkoName, Any> {
    override fun findElementForParameterInfo(context: CreateParameterInfoContext): InkoName? {
        val call = InkoCalls.at(context.file, context.offset) ?: return null
        val answer = CuttleboneQueries.getInstance(context.project).signatureHelp(context.file, context.offset)
        val compiled = answer?.value
        compiled?.signatures?.getOrNull(compiled.activeSignature ?: 0)?.let { signature ->
            context.itemsToShow = arrayOf(CompiledSignature(signature, answer.stale))
            return call.callee
        }
        // Cuttlebone has no signature for a type's constructor (`Point(x: 1)`), whose fields are shown instead;
        // otherwise, only in code it can't check does Parakeet's own reading of the call stand in.
        if (answer == null || (!answer.editing && !isConstructor(call.callee))) return null
        val candidates = InkoResolver.resolveDefinitions(call.callee).map { it.first }
            .filter { it.kind in CALLABLE }
            .distinctBy { it.signature to it.owner }
        if (candidates.isEmpty()) return null
        context.itemsToShow = candidates.toTypedArray()
        return call.callee
    }

    override fun showParameterInfo(element: InkoName, context: CreateParameterInfoContext) {
        val call = InkoCalls.at(context.file, context.offset) ?: return
        context.showHint(element, call.openParen + 1, this)
    }

    override fun findElementForUpdatingParameterInfo(context: UpdateParameterInfoContext): InkoName? =
        InkoCalls.at(context.file, context.offset)?.callee?.takeIf { it == context.parameterOwner }

    override fun updateParameterInfo(element: InkoName, context: UpdateParameterInfoContext) {
        // Cuttlebone's knows named arguments (`times: 1` is `times`'s, in any order); else commas are counted.
        val compiled = if (context.objectsToView.orEmpty().any { it is CompiledSignature }) {
            CuttleboneQueries.getInstance(element.project).signatureHelp(context.file, context.offset)?.value
        } else null
        val active = compiled?.let { help -> help.signatures?.getOrNull(help.activeSignature ?: 0)?.activeParameter ?: help.activeParameter }
        context.setCurrentParameter(active ?: InkoCalls.at(context.file, context.offset)?.argument ?: -1)
    }

    override fun updateUI(item: Any, context: ParameterInfoUIContext) {
        if (item is CompiledSignature) {
            val signature = item.signature
            val range = signature.parameters?.getOrNull(context.currentParameterIndex)?.label?.let { label ->
                if (label.isRight) label.right.first to label.right.second
                else signature.label.indexOf(label.left).takeIf { it >= 0 }?.let { it to it + label.left.length }
            } ?: (-1 to -1)
            // A stale signature is from the last version that type checked: shown, marked.
            val text = if (item.stale) "${signature.label}  (${CuttleboneQueries.OUTDATED})" else signature.label
            context.setupUIComponentPresentation(text, range.first, range.second, false, false, false, context.defaultParameterColor)
            return
        }
        val definition = item as InkoDefinition
        val path = context.parameterOwner?.containingFile?.virtualFile?.let(Inko::pathOf)
        val symbols = path?.let { InkoDocIndex.getInstance(context.parameterOwner.project).symbols(Inko.projectRoot(path)) }
        val parameters = InkoCalls.parameters(definition, symbols)
        if (parameters.isEmpty()) {
            context.setupUIComponentPresentation("<no parameters>", -1, -1, false, false, false, context.defaultParameterColor)
            return
        }
        val text = parameters.joinToString(", ")
        val current = context.currentParameterIndex
        val start = parameters.take(current.coerceAtLeast(0)).sumOf { it.length + 2 }
        val (highlightStart, highlightEnd) = if (current in parameters.indices) start to start + parameters[current].length else -1 to -1
        context.setupUIComponentPresentation(text, highlightStart, highlightEnd, false, false, false, context.defaultParameterColor)
    }

    private fun isConstructor(callee: InkoName): Boolean = callee.tokenType == InkoTokens.CONSTANT && !callee.previousToken().isToken(InkoTokens.DOT)

    private companion object {
        val CALLABLE = setOf(Kind.MODULE_METHOD, Kind.STATIC_METHOD, Kind.INSTANCE_METHOD, Kind.CONSTRUCTOR, Kind.TYPE)
    }
}
