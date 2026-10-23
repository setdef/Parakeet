package com.setdef.parakeet

import junit.framework.TestCase

/**
 * Where statements end, by Inko's lexer and parser: `-` before a digit is
 * part of the number (ast/src/lexer.rs, `minus`), `!` starts a new `Not`
 * expression (`not_expression`), and a bare `return` only takes a value on
 * its own line (`return_expression`). AUDIT.md finding 1.13.
 */
class InkoStatementBoundariesTest : TestCase() {
    private fun statements(body: String): List<String> {
        val text = "fn f(x: Int) -> Int {\n$body\n}\n"
        val stream = InkoTokenStream(text)
        val open = stream.tokens.indexOfFirst { it.type == InkoTokens.LBRACE }
        return stream.statementsIn(open).map { text.substring(stream.tokens[it.first].start, stream.tokens[it.last].end) }
    }

    fun testANegativeNumberStartsAStatement() =
        assertEquals(listOf("if x > 0 { return 1 }", "-1"), statements("  if x > 0 { return 1 }\n  -1"))

    fun testANotStartsAStatement() = assertEquals(listOf("foo", "!bar"), statements("  foo\n  !bar"))

    fun testABareReturnEndsItsLine() = assertEquals(listOf("return", "x"), statements("  return\n  x"))

    fun testBinaryOperatorsAndThrowStillContinue() {
        assertEquals(listOf("x\n    - 1"), statements("  x\n    - 1"))
        assertEquals(listOf("a\n    != b"), statements("  a\n    != b"))
        assertEquals(listOf("throw\n    x"), statements("  throw\n    x"))
    }

    fun testTheLexerReadsAMinusBeforeADigitAsPartOfTheNumber() {
        val tokens = InkoTokenStream("-1 - 2 x-3 -1.5").tokens.map { it.type.toString() to it.text }
        assertEquals(
            listOf("NUMBER" to "-1", "OPERATOR" to "-", "NUMBER" to "2", "IDENTIFIER" to "x", "NUMBER" to "-3", "NUMBER" to "-1.5"),
            tokens,
        )
    }
}
