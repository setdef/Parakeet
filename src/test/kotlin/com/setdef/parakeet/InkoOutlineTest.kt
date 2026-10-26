package com.setdef.parakeet

import junit.framework.TestCase

class InkoOutlineTest : TestCase() {
    private val text = """
        |import std.fmt (Format)
        |
        |let pub LIMIT = 10
        |
        |# A helper.
        |type pub Helper {
        |  let @count: Int
        |
        |  fn pub static new -> Helper {
        |    let local = fn (x) { x }
        |    Helper(count: 0)
        |  }
        |
        |  fn pub value -> Int {
        |    @count
        |  }
        |}
        |
        |trait pub Show {
        |  fn pub show -> String
        |
        |  fn pub ==(other: ref Self) -> Bool {
        |    true
        |  }
        |}
        |
        |impl Show for Helper {
        |  fn pub show -> String {
        |    'x'
        |  }
        |}
        |
        |type async Main {
        |  fn async main {}
        |}
        |""".trimMargin()

    private val outline = InkoOutline.of(text)

    private fun InkoOutline.Node.describe(): String =
        "${kind.name.lowercase()} $label" + if (children.isEmpty()) "" else children.joinToString(", ", " [", "]") { it.describe() }

    fun testFindsDeclarationsAndNesting() {
        assertEquals(
            listOf(
                "constant LIMIT",
                "type Helper [field @count, method new, method value]",
                "trait Show [method show, method ==]",
                "impl impl Show for Helper [method show]",
                "type Main [method main]",
            ),
            outline.roots.map { it.describe() },
        )
    }

    fun testRangesCoverBodies() {
        val helper = outline.roots.single { it.label == "Helper" }
        assertEquals(text.indexOf("type pub Helper"), helper.range.startOffset)
        assertEquals(text.indexOf("}\n\ntrait") + 1, helper.range.endOffset)
        assertEquals(text.indexOf("Helper {"), helper.nameOffset)
    }

    // inko-wobsite's `fn async page(…, builder: uni fn (ref Files, Page) -> Result[…])`: a closure type in the
    // header isn't a declaration, so the method still has its body, and a `case` in its `match` stays a pattern.
    fun testAClosureTypeInAMethodsHeaderKeepsItsBody() {
        val text = """
            |type async Generator {
            |  fn async page(builder: uni fn (Int) -> Result[Int, String], other: fn) -> fn -> Int {
            |    match builder.call(1) {
            |      case Ok(v) -> v
            |      case Error(e) -> 0
            |    }
            |  }
            |}
            |""".trimMargin()
        val page = InkoOutline.of(text).all.single { it.label == "page" }
        assertEquals(text.lastIndexOf("  }") + 3, page.range.endOffset)
        assertFalse(text.indexOf("Error") in InkoDeclarations.offsets(text))
        assertFalse(text.indexOf("Ok") in InkoDeclarations.offsets(text))
    }

    fun testEnclosingDeclarations() {
        assertEquals(listOf("Helper", "value"), outline.enclosing(text.indexOf("@count\n  }")).map { it.label })
        assertEquals(listOf("impl Show for Helper", "show"), outline.enclosing(text.indexOf("'x'")).map { it.label })
        assertEquals(emptyList<String>(), outline.enclosing(text.indexOf("import")).map { it.label })
    }

    fun testIncompleteCodeDoesNotThrow() {
        listOf("type", "fn", "type pub Foo {", "impl", "let", "}}}", "fn pub ==").forEach { InkoOutline.of(it) }
    }
}
