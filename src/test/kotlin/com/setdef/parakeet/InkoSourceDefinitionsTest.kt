package com.setdef.parakeet

import junit.framework.TestCase
import java.nio.file.Path
import com.setdef.parakeet.InkoDefinitionKind as Kind

class InkoSourceDefinitionsTest : TestCase() {
    private val file = Path.of("/project/src/app/util.inko")
    private val text = """
        |import std.string (ToString)
        |
        |let pub LIMIT = 10
        |
        |# Helps.
        |# With **everything**.
        |type pub Helper {
        |  let @count: Int
        |
        |  fn pub static new -> Helper {
        |    Helper(count: 0)
        |  }
        |
        |  fn pub value -> Int {
        |    match @count {
        |      case 0 -> 1
        |      case _ -> 2
        |    }
        |  }
        |}
        |
        |type pub enum Shape {
        |  case Circle(Float)
        |  case Square(Float)
        |}
        |
        |trait pub Show {
        |  fn pub show -> String
        |}
        |
        |impl ToString for Helper {
        |  fn pub to_string -> String {
        |    'helper'
        |  }
        |}
        |
        |fn pub helper -> Int {
        |  42
        |}
        |""".trimMargin()

    private val definitions = InkoSourceDefinitions.of(file, "app.util", text)

    private fun find(kind: Kind, name: String) = definitions.single { it.kind == kind && it.name == name }

    fun testFindsEveryKindOfDefinition() {
        assertEquals(
            listOf(
                Kind.MODULE to "app.util", Kind.CONSTANT to "LIMIT", Kind.TYPE to "Helper", Kind.FIELD to "count",
                Kind.STATIC_METHOD to "new", Kind.INSTANCE_METHOD to "value", Kind.TYPE to "Shape", Kind.CONSTRUCTOR to "Circle",
                Kind.CONSTRUCTOR to "Square", Kind.TRAIT to "Show", Kind.INSTANCE_METHOD to "show",
                Kind.INSTANCE_METHOD to "to_string", Kind.MODULE_METHOD to "helper",
            ),
            definitions.map { it.kind to it.name },
        )
    }

    fun testMembersBelongToTheirTypeOrTrait() {
        assertEquals("Helper", find(Kind.FIELD, "count").owner)
        assertEquals("Shape", find(Kind.CONSTRUCTOR, "Circle").owner)
        assertEquals("Show", find(Kind.INSTANCE_METHOD, "show").owner)
        assertEquals("Helper", find(Kind.INSTANCE_METHOD, "to_string").owner) // From `impl ToString for Helper`.
    }

    fun testLocationsSignaturesAndDocs() {
        val helper = find(Kind.TYPE, "Helper")
        assertEquals(7 to 10, helper.line to helper.column)
        assertEquals("type pub Helper", helper.signature)
        assertEquals("Helps.\nWith **everything**.", helper.documentation)
        assertEquals("fn pub value -> Int", find(Kind.INSTANCE_METHOD, "value").signature)
        assertEquals("app.util", find(Kind.MODULE_METHOD, "helper").module)
        assertEquals(file, find(Kind.MODULE_METHOD, "helper").file)
    }

    fun testMatchCasesAreNotConstructors() {
        assertTrue(definitions.none { it.kind == Kind.CONSTRUCTOR && it.owner == "Helper" })
    }
}
