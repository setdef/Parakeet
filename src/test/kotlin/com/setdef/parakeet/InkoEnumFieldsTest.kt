package com.setdef.parakeet

import junit.framework.TestCase

/**
 * `inko doc --private` lists an enum's compiler-internal fields (`@tag`, and
 * one per constructor argument). Enums can't define fields of their own
 * (docs/source/references/syntax.md, Enums), so those aren't real fields.
 * AUDIT.md section 3, compiler-internal enum fields.
 */
class InkoEnumFieldsTest : TestCase() {
    // From `inko doc --private` 0.21.1, for `type pub enum Shape { case Circle(Int) case Square(Int, Int) }`
    // and `type pub Box { let @tag: Int }`.
    private val json = """
        {"name": "shapes", "file": "/p/src/shapes.inko", "documentation": "", "constants": [], "methods": [], "traits": [],
         "types": [
           {"name": "Shape", "kind": 1, "location": {"lines": {"start": 1, "end": 4}, "columns": {"start": 1, "end": 1}},
            "constructors": [{"name": "Circle", "location": {"lines": {"start": 2, "end": 2}, "columns": {"start": 3, "end": 18}}},
                             {"name": "Square", "location": {"lines": {"start": 3, "end": 3}, "columns": {"start": 3, "end": 23}}}],
            "fields": [{"name": "tag", "type": "let @tag: Uint8", "location": {"lines": {"start": 1, "end": 1}, "columns": {"start": 1, "end": 1}}},
                       {"name": "1", "type": "let @1: <unknown>", "location": {"lines": {"start": 1, "end": 1}, "columns": {"start": 1, "end": 1}}}],
            "static_methods": [], "instance_methods": [], "implemented_traits": []},
           {"name": "Box", "kind": 0, "location": {"lines": {"start": 11, "end": 13}, "columns": {"start": 1, "end": 1}},
            "constructors": [],
            "fields": [{"name": "tag", "type": "let @tag: Int", "location": {"lines": {"start": 12, "end": 12}, "columns": {"start": 3, "end": 17}}}],
            "static_methods": [], "instance_methods": [], "implemented_traits": []}
         ]}
    """.trimIndent()

    fun testAnEnumsInternalFieldsAreNotFields() {
        val fields = InkoDocParser.parse(json).filter { it.kind == InkoDefinitionKind.FIELD }.map { "${it.owner}.${it.name}" }
        assertEquals(listOf("Box.tag"), fields)
    }

    fun testItsConstructorsAreStillThere() {
        assertEquals(listOf("Circle", "Square"), InkoDocParser.parse(json).filter { it.kind == InkoDefinitionKind.CONSTRUCTOR }.map { it.name })
    }
}
