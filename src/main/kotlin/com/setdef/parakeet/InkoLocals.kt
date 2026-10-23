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

    /** Locals visible at [offset]: declared before it, in blocks still open there. Later declarations shadow earlier ones. */
    fun visibleAt(text: CharSequence, offset: Int): List<InkoLocal> {
        val tokens = tokens(text, offset)
        val scopes = ArrayDeque<MutableList<InkoLocal>>().apply { addLast(mutableListOf()) }
        var pendingParameters: List<InkoLocal>? = null
        var i = 0

        fun at(index: Int) = tokens.getOrNull(index)
        fun isOperator(index: Int, text: String) = at(index)?.let { it.type == InkoTokens.OPERATOR && it.text == text } == true

        /** The type named after a `:` at [colon], skipping `ref`/`mut`/`uni`/`move`. */
        fun annotatedType(colon: Int): String? {
            var j = colon + 1
            while (at(j)?.let { it.type == InkoTokens.KEYWORD && it.text in TYPE_MODIFIERS } == true) j++
            return at(j)?.takeIf { it.type == InkoTokens.CONSTANT }?.text
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

        while (i < tokens.size) {
            val t = tokens[i]
            when {
                t.type == InkoTokens.LBRACE -> {
                    scopes.addLast(pendingParameters.orEmpty().toMutableList())
                    pendingParameters = null
                    i++
                }
                t.type == InkoTokens.RBRACE -> {
                    if (scopes.size > 1) scopes.removeLast()
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
                    when (name?.type) {
                        InkoTokens.IDENTIFIER -> {
                            val type = when {
                                isOperator(j + 1, ":") -> annotatedType(j + 1)
                                isOperator(j + 1, "=") && at(j + 2)?.type == InkoTokens.CONSTANT &&
                                    (at(j + 3)?.type == InkoTokens.LPAREN || (at(j + 3)?.type == InkoTokens.DOT && at(j + 4)?.text == "new")) -> at(j + 2)!!.text
                                else -> null
                            }
                            scopes.last() += InkoLocal(name.text, name.start, type)
                        }
                        InkoTokens.LPAREN -> scopes.last() += parameters(j).first.map { it.copy(type = null) }
                        else -> {}
                    }
                    i = j + 1
                }
                t.type == InkoTokens.KEYWORD && t.text == "case" -> {
                    // Bindings in the pattern, up to `->` or an `if` guard.
                    var j = i + 1
                    while (at(j) != null && !(isOperator(j, "-") && isOperator(j + 1, ">")) &&
                        !(at(j)!!.type == InkoTokens.KEYWORD && at(j)!!.text == "if")) {
                        val token = at(j)!!
                        val afterDot = at(j - 1)?.type == InkoTokens.DOT
                        if (token.type == InkoTokens.IDENTIFIER && token.text != "_" && !afterDot) scopes.last() += InkoLocal(token.text, token.start, null)
                        j++
                    }
                    i = j
                }
                else -> i++
            }
        }
        return scopes.flatten().associateBy { it.name }.values.sortedBy { it.offset }
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
