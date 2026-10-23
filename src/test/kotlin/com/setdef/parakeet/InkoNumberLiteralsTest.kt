package com.setdef.parakeet

import junit.framework.TestCase

/**
 * Numbers as Inko's lexer reads them (ast/src/lexer.rs, `number` and, after a
 * `.`, `digits`). AUDIT.md finding 1.15.
 */
class InkoNumberLiteralsTest : TestCase() {
    private fun tokens(text: String) = InkoTokenStream(text).tokens.map { it.type.toString() to it.text }

    fun testNestedTupleAccessIsIntegers() = assertEquals(
        listOf("IDENTIFIER" to "pair", "." to ".", "NUMBER" to "0", "." to ".", "NUMBER" to "1", "OPERATOR" to "+", "NUMBER" to "2"),
        tokens("pair.0.1 + 2"),
    )

    fun testExponentsMayBeSigned() = assertEquals(
        listOf("NUMBER" to "10e+2", "." to ".", "IDENTIFIER" to "to_int", "NUMBER" to "1E-3", "NUMBER" to "2e5"),
        tokens("10e+2.to_int 1E-3 2e5"),
    )

    fun testOtherForms() = assertEquals(
        listOf("NUMBER" to "1.5", "NUMBER" to "0xFF_ff", "NUMBER" to "0b10_1", "NUMBER" to "1_000", "NUMBER" to "10", "IDENTIFIER" to "e"),
        tokens("1.5 0xFF_ff 0b10_1 1_000 10e"),
    )

    fun testAMemberAfterAnIntegerMemberIsNotAFloat() {
        val text = "fn f -> Int {\n  let pair = ((1, 2), 3)\n  pair.0.1\n}\n"
        assertEquals(listOf("0", "1"), InkoTokenStream(text).tokens.takeLast(4).filter { it.type == InkoTokens.NUMBER }.map { it.text })
    }
}
