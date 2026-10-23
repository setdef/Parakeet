package com.setdef.parakeet

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet

class InkoTokenType(debugName: String) : IElementType(debugName, InkoLanguage)

object InkoTokens {
    @JvmField val IDENTIFIER = InkoTokenType("IDENTIFIER")
    @JvmField val CONSTANT = InkoTokenType("CONSTANT")
    @JvmField val FIELD = InkoTokenType("FIELD")
    @JvmField val KEYWORD = InkoTokenType("KEYWORD")
    @JvmField val STRING = InkoTokenType("STRING")
    @JvmField val NUMBER = InkoTokenType("NUMBER")
    @JvmField val COMMENT = InkoTokenType("COMMENT")
    @JvmField val DOT = InkoTokenType(".")
    @JvmField val COMMA = InkoTokenType(",")
    @JvmField val LPAREN = InkoTokenType("(")
    @JvmField val RPAREN = InkoTokenType(")")
    @JvmField val LBRACKET = InkoTokenType("[")
    @JvmField val RBRACKET = InkoTokenType("]")
    @JvmField val LBRACE = InkoTokenType("{")
    @JvmField val RBRACE = InkoTokenType("}")
    @JvmField val OPERATOR = InkoTokenType("OPERATOR")
    @JvmField val WHITE_SPACE: IElementType = TokenType.WHITE_SPACE
    @JvmField val BAD_CHARACTER: IElementType = TokenType.BAD_CHARACTER

    @JvmField val NAMES = TokenSet.create(IDENTIFIER, CONSTANT, FIELD)
    @JvmField val COMMENTS = TokenSet.create(COMMENT)
    @JvmField val STRINGS = TokenSet.create(STRING)

    // From the Inko compiler's lexer (ast/src/lexer.rs).
    val KEYWORDS = setOf(
        "and", "as", "async", "await", "break", "builtin", "case", "copy", "else", "enum", "extern",
        "false", "fn", "for", "if", "impl", "import", "in", "inline", "let", "loop", "match", "move",
        "mut", "next", "nil", "noinline", "or", "pub", "recover", "ref", "return", "self", "static",
        "throw", "trait", "true", "try", "type", "uni", "while",
    )
}

/**
 * A small, stateless lexer that splits Inko source into tokens, so names can be
 * navigated and run markers placed. Colors come from the TextMate grammar.
 */
class InkoLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var end = 0
    private var tokenStart = 0
    private var tokenEnd = 0
    private var tokenType: IElementType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.end = endOffset
        this.tokenEnd = startOffset
        advance()
    }

    override fun getState(): Int = 0
    override fun getTokenType(): IElementType? = tokenType
    override fun getTokenStart(): Int = tokenStart
    override fun getTokenEnd(): Int = tokenEnd
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = end

    override fun advance() {
        tokenStart = tokenEnd
        if (tokenStart >= end) {
            tokenType = null
            return
        }
        var i = tokenStart
        val c = buffer[i]
        tokenType = when {
            c.isWhitespace() -> {
                while (i < end && buffer[i].isWhitespace()) i++
                InkoTokens.WHITE_SPACE
            }
            c == '#' -> {
                while (i < end && buffer[i] != '\n') i++
                InkoTokens.COMMENT
            }
            c == '"' || c == '\'' -> {
                i = skipString(i)
                InkoTokens.STRING
            }
            c.isDigit() -> {
                i++
                while (i < end && (buffer[i].isLetterOrDigit() || buffer[i] == '_' ||
                        (buffer[i] == '.' && i + 1 < end && buffer[i + 1].isDigit()))) i++
                InkoTokens.NUMBER
            }
            c == '@' && i + 1 < end && isNameStart(buffer[i + 1]) -> {
                i = skipName(i + 1)
                InkoTokens.FIELD
            }
            isNameStart(c) -> {
                i = skipName(i)
                val text = buffer.subSequence(tokenStart, i).toString()
                when {
                    text in InkoTokens.KEYWORDS -> InkoTokens.KEYWORD
                    c.isUpperCase() -> InkoTokens.CONSTANT
                    else -> InkoTokens.IDENTIFIER
                }
            }
            else -> {
                i++
                when (c) {
                    '.' -> InkoTokens.DOT
                    ',' -> InkoTokens.COMMA
                    '(' -> InkoTokens.LPAREN
                    ')' -> InkoTokens.RPAREN
                    '[' -> InkoTokens.LBRACKET
                    ']' -> InkoTokens.RBRACKET
                    '{' -> InkoTokens.LBRACE
                    '}' -> InkoTokens.RBRACE
                    else -> InkoTokens.OPERATOR
                }
            }
        }
        tokenEnd = i
    }

    private fun isNameStart(c: Char) = c == '_' || c.isLetter()

    // Names may contain `$` (for C functions like `readdir$INODE64`) and end in a single `?` (e.g. `empty?`), as
    // long as it's not `?=` or `??`. A method's name may end in `=` (a setter, `fn value=`), which is only taken
    // as part of the name right after `fn`, so `a=1` is still `a`, `=`, `1`.
    private fun skipName(from: Int): Int {
        var i = from
        while (i < end && (buffer[i] == '_' || buffer[i] == '$' || buffer[i].isLetterOrDigit())) i++
        if (i < end && buffer[i] == '?' && (i + 1 >= end || buffer[i + 1] != '=' && buffer[i + 1] != '?')) {
            i++
        } else if (i < end && buffer[i] == '=' && (i + 1 >= end || buffer[i + 1] != '=') && followsFn(from)) {
            i++
        }
        return i
    }

    /** Whether the name at [start] comes right after `fn` (and any modifiers, like `fn pub mut`). */
    private fun followsFn(start: Int): Boolean {
        var i = start
        while (true) {
            while (i > 0 && buffer[i - 1].isWhitespace()) i--
            val wordEnd = i
            while (i > 0 && (buffer[i - 1].isLetter() || buffer[i - 1] == '_')) i--
            when (buffer.subSequence(i, wordEnd).toString()) {
                "fn" -> return true
                "pub", "mut", "move", "static", "inline", "noinline", "async" -> continue
                else -> return false
            }
        }
    }

    // Skips a string, including `${...}` interpolation (which may nest strings).
    private fun skipString(from: Int): Int {
        val quote = buffer[from]
        var i = from + 1
        while (i < end) {
            when (buffer[i]) {
                '\\' -> i += 2
                quote -> return i + 1
                '$' -> if (i + 1 < end && buffer[i + 1] == '{') {
                    i = skipInterpolation(i + 2)
                } else {
                    i++
                }
                else -> i++
            }
        }
        return end
    }

    private fun skipInterpolation(from: Int): Int {
        var depth = 1
        var i = from
        while (i < end && depth > 0) {
            when (buffer[i]) {
                '{' -> { depth++; i++ }
                '}' -> { depth--; i++ }
                '"', '\'' -> i = skipString(i)
                else -> i++
            }
        }
        return i
    }
}
