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
    /** A string without interpolation, quotes included. */
    @JvmField val STRING = InkoTokenType("STRING")

    // A string with `${…}` is split around the code in it, as Inko's lexer does: `'a ${` (STRING_START), the
    // code's tokens, `} b ${` (STRING_MIDDLE), more code, and `} c'` (STRING_END).
    @JvmField val STRING_START = InkoTokenType("STRING_START")
    @JvmField val STRING_MIDDLE = InkoTokenType("STRING_MIDDLE")
    @JvmField val STRING_END = InkoTokenType("STRING_END")
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
    @JvmField val STRINGS = TokenSet.create(STRING, STRING_START, STRING_MIDDLE, STRING_END)

    // From the Inko compiler's lexer (ast/src/lexer.rs).
    val KEYWORDS = setOf(
        "and", "as", "async", "await", "break", "builtin", "case", "copy", "else", "enum", "extern",
        "false", "fn", "for", "if", "impl", "import", "in", "inline", "let", "loop", "match", "move",
        "mut", "next", "nil", "noinline", "or", "pub", "recover", "ref", "return", "self", "static",
        "throw", "trait", "true", "try", "type", "uni", "while",
    )
}

/**
 * A small lexer that splits Inko source into tokens, so names can be navigated
 * and run markers placed. Colors come from the TextMate grammar.
 *
 * Its state is how many interpolations (`${`) are open: code inside one is
 * lexed as code, as Inko's lexer does (ast/src/lexer.rs, `string_expression`
 * and `curly_close`), so lexing only restarts outside strings.
 */
class InkoLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var end = 0
    private var tokenStart = 0
    private var tokenEnd = 0
    private var tokenType: IElementType? = null

    /** For each open interpolation, innermost last: its string's quote, and the `{`s opened inside it. */
    private class Interpolation(val quote: Char, var braces: Int)

    private val interpolations = ArrayDeque<Interpolation>()

    /** The last token that wasn't whitespace or a comment. */
    private var previous: IElementType? = null

    /**
     * Where a method header is (ast/src/parser.rs, `define_method` and friends):
     * `fn [pub] [inline|noinline] [async [mut]|move|static|mut|extern] NAME`.
     * 0 outside one; 1 after `fn`; 2 after `pub`; 3 after `inline`; 4 after
     * `async`; 5 when only the name can follow.
     */
    private var fnStage = 0

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.end = endOffset
        this.tokenEnd = startOffset
        interpolations.clear()
        previous = null
        fnStage = 0
        advance()
    }

    override fun getState(): Int = interpolations.size * 8 + fnStage
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
        var nextFnStage = 0
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
                val (stop, opensCode) = skipStringText(i + 1, c)
                i = stop
                if (opensCode) InkoTokens.STRING_START.also { interpolations.addLast(Interpolation(c, 0)) } else InkoTokens.STRING
            }
            c == '{' && interpolations.isNotEmpty() -> {
                interpolations.last().braces++
                i++
                InkoTokens.LBRACE
            }
            c == '}' && interpolations.isNotEmpty() && interpolations.last().braces > 0 -> {
                interpolations.last().braces--
                i++
                InkoTokens.RBRACE
            }
            // The `}` closing an interpolation, and the string's text up to the next `${` or its end.
            c == '}' && interpolations.isNotEmpty() -> {
                val (stop, opensCode) = skipStringText(i + 1, interpolations.last().quote)
                i = stop
                if (opensCode) InkoTokens.STRING_MIDDLE else InkoTokens.STRING_END.also { interpolations.removeLast() }
            }
            // A `-` right before a digit is part of the number, as in Inko's lexer (ast/src/lexer.rs, `minus`).
            c.isDigit() || (c == '-' && i + 1 < end && buffer[i + 1].isDigit()) -> {
                if (c == '-') i++
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
                val modifier = methodModifier(text)
                // Inko's parser takes any keyword as a name in these places (ast/src/parser.rs): a method's name
                // (`method_name`), a name after `.` (`call_with_receiver`), and a named argument's label.
                val isName = fnStage > 0 || previous == InkoTokens.DOT ||
                    ((previous == InkoTokens.LPAREN || previous == InkoTokens.COMMA) && labels(i))
                when {
                    modifier != null -> InkoTokens.KEYWORD.also { nextFnStage = modifier }
                    isName && text in InkoTokens.KEYWORDS -> InkoTokens.IDENTIFIER
                    text in InkoTokens.KEYWORDS -> InkoTokens.KEYWORD.also { if (text == "fn") nextFnStage = 1 }
                    c.isUpperCase() -> InkoTokens.CONSTANT
                    else -> InkoTokens.IDENTIFIER
                }.also {
                    // A setter's name ends in `=` (`fn value=`), which Inko's `method_name` appends.
                    if (fnStage > 0 && modifier == null && i < end && buffer[i] == '=' && (i + 1 >= end || buffer[i + 1] != '=' && buffer[i + 1] != '>')) i++
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
        if (tokenType != InkoTokens.WHITE_SPACE && tokenType != InkoTokens.COMMENT) {
            previous = tokenType
            fnStage = nextFnStage
        }
    }

    /** The stage after [word] if it's a method modifier allowed at the current [fnStage], else null. */
    private fun methodModifier(word: String): Int? = when {
        fnStage == 0 || fnStage == 5 -> null
        fnStage == 1 && word == "pub" -> 2
        fnStage <= 2 && (word == "inline" || word == "noinline") -> 3
        fnStage <= 3 && word == "async" -> 4
        fnStage <= 3 && word in setOf("move", "static", "mut", "extern") -> 5
        fnStage == 4 && word == "mut" -> 5
        else -> null
    }

    /** Whether a `:` that isn't `:=` follows [from] (after spaces): what makes a word a named argument's label. */
    private fun labels(from: Int): Boolean {
        var i = from
        while (i < end && (buffer[i] == ' ' || buffer[i] == '\t')) i++
        return i < end && buffer[i] == ':' && (i + 1 >= end || buffer[i + 1] != '=' && buffer[i + 1] != ':')
    }

    private fun isNameStart(c: Char) = c == '_' || c.isLetter()

    // Names may contain `$` (for C functions like `readdir$INODE64`) and end in a single `?` (e.g. `empty?`), as
    // long as it's not `?=` or `??`.
    private fun skipName(from: Int): Int {
        var i = from
        while (i < end && (buffer[i] == '_' || buffer[i] == '$' || buffer[i].isLetterOrDigit())) i++
        if (i < end && buffer[i] == '?' && (i + 1 >= end || buffer[i + 1] != '=' && buffer[i + 1] != '?')) i++
        return i
    }

    /**
     * Skips string text from [from] to just past its closing [quote], or past
     * the `${` that starts code in it (then the second value is true), or to
     * the end. Escapes (including `\${`) are skipped whole.
     */
    private fun skipStringText(from: Int, quote: Char): Pair<Int, Boolean> {
        var i = from
        while (i < end) {
            when (buffer[i]) {
                '\\' -> i += 2
                quote -> return i + 1 to false
                '$' -> if (i + 1 < end && buffer[i + 1] == '{') return i + 2 to true else i++
                else -> i++
            }
        }
        return minOf(i, end) to false
    }
}
