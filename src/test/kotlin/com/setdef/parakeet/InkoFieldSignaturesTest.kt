package com.setdef.parakeet

import junit.framework.TestCase
import java.nio.file.Path

/**
 * `inko doc` writes a field as `let pub mut @count: Int` (compiler/src/docs.rs,
 * the `fields` of a type). AUDIT.md section 3, field signatures with `mut`.
 */
class InkoFieldSignaturesTest : TestCase() {
    private fun field(signature: String) =
        InkoDefinition(InkoDefinitionKind.FIELD, "count", "m", "Counter", Path.of("/m.inko"), 2, 3, signature)

    fun testATypesParametersAreItsFieldsWithoutTheirKeywords() {
        val counter = InkoDefinition(InkoDefinitionKind.TYPE, "Counter", "m", null, Path.of("/m.inko"), 1, 1, "type pub Counter")
        for (signature in listOf("let pub mut @count: Int", "let mut @count: Int", "let pub @count: Int", "let @count: Int")) {
            assertEquals(signature, listOf("count: Int"), InkoCalls.parameters(counter, InkoSymbols(listOf(counter, field(signature)))))
        }
    }
}
