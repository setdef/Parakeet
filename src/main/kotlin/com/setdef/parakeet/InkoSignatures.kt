package com.setdef.parakeet

import com.intellij.openapi.util.TextRange

/** A method parameter as written: `name: Type` (with any keywords before the name kept in [prefix]). */
data class InkoParameter(val name: String, val type: String, val prefix: String = "") {
    fun render(): String = "$prefix$name: $type"
}

/**
 * A method's header, parsed from tokens: `fn [modifiers] name[TypeParams](parameters) -> ReturnType`.
 * Ranges are offsets in the file's text.
 */
class InkoSignature(
    val name: String,
    val nameRange: TextRange,
    /** `[A, B: Trait]`, as written, or empty. */
    val typeParameters: String,
    val parameters: List<InkoParameter>,
    val returnType: String?,
    /** From the name through the return type (or whatever ends the header): what a new header replaces. */
    val headerRange: TextRange,
) {
    /** The header from the name on, for [name], [parameters], and [returnType]. */
    fun render(name: String, parameters: List<InkoParameter>, returnType: String?): String = buildString {
        append(name)
        append(typeParameters)
        if (parameters.isNotEmpty()) append(parameters.joinToString(", ", "(", ")") { it.render() })
        if (!returnType.isNullOrBlank()) append(" -> ").append(returnType.trim())
    }
}

/** A call's argument: `value`, or `name: value`. */
data class InkoArgument(val name: String?, val value: String)

/** A call (or other use) of a method by name: `name`, `name(…)`, `name[T](…)`, `x.name(…)`. */
class InkoCallSite(
    val nameRange: TextRange,
    /** `[Int, String]` explicit type arguments, as written, or empty. */
    val typeArguments: String,
    /** The `(…)`, or null when there are no parentheses. */
    val argumentsRange: TextRange?,
    val arguments: List<InkoArgument>,
) {
    /** From the name through the arguments: what an updated call replaces. */
    val range: TextRange get() = TextRange(nameRange.startOffset, argumentsRange?.endOffset ?: (nameRange.endOffset + typeArguments.length))
}

object InkoSignatures {
    /** The signature of the method whose name is at [nameOffset], or null if that's not a method with a plain name. */
    fun at(text: CharSequence, nameOffset: Int): InkoSignature? {
        val stream = InkoTokenStream(text)
        val tokens = stream.tokens
        val n = tokens.indexOfFirst { it.start == nameOffset }.takeIf { it >= 0 } ?: return null
        if (tokens[n].type != InkoTokens.IDENTIFIER) return null
        var i = n + 1
        var end = tokens[n].end

        var typeParameters = ""
        if (tokens.getOrNull(i)?.type == InkoTokens.LBRACKET && tokens[i].start == end) {
            val close = stream.match(i).takeIf { it > i } ?: return null
            typeParameters = text.substring(tokens[i].start, tokens[close].end)
            end = tokens[close].end
            i = close + 1
        }

        val parameters = mutableListOf<InkoParameter>()
        if (tokens.getOrNull(i)?.type == InkoTokens.LPAREN) {
            val close = stream.match(i).takeIf { it > i } ?: return null
            for (item in topLevelItems(stream, i, close)) {
                val itemText = text.substring(tokens[item.first].start, tokens[item.last].end)
                // `...` (C variadics) can't be expressed as a parameter.
                if ("..." in itemText) return null
                val colon = itemText.indexOf(':').takeIf { it >= 0 } ?: return null
                val head = itemText.substring(0, colon).trim()
                val name = head.substringAfterLast(' ')
                val prefix = head.removeSuffix(name)
                parameters += InkoParameter(name, itemText.substring(colon + 1).trim(), prefix)
            }
            end = tokens[close].end
            i = close + 1
        }

        var returnType: String? = null
        if (tokens.getOrNull(i)?.isOperator("-") == true && tokens.getOrNull(i + 1)?.isOperator(">") == true && tokens[i + 1].start == tokens[i].end) {
            val first = i + 2
            var j = first
            var last = -1
            // Up to the body's `{`, or (without a body) the end of the line.
            while (j < tokens.size) {
                val token = tokens[j]
                if (token.type == InkoTokens.LBRACE) break
                if (j > first && stream.lineBreakBetween(j - 1, j)) break
                if (token.opens) {
                    val close = stream.match(j).takeIf { it > j } ?: break
                    last = close
                    j = close + 1
                    continue
                }
                last = j
                j++
            }
            if (last < first) return null
            returnType = text.substring(tokens[first].start, tokens[last].end)
            end = tokens[last].end
        }
        return InkoSignature(tokens[n].text, tokens[n].range, typeParameters, parameters, returnType, TextRange(tokens[n].start, end))
    }

    /** The call (or use) whose name token starts at [nameOffset]. */
    fun callAt(text: CharSequence, nameOffset: Int): InkoCallSite? {
        val stream = InkoTokenStream(text)
        val tokens = stream.tokens
        val n = tokens.indexOfFirst { it.start == nameOffset }.takeIf { it >= 0 } ?: return null
        var i = n + 1
        var typeArguments = ""
        if (tokens.getOrNull(i)?.type == InkoTokens.LBRACKET && tokens[i].start == tokens[n].end) {
            val close = stream.match(i).takeIf { it > i } ?: return null
            // `name[…]` followed by `(` is a call with type arguments; otherwise it's indexing the result.
            if (tokens.getOrNull(close + 1)?.type == InkoTokens.LPAREN && tokens[close + 1].start == tokens[close].end) {
                typeArguments = text.substring(tokens[i].start, tokens[close].end)
                i = close + 1
            }
        }
        val open = tokens.getOrNull(i)
        val adjacent = open != null && open.start == (if (typeArguments.isEmpty()) tokens[n].end else tokens[i - 1].end)
        if (open?.type != InkoTokens.LPAREN || !adjacent) return InkoCallSite(tokens[n].range, typeArguments, null, emptyList())
        val close = stream.match(i).takeIf { it > i } ?: return null
        val arguments = topLevelItems(stream, i, close).map { item ->
            val first = tokens[item.first]
            val named = first.type == InkoTokens.IDENTIFIER && item.last > item.first &&
                tokens[item.first + 1].isOperator(":") &&
                tokens.getOrNull(item.first + 2)?.let { it.isOperator("=") && it.start == tokens[item.first + 1].end } != true
            val valueStart = if (named) item.first + 2 else item.first
            InkoArgument(if (named) first.text else null, text.substring(tokens[valueStart].start, tokens[item.last].end))
        }
        return InkoCallSite(tokens[n].range, typeArguments, TextRange(tokens[i].start, tokens[close].end), arguments)
    }

    /** The comma-separated items between the brackets at [open] and [close], as token index ranges. */
    private fun topLevelItems(stream: InkoTokenStream, open: Int, close: Int): List<IntRange> {
        val items = mutableListOf<IntRange>()
        var start = open + 1
        var i = open + 1
        while (i < close) {
            val token = stream.tokens[i]
            if (token.type == InkoTokens.COMMA) {
                if (i > start) items += start until i
                start = i + 1
                i++
                continue
            }
            i = if (token.opens && stream.match(i) > i) stream.match(i) + 1 else i + 1
        }
        if (close > start) items += start until close
        return items
    }
}
