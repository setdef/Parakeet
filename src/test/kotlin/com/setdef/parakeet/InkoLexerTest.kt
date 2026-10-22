package com.setdef.parakeet

import junit.framework.TestCase

class InkoLexerTest : TestCase() {
    private fun tokens(text: String): List<Pair<String, String>> {
        val lexer = InkoLexer()
        lexer.start(text, 0, text.length, 0)
        return buildList {
            while (lexer.tokenType != null) {
                if (lexer.tokenType != InkoTokens.WHITE_SPACE) add(lexer.tokenType.toString() to text.substring(lexer.tokenStart, lexer.tokenEnd))
                lexer.advance()
            }
        }
    }

    fun testNamesKeywordsAndFields() {
        assertEquals(
            listOf("KEYWORD" to "fn", "KEYWORD" to "pub", "IDENTIFIER" to "empty?", "OPERATOR" to "-", "OPERATOR" to ">",
                "CONSTANT" to "Bool", "{" to "{", "FIELD" to "@inner", "." to ".", "IDENTIFIER" to "size", "}" to "}"),
            tokens("fn pub empty? -> Bool { @inner.size }"),
        )
    }

    fun testStringsWithInterpolationAreOneToken() {
        assertEquals(
            listOf("KEYWORD" to "let", "IDENTIFIER" to "a", "OPERATOR" to "=", "STRING" to "\"x \${f(\"}\")} y\"", "NUMBER" to "1_000"),
            tokens("let a = \"x \${f(\"}\")} y\" 1_000"),
        )
    }

    fun testCommentsRunToEndOfLine() {
        assertEquals(listOf("COMMENT" to "# hi (there)", "IDENTIFIER" to "a"), tokens("# hi (there)\na"))
    }

    fun testQuestionMarkOperatorsAreNotPartOfNames() {
        assertEquals("IDENTIFIER" to "a", tokens("a ?? b").first())
    }
}
