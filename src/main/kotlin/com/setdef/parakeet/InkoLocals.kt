package com.setdef.parakeet

import com.intellij.psi.tree.IElementType

/** A local variable or parameter: its name, where it's declared, and its type when written or obvious. */
data class InkoLocal(val name: String, val offset: Int, val type: String?)

/**
 * Local variables and parameters, found from tokens. Scopes follow braces:
 * method and closure parameters belong to the block that follows them, and
 * `let` and `case` bindings to the block they're in. Types come from
 * annotations (`h: Helper`, `let x: ref Foo`) or obvious constructions
 * (`let x = Foo.new`, `let x = Foo(…)`).
 */
object InkoLocals {
    private class Token(val type: IElementType, val text: String, val start: Int)

    private val TYPE_MODIFIERS = setOf("ref", "mut", "uni", "move")
    private val OPENERS = setOf(InkoTokens.LPAREN, InkoTokens.LBRACKET, InkoTokens.LBRACE, InkoTokens.STRING_START)
    private val CLOSERS = setOf(InkoTokens.RPAREN, InkoTokens.RBRACKET, InkoTokens.RBRACE, InkoTokens.STRING_END)

    /** Locals visible at [offset]: declared before it, in blocks still open there. Later declarations shadow earlier ones. */
    fun visibleAt(text: CharSequence, offset: Int): List<InkoLocal> = scan(text, offset).first

    /** Every local declared in [text] (in any scope). */
    fun declarations(text: CharSequence): List<InkoLocal> = scan(text, text.length).second

    /** The local declared by the name at [offset], if it declares one (in any scope). */
    fun declaredAt(text: CharSequence, offset: Int): InkoLocal? = scan(text, text.length).second.firstOrNull { it.offset == offset }

    /** Locals visible at [end], and every local declared before it. */
    private fun scan(text: CharSequence, end: Int): Pair<List<InkoLocal>, List<InkoLocal>> {
        val tokens = tokens(text, end)
        val declared = mutableListOf<InkoLocal>()
        // Each open block's locals, and whether the block is a `match`'s (whose `case`s are arms).
        val scopes = ArrayDeque<MutableList<InkoLocal>>().apply { addLast(mutableListOf()) }
        val matchBlocks = ArrayDeque<Boolean>().apply { addLast(false) }
        var pendingParameters: List<InkoLocal>? = null
        // The `{` of a `for` loop's body or a `match`'s block, with what the loop binds.
        val loopBodies = mutableMapOf<Int, List<InkoLocal>>()
        val matchBodies = mutableSetOf<Int>()
        var i = 0

        fun at(index: Int) = tokens.getOrNull(index)
        fun isOperator(index: Int, text: String) = at(index)?.let { it.type == InkoTokens.OPERATOR && it.text == text } == true
        fun isKeyword(index: Int, vararg texts: String) = at(index)?.let { it.type == InkoTokens.KEYWORD && it.text in texts } == true

        /** The type named after a `:` at [colon], skipping `ref`/`mut`/`uni`/`move`. */
        fun annotatedType(colon: Int): String? {
            var j = colon + 1
            while (at(j)?.let { it.type == InkoTokens.KEYWORD && it.text in TYPE_MODIFIERS } == true) j++
            return at(j)?.takeIf { it.type == InkoTokens.CONSTANT }?.text
        }

        fun declare(local: InkoLocal) {
            scopes.last() += local
            declared += local
        }

        /** Parameters in the parentheses opening at [open]; returns them and the index after `)`. */
        fun parameters(open: Int): Pair<List<InkoLocal>, Int> {
            val found = mutableListOf<InkoLocal>()
            var depth = 0
            var j = open
            var expectName = true
            while (j < tokens.size) {
                val t = tokens[j]
                when (t.type) {
                    InkoTokens.LPAREN, InkoTokens.LBRACKET -> depth++
                    InkoTokens.RPAREN, InkoTokens.RBRACKET -> { depth--; if (depth == 0) return found to j + 1 }
                    InkoTokens.COMMA -> if (depth == 1) expectName = true
                    InkoTokens.IDENTIFIER -> if (depth == 1 && expectName) {
                        expectName = false
                        found += InkoLocal(t.text, t.start, if (isOperator(j + 1, ":")) annotatedType(j + 1) else null)
                    }
                    InkoTokens.KEYWORD -> {} // `mut`/`move` before a parameter name
                    else -> if (depth == 1) expectName = false
                }
                j++
            }
            return found to j
        }

        /** The index of the bracket closing the one at [open], or the end. */
        fun closing(open: Int): Int {
            var depth = 0
            for (j in open until tokens.size) {
                if (tokens[j].type in OPENERS) depth++ else if (tokens[j].type in CLOSERS && --depth == 0) return j
            }
            return tokens.size
        }

        /**
         * A type annotation from [from] (just past its `:`): up to the `,`, `=`, closing bracket, or keyword that
         * ends it in a pattern. Returns the index after it.
         */
        fun skipType(from: Int): Int {
            var j = from
            while (j < tokens.size) {
                val t = tokens[j]
                when {
                    t.type == InkoTokens.LPAREN || t.type == InkoTokens.LBRACKET -> j = closing(j) + 1
                    t.type in CLOSERS || t.type == InkoTokens.COMMA || t.type == InkoTokens.LBRACE -> return j
                    t.type == InkoTokens.OPERATOR && t.text == "=" -> return j
                    // A closure type, `fn (A) -> B`: its own `->` doesn't end the annotation.
                    t.type == InkoTokens.KEYWORD && t.text == "fn" -> {
                        j++
                        if (at(j)?.type == InkoTokens.LPAREN) j = closing(j) + 1
                        if (isOperator(j, "-") && isOperator(j + 1, ">")) j += 2
                    }
                    // A match arm's `->`.
                    t.type == InkoTokens.OPERATOR && t.text == "-" && isOperator(j + 1, ">") -> return j
                    t.type == InkoTokens.KEYWORD && t.text in setOf("or", "in", "if", "else") -> return j
                    else -> j++
                }
            }
            return j
        }

        /**
         * A pattern from [start], following Inko's `pattern` (ast/src/parser.rs): its identifier bindings (not `_`,
         * and not the `mod` of a `mod.CONSTANT` pattern), and the index after it.
         */
        fun pattern(start: Int, bindings: MutableList<InkoLocal>): Int {
            fun list(open: Int): Int {
                var j = open + 1
                while (at(j) != null && at(j)!!.type !in CLOSERS) {
                    val next = pattern(j, bindings)
                    if (next == j) return closing(open) + 1
                    j = next
                    if (at(j)?.type == InkoTokens.COMMA) j++ else break
                }
                return if (at(j)?.type in CLOSERS) j + 1 else j
            }

            fun one(from: Int): Int {
                val t = at(from) ?: return from
                return when {
                    t.type == InkoTokens.IDENTIFIER && t.text == "_" -> from + 1
                    t.type == InkoTokens.IDENTIFIER && at(from + 1)?.type == InkoTokens.DOT -> from + 3
                    t.type == InkoTokens.IDENTIFIER || (t.type == InkoTokens.KEYWORD && t.text == "mut") -> {
                        val name = if (t.type == InkoTokens.KEYWORD) from + 1 else from
                        val token = at(name)?.takeIf { it.type == InkoTokens.IDENTIFIER } ?: return name
                        if (isOperator(name + 1, ":") && !isOperator(name + 2, "=")) {
                            bindings += InkoLocal(token.text, token.start, annotatedType(name + 1))
                            skipType(name + 2)
                        } else {
                            bindings += InkoLocal(token.text, token.start, null)
                            name + 1
                        }
                    }
                    t.type == InkoTokens.CONSTANT && at(from + 1)?.type == InkoTokens.LPAREN -> list(from + 1)
                    t.type == InkoTokens.LPAREN || t.type == InkoTokens.LBRACKET -> list(from)
                    t.type == InkoTokens.LBRACE -> {
                        // `{ @field = pattern, … }`
                        var j = from + 1
                        while (at(j)?.type == InkoTokens.FIELD && isOperator(j + 1, "=")) {
                            j = pattern(j + 2, bindings)
                            if (at(j)?.type == InkoTokens.COMMA) j++ else break
                        }
                        if (at(j)?.type == InkoTokens.RBRACE) j + 1 else j
                    }
                    t.type == InkoTokens.STRING_START -> closing(from) + 1
                    t.type == InkoTokens.OPERATOR && t.text == "-" && at(from + 1)?.type == InkoTokens.NUMBER -> from + 2
                    t.type == InkoTokens.CONSTANT || t.type == InkoTokens.NUMBER || t.type == InkoTokens.STRING ||
                        (t.type == InkoTokens.KEYWORD && t.text in setOf("true", "false")) -> from + 1
                    else -> from
                }
            }

            var j = one(start)
            while (isKeyword(j, "or")) j = one(j + 1)
            return j
        }

        /** The `{` that a construct's header from [from] leads to (an `if`'s, a loop's, a `match`'s), skipping closures. */
        fun bodyBrace(from: Int): Int? {
            var j = from
            while (j < tokens.size) {
                val t = tokens[j]
                when {
                    t.type == InkoTokens.LBRACE -> return j
                    t.type == InkoTokens.LPAREN || t.type == InkoTokens.LBRACKET || t.type == InkoTokens.STRING_START -> j = closing(j) + 1
                    t.type in CLOSERS -> return null
                    // A closure in the header (`items.map(fn …)` is inside parentheses; this is a bare one).
                    t.type == InkoTokens.KEYWORD && t.text == "fn" -> {
                        var k = j + 1
                        while (k < tokens.size && tokens[k].type != InkoTokens.LBRACE) k++
                        j = closing(k) + 1
                    }
                    else -> j++
                }
            }
            return null
        }

        while (i < tokens.size) {
            val t = tokens[i]
            when {
                t.type == InkoTokens.LBRACE -> {
                    val bound = loopBodies[i] ?: pendingParameters.orEmpty()
                    declared += bound
                    scopes.addLast(bound.toMutableList())
                    matchBlocks.addLast(i in matchBodies)
                    pendingParameters = null
                    i++
                }
                t.type == InkoTokens.RBRACE -> {
                    if (scopes.size > 1) {
                        scopes.removeLast()
                        matchBlocks.removeLast()
                    }
                    pendingParameters = null
                    i++
                }
                t.type == InkoTokens.KEYWORD && t.text == "fn" -> {
                    // Skip modifiers and the name (or operator) to the parameter list, if any.
                    var j = i + 1
                    while (at(j) != null && at(j)!!.type != InkoTokens.LPAREN && at(j)!!.type != InkoTokens.LBRACE &&
                        !(at(j)!!.type == InkoTokens.KEYWORD && at(j)!!.text == "fn")) j++
                    if (at(j)?.type == InkoTokens.LPAREN) {
                        val (found, after) = parameters(j)
                        pendingParameters = found
                        i = after
                    } else {
                        pendingParameters = emptyList()
                        i = j
                    }
                }
                t.type == InkoTokens.KEYWORD && t.text == "let" -> {
                    var j = i + 1
                    while (at(j)?.let { it.type == InkoTokens.KEYWORD && it.text == "mut" } == true) j++
                    val name = at(j)
                    // `let x = Foo.new` and `let x = Foo(…)` say what x is.
                    if (name?.type == InkoTokens.IDENTIFIER && isOperator(j + 1, "=") && at(j + 2)?.type == InkoTokens.CONSTANT &&
                        (at(j + 3)?.type == InkoTokens.LPAREN || (at(j + 3)?.type == InkoTokens.DOT && at(j + 4)?.text == "new"))) {
                        declare(InkoLocal(name.text, name.start, at(j + 2)!!.text))
                        i = j + 1
                    } else {
                        val bindings = mutableListOf<InkoLocal>()
                        i = maxOf(pattern(i + 1, bindings), i + 1)
                        bindings.forEach(::declare)
                    }
                }
                t.type == InkoTokens.KEYWORD && t.text == "for" -> {
                    // `for PATTERN in ITERATOR { … }`: the pattern's names belong to the loop's body.
                    val bindings = mutableListOf<InkoLocal>()
                    val after = pattern(i + 1, bindings)
                    if (isKeyword(after, "in")) bodyBrace(after + 1)?.let { loopBodies[it] = bindings }
                    i = maxOf(after, i + 1)
                }
                t.type == InkoTokens.KEYWORD && t.text == "match" -> {
                    bodyBrace(i + 1)?.let { matchBodies += it }
                    i++
                }
                // A match arm: its pattern's names belong to the arm (its guard and body). In a type's body,
                // `case` defines an enum constructor instead, which binds nothing (ast/src/parser.rs, define_constructor).
                t.type == InkoTokens.KEYWORD && t.text == "case" && matchBlocks.last() -> {
                    scopes.last().clear()
                    val bindings = mutableListOf<InkoLocal>()
                    i = maxOf(pattern(i + 1, bindings), i + 1)
                    bindings.forEach(::declare)
                }
                else -> i++
            }
        }
        return scopes.flatten().associateBy { it.name }.values.sortedBy { it.offset } to declared
    }

    /** Tokens before [end], without whitespace and comments. */
    private fun tokens(text: CharSequence, end: Int): List<Token> {
        val lexer = InkoLexer()
        lexer.start(text, 0, text.length, 0)
        return buildList {
            while (lexer.tokenType != null && lexer.tokenStart < end) {
                val type = lexer.tokenType!!
                if (type != InkoTokens.WHITE_SPACE && type != InkoTokens.COMMENT) add(Token(type, text.substring(lexer.tokenStart, lexer.tokenEnd), lexer.tokenStart))
                lexer.advance()
            }
        }
    }

    /** The type that `self` refers to at [offset]: the enclosing type, or the type an enclosing `impl` is for. */
    fun selfType(text: CharSequence, offset: Int): String? {
        val outline = InkoOutline.of(text)
        val node = outline.enclosing(offset).lastOrNull {
            it.kind == InkoOutline.Kind.TYPE || it.kind == InkoOutline.Kind.IMPL || it.kind == InkoOutline.Kind.TRAIT
        } ?: return null
        return if (node.kind == InkoOutline.Kind.IMPL) outline.implTarget(node) else node.label
    }
}
