package com.setdef.parakeet

import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.openapi.util.TextRange

class InkoIntroduceVariableTest : InkoTestCase() {
    private fun stream(text: String) = InkoTokenStream(text)

    private fun texts(text: String, ranges: List<TextRange>) = ranges.map { text.substring(it.startOffset, it.endOffset) }

    fun testOffersEachChainAroundTheCaret() {
        val text = "fn a {\n  foo.bar(1).baz.size\n}\n"
        assertEquals(listOf("foo", "foo.bar(1)", "foo.bar(1).baz", "foo.bar(1).baz.size"), texts(text, InkoIntroduceVariable.candidatesAt(stream(text), text.indexOf("foo"))))
        assertEquals(listOf("foo.bar(1)", "foo.bar(1).baz", "foo.bar(1).baz.size"), texts(text, InkoIntroduceVariable.candidatesAt(stream(text), text.indexOf("bar"))))
    }

    fun testOffersConstructorCallsAndLiterals() {
        val text = "fn a {\n  Foo(name: 'x').name\n  42\n}\n"
        assertEquals(listOf("Foo(name: 'x')", "Foo(name: 'x').name"), texts(text, InkoIntroduceVariable.candidatesAt(stream(text), text.indexOf("Foo"))))
        assertEquals(listOf("42"), texts(text, InkoIntroduceVariable.candidatesAt(stream(text), text.indexOf("42"))))
    }

    fun testSelectionsMustBeWholeExpressions() {
        val text = "fn a {\n  let x = (a + b) * 2\n}\n"
        val s = stream(text)
        fun select(part: String) = InkoIntroduceVariable.selected(s, TextRange(text.indexOf(part), text.indexOf(part) + part.length))?.let { text.substring(it.startOffset, it.endOffset) }
        assertEquals("(a + b) * 2", select(" (a + b) * 2"))
        assertEquals("a + b", select("a + b"))
        assertNull(select("(a + b"))
        assertNull(select("+ b) * 2"))
        assertNull(select("let x"))
    }

    fun testFindsTheSameExpressionInTheMethodOnly() {
        val text = "fn a {\n  foo.bar + foo.bar(1)\n  x.foo.bar\n  foo.bar\n}\n\nfn b {\n  foo.bar\n}\n"
        val s = stream(text)
        val first = TextRange(text.indexOf("foo.bar"), text.indexOf("foo.bar") + 7)
        // Not `foo.bar(1)` (a call with arguments), not `x.foo.bar` (a different receiver), not method b's.
        assertEquals(listOf(first.startOffset, text.indexOf("  foo.bar\n}") + 2), InkoIntroduceVariable.occurrences(s, first).map { it.startOffset })
    }

    fun testSuggestsNamesInInkosStyle() {
        fun name(text: String, part: String) = InkoIntroduceVariable.suggestName(stream(text), TextRange(text.indexOf(part), text.indexOf(part) + part.length))
        assertEquals("string", name("x.to_string", "x.to_string"))
        assertEquals("array", name("x.into_array", "x.into_array"))
        assertEquals("empty", name("x.empty?", "x.empty?"))
        assertEquals("byte_array", name("ByteArray.new", "ByteArray.new"))
        assertEquals("person", name("Person(name: 'a')", "Person(name: 'a')"))
        assertEquals("count", name("@count", "@count"))
        assertEquals("number", name("42", "42"))
        assertEquals("string", name("'hi'", "'hi'"))
        assertEquals("values", name("[1, 2]", "[1, 2]"))
    }

    fun testPlacesTheLetSoEveryReplacedUseIsInScope() {
        val text = "fn a {\n  if x {\n    foo.size\n  }\n  foo.size\n}\n"
        val s = stream(text)
        val first = TextRange(text.indexOf("foo.size"), text.indexOf("foo.size") + 8)
        val plan = InkoIntroduceVariable.plan(s, first, InkoIntroduceVariable.occurrences(s, first)).getOrThrow()
        // Before the `if`, in the block that holds both uses.
        assertEquals(text.indexOf("if x"), plan.insertAt)
        assertEquals("let size = foo.size\n  ", plan.declaration)
        // Only the inner one: inside the `if`'s block.
        assertEquals(text.indexOf("foo.size"), InkoIntroduceVariable.plan(s, first, listOf(first)).getOrThrow().insertAt)
    }

    fun testKeepsMultiLineChainsTogether() {
        val text = "fn a {\n  let y = foo\n    .bar\n    .baz(x.size)\n}\n"
        val s = stream(text)
        val size = TextRange(text.indexOf("x.size"), text.indexOf("x.size") + 6)
        assertEquals(text.indexOf("let y"), InkoIntroduceVariable.plan(s, size, listOf(size)).getOrThrow().insertAt)
    }

    fun testRefusesMatchArmsWithoutBraces() {
        val text = "fn a {\n  match x {\n    case Some(v) -> v.size\n    case _ -> 0\n  }\n}\n"
        val s = stream(text)
        val size = TextRange(text.indexOf("v.size"), text.indexOf("v.size") + 6)
        assertTrue(InkoIntroduceVariable.plan(s, size, listOf(size)).isFailure)
    }

    fun testAvoidsNamesAlreadyInUse() {
        val text = "fn a(size: Int) {\n  foo.size\n}\n"
        val s = stream(text)
        val range = TextRange(text.indexOf("foo.size"), text.indexOf("foo.size") + 8)
        assertEquals("size2", InkoIntroduceVariable.plan(s, range, listOf(range)).getOrThrow().name)
    }

    fun testIntroducesAndRenamesInPlace() {
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
        myFixture.configureByText("main.inko", "fn a -> Int {\n  <selection>items.size</selection> + 1\n}\n")
        InkoIntroduceVariableHandler().invoke(project, myFixture.editor, myFixture.file, null)
        TemplateManagerImpl.getTemplateState(myFixture.editor)?.let { it.gotoEnd(false) }
        assertEquals("fn a -> Int {\n  let size = items.size\n  size + 1\n}\n", myFixture.editor.document.text)
    }

    fun testTheIntroduceVariableActionUsesInkosHandler() {
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
        myFixture.configureByText("main.inko", "fn a -> String {\n  <selection>name.to_string</selection>\n}\n")
        myFixture.performEditorAction("IntroduceVariable")
        TemplateManagerImpl.getTemplateState(myFixture.editor)?.gotoEnd(false)
        assertEquals("fn a -> String {\n  let string = name.to_string\n  string\n}\n", myFixture.editor.document.text)
    }
}
