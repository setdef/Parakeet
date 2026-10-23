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

    // A `}` in a string nested in an interpolation doesn't end the interpolation (AUDIT.md finding 1.1: the code is tokens).
    fun testStringsWithInterpolationSplitAroundTheirCode() {
        assertEquals(
            listOf(
                "KEYWORD" to "let", "IDENTIFIER" to "a", "OPERATOR" to "=", "STRING_START" to "\"x \${", "IDENTIFIER" to "f",
                "(" to "(", "STRING" to "\"}\"", ")" to ")", "STRING_END" to "} y\"", "NUMBER" to "1_000",
            ),
            tokens("let a = \"x \${f(\"}\")} y\" 1_000"),
        )
    }

    fun testCommentsRunToEndOfLine() {
        assertEquals(listOf("COMMENT" to "# hi (there)", "IDENTIFIER" to "a"), tokens("# hi (there)\na"))
    }

    fun testQuestionMarkOperatorsAreNotPartOfNames() {
        assertEquals("IDENTIFIER" to "a", tokens("a ?? b").first())
    }

    // From Inko's syntax reference: identifiers may contain `$`, and setters end in `=`.
    fun testNamesMayContainDollarSigns() {
        assertEquals("IDENTIFIER" to "readdir\$INODE64", tokens("readdir\$INODE64(dir)").first())
    }

    fun testSetterNamesEndInEquals() {
        assertEquals(listOf("KEYWORD" to "fn", "KEYWORD" to "pub", "IDENTIFIER" to "value=", "{" to "{", "}" to "}"), tokens("fn pub value= {}"))
        assertEquals(listOf("IDENTIFIER" to "a", "OPERATOR" to "=", "NUMBER" to "1"), tokens("a=1"))
        assertEquals(listOf("IDENTIFIER" to "a", "OPERATOR" to "=", "OPERATOR" to "=", "NUMBER" to "1"), tokens("a==1"))
    }
}
