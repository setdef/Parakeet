package com.setdef.parakeet

/** Expression Type (⌃⇧P), with `inko doc` output supplied by a fake. */
class InkoExpressionTypeTest : InkoTestCase(onDisk = true) {
    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("inko.pkg", "")
        myFixture.addFileToProject("src/app/util.inko", "type pub Helper {\n  let @count: Int\n\n  fn pub static new -> Helper {\n    Helper(count: 0)\n  }\n\n  fn pub value -> Int {\n    @count\n  }\n\n  fn pub twin -> Self {\n    self\n  }\n\n  fn pub first[T](items: Array[T]) -> T {\n    items.get(0)\n  }\n}\n\nfn pub helper -> String {\n  'x'\n}\n")
        useFakeInko(FakeInkoDoc.cli { mirror ->
            val util = mirror.resolve("src/app/util.inko")
            listOf(
                FakeInkoDoc.module(
                    "app.util", util,
                    methods = listOf(FakeInkoDoc.member("helper", util, 22, 1, signature = "fn pub helper -> String")),
                    types = listOf(FakeInkoDoc.type(
                        "Helper", 1,
                        fields = listOf(FakeInkoDoc.member("count", util, 2, signature = "let @count: Int")),
                        staticMethods = listOf(FakeInkoDoc.member("new", util, 4, signature = "fn pub static new -> Helper")),
                        instanceMethods = listOf(
                            FakeInkoDoc.member("value", util, 8, signature = "fn pub value -> Int"),
                            FakeInkoDoc.member("twin", util, 12, signature = "fn pub twin -> Self"),
                            FakeInkoDoc.member("first", util, 16, signature = "fn pub first[T](items: Array[T]) -> T"),
                        ),
                    )),
                ),
                FakeInkoDoc.module("std.string", mirror.resolve("std/string.inko"), types = listOf(FakeInkoDoc.type("String", 1))),
                FakeInkoDoc.module("std.int", mirror.resolve("std/int.inko"), types = listOf(FakeInkoDoc.type("Int", 1))),
            )
        })
    }

    /** The types ⌃⇧P offers at `<caret>`, innermost expression first, as "expression: type". */
    private fun typesAt(text: String): List<String> {
        val file = myFixture.addFileToProject("src/main.inko", text.replace("<caret>", ""))
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val provider = InkoExpressionTypeProvider()
        val element = myFixture.file.findElementAt(text.indexOf("<caret>"))!!
        return provider.getExpressionsAt(element).map { "${it.text}: ${provider.getInformationHint(it)}" }
    }

    private fun typeAt(text: String): String = typesAt(text).first().substringAfter(": ")

    private fun body(code: String) = "import app.util (Helper, helper)\n\nfn main {\n$code\n}\n"

    fun testLiterals() {
        assertEquals("Int", typeAt(body("  <caret>42")))
        assertEquals("Float", typeAt(body("  <caret>4.2")))
        assertEquals("String", typeAt(body("  <caret>'hi'")))
        assertEquals("Bool", typeAt(body("  <caret>true")))
    }

    fun testLocalsByAnnotationAndByValue() {
        assertEquals("Array[Int]", typeAt(body("  let xs: Array[Int] = []\n  <caret>xs")))
        assertEquals("Int", typeAt(body("  let n = 1 + 2\n  <caret>n")))
        assertEquals("Helper", typeAt(body("  let h = Helper.new\n  <caret>h")))
    }

    fun testCallsUseTheirSignaturesReturnType() {
        assertEquals("String", typeAt(body("  <caret>helper")))
        assertEquals(listOf("h: Helper", "h.value: Int"), typesAt(body("  let h = Helper.new\n  <caret>h.value")))
    }

    fun testSelfInAReturnTypeIsTheOwner() {
        assertEquals("Helper", typesAt(body("  let h = Helper.new\n  <caret>h.twin")).last().substringAfter(": "))
    }

    fun testTypeParametersAreUnknownRatherThanGuessed() {
        assertTrue(typesAt(body("  let h = Helper.new\n  <caret>h.first([1])")).last().contains("Unknown"))
    }

    fun testMethodsOnAnUnknownReceiverAreUnknown() {
        assertTrue(typesAt("import app.util (Helper)\n\nfn main(thing: Thing) {\n  <caret>thing.value\n}\n").last().contains("Unknown"))
    }

    fun testComparisonsAndCasts() {
        assertEquals("Bool", typeAt(body("  let a = 1\n  let same = a == 2\n  <caret>same")))
        assertEquals("Bool", typeAt(body("  let a = 1\n  let both = a > 1 and a < 3\n  <caret>both")))
        assertEquals("Float", typeAt(body("  let a = 1\n  let f = a as Float\n  <caret>f")))
    }

    fun testArithmeticKeepsTheNumbersType() {
        assertEquals("Float", typeAt(body("  let x = 1.5 * 2.0\n  <caret>x")))
    }

    fun testBorrowedAndGenericReceivers() {
        assertEquals(
            "Int",
            typesAt("import app.util (Helper)\n\nfn main(h: ref Helper) {\n  <caret>h.value\n}\n").last().substringAfter(": "),
        )
    }
}
