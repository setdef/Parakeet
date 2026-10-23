package com.setdef.parakeet

import com.intellij.openapi.util.TextRange
import com.intellij.psi.tree.IElementType

/** A token, without whitespace or comments in between. */
class InkoToken(val type: IElementType, val text: String, val start: Int, val end: Int) {
    val range: TextRange get() = TextRange(start, end)

    fun isOperator(vararg texts: String) = type == InkoTokens.OPERATOR && (texts.isEmpty() || text in texts)

    fun isKeyword(vararg texts: String) = type == InkoTokens.KEYWORD && text in texts

    // An interpolated string (`'a ${x} b'`) pairs up like brackets: its first part opens it, and its last closes it.
    val opens: Boolean get() = type == InkoTokens.LPAREN || type == InkoTokens.LBRACKET || type == InkoTokens.LBRACE || type == InkoTokens.STRING_START
    val closes: Boolean get() = type == InkoTokens.RPAREN || type == InkoTokens.RBRACKET || type == InkoTokens.RBRACE || type == InkoTokens.STRING_END

    /** Any part of a string literal: a whole string, or a part of an interpolated one. */
    val isString: Boolean get() = type in InkoTokens.STRINGS
}

/**
 * A file's tokens with what refactorings need to reason about expressions and
 * statements: matching brackets, enclosing blocks, and where statements start.
 */
class InkoTokenStream(val text: CharSequence) {
    val tokens: List<InkoToken> = buildList {
        val lexer = InkoLexer()
        lexer.start(text, 0, text.length, 0)
        while (lexer.tokenType != null) {
            val type = lexer.tokenType!!
            if (type != InkoTokens.WHITE_SPACE && type != InkoTokens.COMMENT) {
                add(InkoToken(type, text.substring(lexer.tokenStart, lexer.tokenEnd), lexer.tokenStart, lexer.tokenEnd))
            }
            lexer.advance()
        }
    }

    // Pairs each closer with the nearest unclosed opener of its kind; openers left in between stay unmatched (-1).
    // A STRING_MIDDLE is matched to its string's STRING_START, which stays open.
    private val matches: IntArray = IntArray(tokens.size) { -1 }.also { matches ->
        val open = ArrayDeque<Int>()
        tokens.forEachIndexed { index, token ->
            when {
                token.opens -> open.addLast(index)
                token.type == InkoTokens.STRING_MIDDLE -> {
                    val opener = open.indexOfLast { tokens[it].type == InkoTokens.STRING_START }
                    if (opener >= 0) {
                        while (open.size > opener + 1) open.removeLast()
                        matches[index] = open[opener]
                    }
                }
                token.closes -> {
                    val opener = open.indexOfLast { pairs(tokens[it], token) }
                    if (opener >= 0) {
                        val start = open[opener]
                        while (open.size > opener) open.removeLast()
                        matches[start] = index
                        matches[index] = start
                    }
                }
            }
        }
    }

    private fun pairs(open: InkoToken, close: InkoToken): Boolean =
        (open.type == InkoTokens.LPAREN && close.type == InkoTokens.RPAREN) ||
            (open.type == InkoTokens.LBRACKET && close.type == InkoTokens.RBRACKET) ||
            (open.type == InkoTokens.LBRACE && close.type == InkoTokens.RBRACE) ||
            (open.type == InkoTokens.STRING_START && close.type == InkoTokens.STRING_END)

    /** The index of the bracket matching the one at [index] (for a STRING_MIDDLE, its STRING_START), or -1. */
    fun match(index: Int): Int = matches.getOrElse(index) { -1 }

    /** The index of the first token starting at or after [offset]. */
    fun indexAt(offset: Int): Int = tokens.indexOfFirst { it.end > offset }.let { if (it < 0) tokens.size else it }

    /** Whether a line break separates tokens [a] and [b]. */
    fun lineBreakBetween(a: Int, b: Int): Boolean =
        text.subSequence(tokens[a].end, tokens[b].start).contains('\n')

    /** The `{` of the innermost block containing [offset], or -1 for the file itself. */
    fun enclosingBlock(offset: Int): Int {
        var best = -1
        tokens.forEachIndexed { index, token ->
            if (token.type == InkoTokens.LBRACE && token.end <= offset) {
                val close = match(index)
                if (close < 0 || tokens[close].start >= offset) best = index
            }
        }
        return best
    }

    /** The `{` of every block containing [offset], outermost first. */
    fun enclosingBlocks(offset: Int): List<Int> = tokens.indices.filter { index ->
        val close = match(index)
        tokens[index].type == InkoTokens.LBRACE && tokens[index].end <= offset && (close < 0 || tokens[close].start >= offset)
    }

    /**
     * The index of the first token of the statement containing [offset], within
     * the block opened at [block]: a token at the block's own level that starts
     * a line, unless the line continues the previous one (it starts with `.`,
     * or the previous line ends with an operator, `,`, `.`, or an open bracket).
     */
    fun statementStart(block: Int, offset: Int): Int {
        val first = block + 1
        val last = indexAt(offset).coerceAtMost(tokens.size - 1)
        var start = first
        var i = first
        while (i <= last) {
            if (i > first && lineBreakBetween(i - 1, i) && !continues(i)) start = i
            if (tokens[i].opens) {
                val close = match(i)
                // A bracket containing [offset] holds it within this statement (e.g. an `if`'s block): stop here.
                if (close < 0 || close > last) break
                // Skip other nested brackets: their contents belong to the statement they're in.
                i = close + 1
            } else {
                i++
            }
        }
        return start
    }

    /**
     * The index of the last token of the statement starting at [index]: up to
     * the next line that doesn't continue it, or the enclosing block's end.
     */
    fun statementEnd(index: Int): Int {
        var i = index
        while (true) {
            if (tokens[i].opens) {
                val close = match(i)
                if (close < 0) return tokens.size - 1
                i = close
            }
            val next = i + 1
            if (next >= tokens.size || tokens[next].closes) return i
            if (lineBreakBetween(i, next) && !continues(next)) return i
            i = next
        }
    }

    /** The statements in the block opened at [block] (-1 for the file's top level), as token index ranges. */
    fun statementsIn(block: Int): List<IntRange> {
        val close = if (block < 0) tokens.size else match(block).let { if (it < 0) tokens.size else it }
        val result = mutableListOf<IntRange>()
        var start = block + 1
        while (start < close) {
            val end = statementEnd(start).coerceAtMost(close - 1)
            result += start..end
            start = end + 1
        }
        return result
    }

    /**
     * Whether the `if` at [index] starts an `if` expression. In Inko's grammar
     * (ast/src/parser.rs) an `if` is also a match arm's guard
     * (`optional_match_guard`), an impl's bounds (`optional_type_bounds`), and
     * an import's build tags (`build_tags`): those follow a `case`, `impl`, or
     * `import` in the same statement.
     */
    fun startsIfExpression(index: Int): Boolean {
        if (tokens.getOrNull(index)?.isKeyword("if") != true) return false
        var i = index - 1
        while (i >= 0) {
            val token = tokens[i]
            when {
                token.isKeyword("case", "impl", "import") -> return false
                // A statement or expression starts before here: after a line that doesn't continue, an opening
                // bracket we're inside, an assignment, a match arm's `->`, or a keyword that takes an expression.
                lineBreakBetween(i, i + 1) && !continues(i + 1) -> return true
                token.opens -> return true
                token.closes -> { i = match(i).let { if (it < 0) return true else it - 1 }; continue }
                token.isOperator("=") || token.type == InkoTokens.COMMA -> return true
                token.isOperator(">") && tokens.getOrNull(i - 1)?.let { it.isOperator("-") && it.end == token.start } == true -> return true
                token.isKeyword("else", "return", "throw", "let", "and", "or", "try", "recover") -> return true
            }
            i--
        }
        return true
    }

    /** Whether the line starting at token [index] continues the previous line's statement. */
    fun continues(index: Int): Boolean {
        val previous = tokens[index - 1]
        val current = tokens[index]
        // `!x` starts a new `Not` expression (ast/src/parser.rs, `not_expression`); `!=` continues a binary one.
        val startsNot = current.isOperator("!") && tokens.getOrNull(index + 1)?.let { it.isOperator("=") && it.start == current.end } != true
        // A bare `return` only takes a value on its own line (`return_expression`); `throw` and the rest take one from any line.
        return current.type == InkoTokens.DOT || (current.isOperator() && !startsNot) || current.isKeyword("and", "or", "as", "else") ||
            previous.type == InkoTokens.DOT || previous.type == InkoTokens.COMMA || previous.isOperator() ||
            previous.isKeyword("and", "or", "as", "let", "throw", "try", "recover", "ref", "mut", "move") ||
            previous.type == InkoTokens.LPAREN || previous.type == InkoTokens.LBRACKET
    }
}
