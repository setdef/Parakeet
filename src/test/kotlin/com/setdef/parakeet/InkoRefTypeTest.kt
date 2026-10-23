package com.setdef.parakeet

import junit.framework.TestCase
import java.nio.file.Path

/**
 * `type pub ref X`, and every other modifier Inko's `define_type` allows:
 * `type [pub] [inline|copy|ref] [async|enum|builtin|extern] Name`
 * (ast/src/parser.rs). AUDIT.md finding 1.7.
 */
class InkoRefTypeTest : TestCase() {
    private val shared = "type pub ref Shared {\n  let @count: Int\n\n  fn pub count -> Int {\n    @count\n  }\n}\n"

    fun testARefTypeIsATypeWithItsMembers() {
        val outline = InkoOutline.of(shared)
        assertEquals(listOf("Shared"), outline.roots.map { it.label })
        assertEquals(InkoOutline.Kind.TYPE, outline.roots.single().kind)
        assertEquals(listOf("@count", "count"), outline.roots.single().children.map { it.label })
    }

    fun testItsMethodsAreNotModuleMethods() {
        val count = InkoSourceDefinitions.of(Path.of("/p/src/a.inko"), "a", shared).single { it.name == "count" && it.kind != InkoDefinitionKind.FIELD }
        assertEquals(InkoDefinitionKind.INSTANCE_METHOD, count.kind)
        assertEquals("Shared", count.owner)
        assertEquals("Shared", InkoLocals.selfType(shared, shared.indexOf("@count\n  }")))
    }

    fun testEveryTypeModifierCombination() {
        for (header in listOf("type ref A", "type pub inline enum A", "type pub copy enum A", "type pub ref async A", "type builtin A", "type pub extern A")) {
            assertEquals(header, listOf("A"), InkoOutline.of("$header {}\n").roots.map { it.label })
        }
    }
}
