package com.setdef.parakeet

import com.intellij.openapi.util.TextRange

/**
 * The declarations in an Inko file (types, traits, impls, methods, fields,
 * constants), found from its tokens, so it follows unsaved edits without the
 * compiler. Used by the Structure view, breadcrumbs, and sticky lines.
 */
class InkoOutline private constructor(val roots: List<Node>) {
    enum class Kind { TYPE, TRAIT, IMPL, METHOD, FIELD, CONSTANT }

    class Node(val kind: Kind, val label: String, val nameOffset: Int, start: Int) {
        /** From the declaration's first keyword to its closing brace (or its name, without a body). */
        var range: TextRange = TextRange(start, nameOffset)
            internal set
        val children: MutableList<Node> = mutableListOf()
    }

    /** Every node, depth first. */
    val all: List<Node> get() = roots.flatMap { it.withDescendants() }

    /** The innermost declarations containing [offset], outermost first. */
    fun enclosing(offset: Int): List<Node> {
        val path = mutableListOf<Node>()
        var level = roots
        while (true) {
            val node = level.firstOrNull { it.range.containsOffset(offset) } ?: return path
            path += node
            level = node.children
        }
    }

    companion object {
        private val MODIFIERS = setOf("pub", "async", "inline", "enum", "builtin", "copy", "extern", "static", "move", "mut")

        private class Token(val type: com.intellij.psi.tree.IElementType, val text: String, val start: Int, val end: Int)

        fun of(text: CharSequence): InkoOutline {
            val tokens = tokens(text)
            val roots = mutableListOf<Node>()
            // Open declarations with a body, and the brace depth inside each.
            val open = ArrayDeque<Pair<Node, Int>>()
            var depth = 0
            var i = 0

            fun add(node: Node) = (open.lastOrNull()?.first?.children ?: roots).add(node)

            // Skip past a declaration's header to its opening brace, if it has a body before the next declaration.
            fun bodyStart(from: Int): Int? {
                var j = from
                while (j < tokens.size) {
                    val t = tokens[j]
                    if (t.type == InkoTokens.LBRACE) return j
                    if (t.type == InkoTokens.RBRACE || (t.type == InkoTokens.KEYWORD && t.text in setOf("fn", "let", "type", "trait", "impl", "import"))) return null
                    j++
                }
                return null
            }

            fun open(node: Node, from: Int): Int {
                add(node)
                val brace = bodyStart(from) ?: return from
                depth++
                open.addLast(node to depth)
                return brace + 1
            }

            while (i < tokens.size) {
                val token = tokens[i]
                val inFunctionBody = open.lastOrNull()?.first?.kind == Kind.METHOD
                when {
                    token.type == InkoTokens.LBRACE -> { depth++; i++ }
                    token.type == InkoTokens.RBRACE -> {
                        val top = open.lastOrNull()
                        if (top != null && top.second == depth) {
                            top.first.range = TextRange(top.first.range.startOffset, token.end)
                            open.removeLast()
                        }
                        depth--
                        i++
                    }
                    inFunctionBody || token.type != InkoTokens.KEYWORD -> i++
                    token.text == "type" || token.text == "trait" -> {
                        var j = i + 1
                        while (j < tokens.size && tokens[j].type == InkoTokens.KEYWORD && tokens[j].text in MODIFIERS) j++
                        val name = tokens.getOrNull(j)?.takeIf { it.type == InkoTokens.CONSTANT }
                        i = if (name == null) j else open(Node(if (token.text == "type") Kind.TYPE else Kind.TRAIT, name.text, name.start, token.start), j + 1)
                    }
                    token.text == "impl" -> {
                        val brace = bodyStart(i + 1)
                        val header = tokens.subList(i + 1, brace ?: (i + 1)).joinToString(" ") { it.text }
                            .replace(" ,", ",").replace("[ ", "[").replace(" ]", "]")
                        val name = tokens.subList(i + 1, brace ?: (i + 1)).firstOrNull { it.type == InkoTokens.CONSTANT }
                        i = if (name == null) i + 1 else open(Node(Kind.IMPL, "impl $header", name.start, token.start), i + 1)
                    }
                    token.text == "fn" -> {
                        var j = i + 1
                        while (j < tokens.size && tokens[j].type == InkoTokens.KEYWORD && tokens[j].text in MODIFIERS) j++
                        val first = tokens.getOrNull(j)
                        // A name, or an operator method like `==`; `fn (` is a closure.
                        val nameTokens = when (first?.type) {
                            InkoTokens.IDENTIFIER, InkoTokens.CONSTANT -> listOf(first)
                            InkoTokens.OPERATOR -> tokens.drop(j).takeWhile { it.type == InkoTokens.OPERATOR }
                            else -> emptyList()
                        }
                        i = if (nameTokens.isEmpty()) {
                            j
                        } else {
                            open(Node(Kind.METHOD, nameTokens.joinToString("") { it.text }, nameTokens.first().start, token.start), j + nameTokens.size)
                        }
                    }
                    token.text == "let" -> {
                        var j = i + 1
                        while (j < tokens.size && tokens[j].type == InkoTokens.KEYWORD && tokens[j].text in MODIFIERS) j++
                        val name = tokens.getOrNull(j)
                        val inType = open.lastOrNull()?.first?.kind == Kind.TYPE
                        when {
                            inType && name?.type == InkoTokens.FIELD -> add(Node(Kind.FIELD, name.text, name.start, token.start))
                            open.isEmpty() && name?.type == InkoTokens.CONSTANT -> add(Node(Kind.CONSTANT, name.text, name.start, token.start))
                        }
                        i = j + 1
                    }
                    else -> i++
                }
            }
            return InkoOutline(roots)
        }

        private fun tokens(text: CharSequence): List<Token> {
            val lexer = InkoLexer()
            lexer.start(text, 0, text.length, 0)
            return buildList {
                while (lexer.tokenType != null) {
                    val type = lexer.tokenType!!
                    if (type != InkoTokens.WHITE_SPACE && type != InkoTokens.COMMENT) {
                        add(Token(type, text.substring(lexer.tokenStart, lexer.tokenEnd), lexer.tokenStart, lexer.tokenEnd))
                    }
                    lexer.advance()
                }
            }
        }

        private fun Node.withDescendants(): List<Node> = listOf(this) + children.flatMap { it.withDescendants() }
    }
}
