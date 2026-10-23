package com.setdef.parakeet

import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.openapi.util.TextRange

class InkoExtractMethodTest : InkoTestCase() {
    /** Extracts what's between `<selection>` and `</selection>` in [text], returning the result. */
    private fun extract(text: String): String {
        val start = text.indexOf("<selection>")
        val clean = text.replace("<selection>", "").replace("</selection>", "")
        val end = text.indexOf("</selection>") - "<selection>".length
        myFixture.configureByText("main.inko", clean)
        val plan = InkoExtractMethod.plan(clean, TextRange(start, end)).getOrThrow()
        InkoExtractMethod.apply(project, myFixture.editor, plan)
        return myFixture.editor.document.text
    }

    private fun refusal(text: String): String? {
        val start = text.indexOf("<selection>")
        val clean = text.replace("<selection>", "").replace("</selection>", "")
        val end = text.indexOf("</selection>") - "<selection>".length
        return InkoExtractMethod.plan(clean, TextRange(start, end)).exceptionOrNull()?.message
    }

    fun testStatementsWithParametersAndAResult() {
        val result = extract(
            "fn total(items: Array[Int]) -> Int {\n  let mut sum = 0\n  <selection>let doubled = items.size * 2\n  let tripled = doubled + 1</selection>\n  tripled + sum\n}\n",
        )
        assertEquals(
            "fn total(items: Array[Int]) -> Int {\n  let mut sum = 0\n  let tripled = extracted(items)\n  tripled + sum\n}\n\n" +
                "fn extracted(items: mut Array[Int]) -> Type {\n  let doubled = items.size * 2\n  let tripled = doubled + 1\n  tripled\n}\n",
            result,
        )
    }

    fun testAnExpression() {
        val result = extract("fn total(items: Array[Int], extra: Int) -> Int {\n  <selection>items.size * extra</selection> + 1\n}\n")
        assertEquals(
            "fn total(items: Array[Int], extra: Int) -> Int {\n  extracted(items, extra) + 1\n}\n\n" +
                "fn extracted(items: mut Array[Int], extra: Int) -> Type {\n  items.size * extra\n}\n",
            result,
        )
    }

    fun testAnInstanceMethodStaysInItsTypeAndKeepsMut() {
        val result = extract(
            "type Counter {\n  let mut @count: Int\n\n  fn mut bump(by: Int) {\n    <selection>@count = @count + by\n    @count = @count * 2</selection>\n  }\n}\n",
        )
        assertEquals(
            "type Counter {\n  let mut @count: Int\n\n  fn mut bump(by: Int) {\n    extracted(by)\n  }\n\n" +
                "  fn mut extracted(by: Int) {\n    @count = @count + by\n    @count = @count * 2\n  }\n}\n",
            result,
        )
    }

    fun testAStaticMethodMakesAStaticMethod() {
        val result = extract("type Maths {\n  fn static twice(value: Int) -> Int {\n    <selection>value * 2</selection>\n  }\n}\n")
        assertEquals(
            "type Maths {\n  fn static twice(value: Int) -> Int {\n    extracted(value)\n  }\n\n  fn static extracted(value: Int) -> Int {\n    value * 2\n  }\n}\n",
            result,
        )
    }

    fun testTheMethodsResultKeepsItsReturnType() {
        val result = extract("fn area(width: Int, height: Int) -> Int {\n  <selection>let w = width * 2\n  w * height</selection>\n}\n")
        assertEquals(
            "fn area(width: Int, height: Int) -> Int {\n  extracted(width, height)\n}\n\n" +
                "fn extracted(width: Int, height: Int) -> Int {\n  let w = width * 2\n  w * height\n}\n",
            result,
        )
    }

    fun testAReassignedVariableIsPassedAndReturned() {
        val result = extract("fn f -> Int {\n  let mut x: Int = 1\n  <selection>x = x + 1\n  x = x * 3</selection>\n  x\n}\n")
        assertEquals(
            "fn f -> Int {\n  let mut x: Int = 1\n  x = extracted(x)\n  x\n}\n\n" +
                "fn extracted(x: Int) -> Int {\n  let mut x = x\n  x = x + 1\n  x = x * 3\n  x\n}\n",
            result,
        )
    }

    fun testSeveralResultsComeBackAsATuple() {
        val result = extract("fn f -> Int {\n  <selection>let a: Int = 1\n  let mut b = 2</selection>\n  b = b + 1\n  a + b\n}\n")
        assertEquals(
            "fn f -> Int {\n  let (a, mut b) = extracted\n  b = b + 1\n  a + b\n}\n\n" +
                "fn extracted -> (Int, Type) {\n  let a: Int = 1\n  let mut b = 2\n  (a, b)\n}\n",
            result,
        )
    }

    fun testRefusesWhatCantBeExtracted() {
        assertTrue(refusal("fn f(x: Int) -> Int {\n  <selection>return x</selection>\n}\n")!!.contains("`return`"))
        assertTrue(refusal("fn f(x: Int) {\n  loop {\n    <selection>break</selection>\n  }\n}\n")!!.contains("`break`"))
        assertTrue(refusal("fn f(x: Int) -> Int {\n  <selection>try g(x)</selection>\n}\n")!!.contains("`try`"))
        assertTrue(refusal("fn f -> Int {\n  let mut x = 1\n  <selection>x = 2\n  let y = 3</selection>\n  x + y\n}\n")!!.contains("can't assign several"))
        assertTrue(refusal("let <selection>A</selection> = 1\n")!!.contains("inside a method"))
        assertTrue(refusal("fn f(x: Int) -> Int {\n  match x {\n    <selection>case 1 -> 2</selection>\n    case _ -> 3\n  }\n}\n")!!.contains("Match arms"))
        assertTrue(refusal("fn f(x: Int) -> Int {\n  <selection>x +</selection> 1\n}\n")!!.contains("whole"))
    }

    // Returning from a closure, or breaking out of a loop, that the selection contains is fine.
    fun testAllowsControlFlowThatStaysInside() {
        assertNull(refusal("fn f(items: Array[Int]) {\n  <selection>items.each(fn (i) { return })\n  loop { break }</selection>\n}\n"))
    }

    fun testTheExtractMethodActionNamesItInPlace() {
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
        myFixture.configureByText("main.inko", "fn twice(value: Int) -> Int {\n  <selection>value * 2</selection>\n}\n")
        myFixture.performEditorAction("ExtractMethod")
        val state = TemplateManagerImpl.getTemplateState(myFixture.editor)!!
        // Typing the name changes the call and the definition together.
        myFixture.type("doubled")
        state.gotoEnd(false)
        assertEquals("fn twice(value: Int) -> Int {\n  doubled(value)\n}\n\nfn doubled(value: Int) -> Int {\n  value * 2\n}\n", myFixture.editor.document.text)
    }
}
