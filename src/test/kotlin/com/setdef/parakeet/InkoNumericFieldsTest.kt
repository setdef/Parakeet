package com.setdef.parakeet

import junit.framework.TestCase

/**
 * Field names may be digits (`let @0: A` in std/src/std/tuple.inko): Inko's
 * field lexer takes any identifier bytes after `@` (ast/src/lexer.rs, `field`).
 * AUDIT.md section 3, numeric field names.
 */
class InkoNumericFieldsTest : TestCase() {
    private val tuple = "type pub inline Tuple2[A, B] {\n  let pub @0: A\n  let pub @1: B\n\n  fn first -> ref A {\n    @0\n  }\n}\n"

    fun testNumericFieldsAreFields() {
        val fields = InkoTokenStream(tuple).tokens.filter { it.type == InkoTokens.FIELD }.map { it.text }
        assertEquals(listOf("@0", "@1", "@0"), fields)
    }

    fun testTheOutlineHasThem() {
        assertEquals(listOf("@0", "@1", "first"), InkoOutline.of(tuple).roots.single().children.map { it.label })
    }

    fun testTheNamingInspectionAcceptsThem() {
        assertEquals("0", InkoNames.snake("0"))
    }
}
