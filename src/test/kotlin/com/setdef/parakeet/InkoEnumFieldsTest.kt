package com.setdef.parakeet

import junit.framework.TestCase
import java.nio.file.Path

/**
 * Enums can't define fields of their own (docs/source/references/syntax.md, Enums), so an enum has none, only
 * its constructors; a type's `@tag` is a field like any other. AUDIT.md section 3, compiler-internal enum fields.
 */
class InkoEnumFieldsTest : TestCase() {
    private val definitions = InkoSourceDefinitions.of(
        Path.of("/p/src/shapes.inko"), "shapes",
        "type pub enum Shape {\n  case Circle(Int)\n  case Square(Int, Int)\n}\n\ntype pub Box {\n  let @tag: Int\n}\n",
    )

    fun testAnEnumHasNoFields() {
        assertEquals(listOf("Box.tag"), definitions.filter { it.kind == InkoDefinitionKind.FIELD }.map { "${it.owner}.${it.name}" })
    }

    fun testItsConstructorsAreThere() {
        assertEquals(listOf("Circle", "Square"), definitions.filter { it.kind == InkoDefinitionKind.CONSTRUCTOR }.map { it.name })
    }
}
