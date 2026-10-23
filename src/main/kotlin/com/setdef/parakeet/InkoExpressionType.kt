package com.setdef.parakeet

import com.intellij.lang.ExpressionTypeProvider
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.setdef.parakeet.InkoDefinitionKind as Kind

/**
 * Expression Type (⌃⇧P / Ctrl+Shift+P). Without the compiler's type checker,
 * types come from what's written: literals, comparisons, casts, annotated and
 * constructed locals, `self`, fields, constructors, and the return types in
 * method signatures. Anything else says it's unknown, rather than guessing.
 */
class InkoExpressionTypeProvider : ExpressionTypeProvider<PsiElement>() {
    override fun getInformationHint(element: PsiElement): String {
        val expression = element as? InkoExpression ?: return UNKNOWN
        return InkoExpressionTypes.of(expression.containingFile, expression.textRange)?.let(StringUtil::escapeXmlEntities) ?: UNKNOWN
    }

    override fun getErrorHint(): String = "No Inko expression at the caret"

    override fun getExpressionsAt(elementAt: PsiElement): List<PsiElement> {
        val file = elementAt.containingFile as? InkoFile ?: return emptyList()
        val stream = InkoTokenStream(file.text)
        return InkoIntroduceVariable.candidatesAt(stream, elementAt.textRange.startOffset).map { InkoExpression(file, it) }
    }

    private companion object {
        const val UNKNOWN = "Unknown: Parakeet can only tell types written in the code, not ones the compiler infers"
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

object InkoExpressionTypes {
    private val COMPARISONS = setOf("==", "!=", "<", ">", "<=", ">=")
    private val BUILTIN = setOf("Self", "Never", "Nil")
    private const val MAX_DEPTH = 4

    /** The type of the expression at [range] in [file], when the code says what it is. */
    fun of(file: PsiFile, range: TextRange, depth: Int = 0): String? {
        if (depth > MAX_DEPTH) return null
        val stream = InkoTokenStream(file.text)
        val all = stream.tokens
        val first = all.indexOfFirst { it.start >= range.startOffset }.takeIf { it >= 0 } ?: return null
        val last = all.indexOfLast { it.end <= range.endOffset }.takeIf { it >= first } ?: return null
        val tokens = all.subList(first, last + 1)

        binary(stream, first, last)?.let { (operator, left) ->
            return when (operator) {
                in COMPARISONS, "and", "or" -> "Bool"
                "as" -> file.text.substring(all[left + 1].start, all[last].end).trim()
                // Arithmetic on numbers and strings gives the same type back.
                else -> of(file, TextRange(all[first].start, all[left - 1].end), depth + 1)?.takeIf { it in setOf("Int", "Float", "String") }
            }
        }
        val head = tokens.first()
        if (head.isOperator("!")) return "Bool"
        if (head.isOperator("-") && tokens.size > 1) return of(file, TextRange(tokens[1].start, range.endOffset), depth + 1)

        if (tokens.size == 1) literal(head)?.let { return it }
        // `( … )` and `[ … ]` around the whole thing.
        if (head.opens && stream.match(first) == last) {
            val inner = TextRange(head.end, all[last].start)
            return when (head.type) {
                InkoTokens.STRING_START -> "String"
                InkoTokens.LPAREN -> of(file, inner, depth + 1)
                InkoTokens.LBRACKET -> firstElement(stream, first, last)?.let { of(file, it, depth + 1) }?.let { "Array[$it]" }
                else -> null
            }
        }
        return chain(file, stream, first, last, depth)
    }

    private fun literal(token: InkoToken): String? = when {
        token.type == InkoTokens.STRING -> "String"
        token.type == InkoTokens.NUMBER -> if (!token.text.startsWith("0x") && (token.text.contains('.') || token.text.contains('e'))) "Float" else "Int"
        token.isKeyword("true", "false") -> "Bool"
        token.isKeyword("nil") -> "Nil"
        else -> null
    }

    /** The last top-level binary operator in [first]..[last] (operators group left to right), and its index. */
    private fun binary(stream: InkoTokenStream, first: Int, last: Int): Pair<String, Int>? {
        val tokens = stream.tokens
        var found: Pair<String, Int>? = null
        var i = first
        while (i <= last) {
            val token = tokens[i]
            if (token.opens) {
                i = stream.match(i).takeIf { it > i }?.plus(1) ?: return null
                continue
            }
            if (i > first && token.isKeyword("and", "or", "as")) found = token.text to i
            // A binary operator follows an operand; a prefix one (`-x`, `!x`) doesn't.
            if (i > first && token.isOperator() && !tokens[i - 1].isOperator()) {
                var end = i
                while (end + 1 <= last && tokens[end + 1].isOperator() && tokens[end + 1].start == tokens[end].end) end++
                val text = (i..end).joinToString("") { tokens[it].text }
                if (text != "=" && text != ":") found = text to end
                i = end + 1
                continue
            }
            i++
        }
        return found?.let { (text, end) -> text to if (text in setOf("and", "or", "as")) end else end - (text.length - 1) }
    }

    private fun firstElement(stream: InkoTokenStream, open: Int, close: Int): TextRange? {
        val tokens = stream.tokens
        if (close == open + 1) return null
        var end = open + 1
        while (end < close && tokens[end].type != InkoTokens.COMMA) {
            end = if (tokens[end].opens && stream.match(end) > end) stream.match(end) + 1 else end + 1
        }
        return TextRange(tokens[open + 1].start, tokens[end - 1].end)
    }

    /** `name`, `name(…)`, `a.b.c(…)`: the type of the last step. */
    private fun chain(file: PsiFile, stream: InkoTokenStream, first: Int, last: Int, depth: Int): String? {
        val tokens = stream.tokens
        // The last step's name: before a trailing call's `(`, or the last token itself.
        var nameIndex = last
        if (tokens[last].type == InkoTokens.RPAREN) {
            val open = stream.match(last).takeIf { it > first } ?: return null
            nameIndex = open - 1
            if (tokens[nameIndex].type == InkoTokens.RBRACKET) nameIndex = stream.match(nameIndex) - 1 // `name[T](…)`
        }
        val token = tokens.getOrNull(nameIndex)?.takeIf { it.start >= tokens[first].start } ?: return null
        if (token.isKeyword("self")) return if (nameIndex == first) InkoLocals.selfType(file.text, token.start) else null
        val name = PsiTreeUtil.getParentOfType(file.findElementAt(token.start), InkoName::class.java, false) ?: return null
        val afterDot = nameIndex > first && tokens[nameIndex - 1].type == InkoTokens.DOT

        if (!afterDot && token.type == InkoTokens.IDENTIFIER) {
            InkoLocals.visibleAt(file.text, token.start).lastOrNull { it.name == token.text }?.let { local ->
                return localType(file, stream, local, depth)
            }
        }
        var definitions = InkoResolver.resolveDefinitions(name).map { it.first }
        if (afterDot) {
            val receiver = tokens[nameIndex - 2]
            val static = nameIndex - 2 == first && (receiver.type == InkoTokens.CONSTANT || receiver.type == InkoTokens.IDENTIFIER &&
                InkoImports.parse(file.text).moduleAliasedAs(receiver.text) != null && InkoLocals.visibleAt(file.text, receiver.start).none { it.name == receiver.text })
            if (!static) {
                // Without the receiver's type, the method could be any type's; only trust one we know.
                val receiverType = of(file, TextRange(tokens[first].start, receiver.end), depth + 1) ?: return null
                val owner = typeName(receiverType)
                definitions = definitions.filter { it.owner == owner }
            }
        }
        return definitions.map { definition -> typeOf(file, definition) }.distinct().singleOrNull()
    }

    /** The type's own name, without `ref`/`mut`/`uni` or type arguments: `Array` for `ref Array[Int]`. */
    private fun typeName(type: String): String =
        type.trim().replace(Regex("^((ref|mut|uni|move)\\s+)+"), "").substringBefore('[').trim()

    /** A local's annotated type, or the type of what it's assigned. */
    private fun localType(file: PsiFile, stream: InkoTokenStream, local: InkoLocal, depth: Int): String? {
        val tokens = stream.tokens
        val index = tokens.indexOfFirst { it.start == local.offset }.takeIf { it >= 0 } ?: return local.type
        val next = tokens.getOrNull(index + 1)
        if (next?.isOperator(":") == true && tokens.getOrNull(index + 2)?.let { it.isOperator("=") && it.start == next.end } != true) {
            return annotation(stream, index + 2)
        }
        // `let x = value`: the value runs to the end of the statement.
        if (next?.isOperator("=") == true && tokens.getOrNull(index - 1)?.isKeyword("let", "mut") == true) {
            val end = stream.statementEnd(index)
            if (end > index + 1) return of(file, TextRange(tokens[index + 2].start, tokens[end].end), depth + 1)
        }
        return local.type
    }

    /** The type written from [start] up to a top-level `=`, `,`, `)`, or the line's end. */
    private fun annotation(stream: InkoTokenStream, start: Int): String? {
        val tokens = stream.tokens
        var end = start
        while (end < tokens.size) {
            val token = tokens[end]
            if (token.opens) { end = stream.match(end).takeIf { it > end }?.plus(1) ?: return null; continue }
            if (token.isOperator("=") || token.type == InkoTokens.COMMA || token.closes || token.type == InkoTokens.LBRACE) break
            if (end > start && stream.lineBreakBetween(end - 1, end)) break
            end++
        }
        if (end == start) return null
        return stream.text.substring(tokens[start].start, tokens[end - 1].end)
    }

    /** What using [definition] gives: a type for constructors and types, the return type for calls, a field's type. */
    private fun typeOf(file: PsiFile, definition: InkoDefinition): String? = when (definition.kind) {
        Kind.TYPE -> definition.name
        Kind.CONSTRUCTOR -> definition.owner
        Kind.FIELD -> definition.signature?.substringAfter(":", "")?.trim()?.takeIf { it.isNotEmpty() }
        // `inko doc` writes `let pub LIMIT: Int` (compiler/src/docs.rs); source has `let pub LIMIT = 10`.
        Kind.CONSTANT -> definition.signature?.let { signature ->
            val declared = signature.substringAfter(" ${definition.name}", "").trimStart()
            if (declared.startsWith(":")) {
                declared.removePrefix(":").substringBefore("=").trim().takeIf { it.isNotEmpty() }
            } else {
                InkoTokenStream(signature.substringAfter("=", "").trim()).tokens.singleOrNull()?.let(::literal)
            }
        }
        Kind.MODULE_METHOD, Kind.STATIC_METHOD, Kind.INSTANCE_METHOD -> returnType(file, definition)
        else -> null
    }

    /** The return type in [definition]'s signature (`Nil` without one), with `Self` filled in. Null if it uses a type parameter. */
    private fun returnType(file: PsiFile, definition: InkoDefinition): String? {
        val signature = definition.signature ?: return null
        val stream = InkoTokenStream(signature)
        val tokens = stream.tokens
        var i = 0
        var arrow = -1
        while (i < tokens.size) {
            if (tokens[i].opens) { i = stream.match(i).takeIf { it > i }?.plus(1) ?: return null; continue }
            if (tokens[i].isOperator("-") && tokens.getOrNull(i + 1)?.let { it.isOperator(">") && it.start == tokens[i].end } == true) { arrow = i + 2; break }
            i++
        }
        if (arrow < 0) return "Nil"
        if (arrow >= tokens.size) return null
        val type = signature.substring(tokens[arrow].start).trim()
        val owner = definition.owner
        val symbols = Inko.pathOf(file.virtualFile)?.let { InkoDocIndex.getInstance(file.project).symbols(Inko.projectRoot(it)) }
        // A name that isn't a type anywhere is a type parameter, which only the type checker can fill in.
        val names = InkoTokenStream(type).tokens.filter { it.type == InkoTokens.CONSTANT }.map { it.text }
        if (names.any { it !in BUILTIN && symbols?.named(it, Kind.TYPE, Kind.TRAIT).isNullOrEmpty() }) return null
        return if (owner != null) type.replace(Regex("\\bSelf\\b"), owner) else type
    }
}
