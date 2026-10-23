package com.setdef.parakeet

import junit.framework.TestCase

class InkoLocalsTest : TestCase() {
    private fun visible(text: String, marker: String = "<here>"): Map<String, String?> {
        val offset = text.indexOf(marker)
        return InkoLocals.visibleAt(text.replace(marker, ""), offset).associate { it.name to it.type }
    }

    fun testParametersAndLets() {
        val text = """
            |fn pub greet(name: String, mut count: ref Int, other) -> String {
            |  let a = 1
            |  let mut b: uni Array[Int] = []
            |  let h = Helper.new
            |  let p = Point(x: 1, y: 2)
            |  <here>
            |}
            |""".trimMargin()
        assertEquals(
            mapOf("name" to "String", "count" to "Int", "other" to null, "a" to null, "b" to "Array", "h" to "Helper", "p" to "Point"),
            visible(text),
        )
    }

    fun testBlocksEndTheirLocals() {
        val text = "fn a {\n  if x {\n    let inner = 1\n  }\n  let outer = 2\n  <here>\n}\n\nfn b {\n  let other = 3\n}\n"
        assertEquals(setOf("outer"), visible(text).keys)
    }

    fun testOnlyDeclarationsBeforeTheCaret() {
        assertEquals(setOf("a"), visible("fn f {\n  let a = 1\n  <here>\n  let b = 2\n}\n").keys)
    }

    fun testClosureParametersBelongToTheClosure() {
        val text = "fn f {\n  [1].iter.each(fn (n) {\n    <here>\n  })\n  n\n}\n"
        assertEquals(setOf("n"), visible(text).keys)
        assertEquals(emptySet<String>(), visible(text.replace("<here>", "").replace("\n  n\n", "\n  <here>\n")).keys)
    }

    fun testCaseBindingsAndTuples() {
        val text = "fn f {\n  let (left, right) = pair\n  match x {\n    case Some(value) -> <here>\n    case _ -> 0\n  }\n}\n"
        assertEquals(setOf("left", "right", "value"), visible(text).keys)
    }

    fun testLaterDeclarationsShadowEarlierOnes() {
        assertEquals(mapOf("x" to "Text"), visible("fn f(x: Int) {\n  let x: Text = 'a'\n  <here>\n}\n"))
    }

    fun testSelfIsTheEnclosingTypeOrImplTarget() {
        val text = "type Helper {\n  fn a {\n    self\n  }\n}\n\nimpl ToString for Point[T] {\n  fn to_string -> String {\n    self\n  }\n}\n"
        assertEquals("Helper", InkoLocals.selfType(text, text.indexOf("self")))
        assertEquals("Point", InkoLocals.selfType(text, text.lastIndexOf("self")))
    }
}
