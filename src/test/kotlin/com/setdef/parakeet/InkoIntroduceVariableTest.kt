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

    // Inko's binary operators group left to right with one precedence: `60 - 60` in `a - 60 - 60` is `(a - 60) - 60`.
    fun testPartOfAnOperatorChainIsntAnExpression() {
        val text = "fn a(total: Int) -> Int {\n  let x = 60 - 60\n  total - 60 - 60 == 1 - 2\n}\n"
        val s = stream(text)
        fun select(part: String, nth: Int = 0): String? {
            var start = -1
            repeat(nth + 1) { start = text.indexOf(part, start + 1) }
            return InkoIntroduceVariable.selected(s, TextRange(start, start + part.length))?.let { text.substring(it.startOffset, it.endOffset) }
        }
        assertEquals("60 - 60", select("60 - 60"))
        assertNull(select("60 - 60", nth = 1))
        assertNull(select("1 - 2"))
        assertEquals("total - 60", select("total - 60"))
        // Only the occurrence after `=` is one; the one in the chain isn't.
        val first = text.indexOf("60 - 60")
        assertEquals(listOf(first), InkoIntroduceVariable.occurrences(s, TextRange(first, first + 7)).map { it.startOffset })
    }

    fun testFindsTheSameExpressionInTheMethodOnly() {
        val text = "fn a {\n  foo.bar + foo.bar(1)\n  x.foo.bar\n  foo.bar\n}\n\nfn b {\n  foo.bar\n}\n"
        val s = stream(text)
        val first = TextRange(text.indexOf("foo.bar"), text.indexOf("foo.bar") + 7)
        // Not `foo.bar(1)` (a call with arguments), not `x.foo.bar` (a different receiver), not method b's.
        assertEquals(listOf(first.startOffset, text.indexOf("  foo.bar\n}") + 2), InkoIntroduceVariable.occurrences(s, first).map { it.startOffset })
    }

    /** The occurrences of the [nth] [part] in [text], as offsets. */
    private fun occurrencesOf(text: String, part: String, nth: Int = 0): List<Int> {
        var at = -1
        repeat(nth + 1) { at = text.indexOf(part, at + 1) }
        return InkoIntroduceVariable.occurrences(stream(text), TextRange(at, at + part.length)).map { it.startOffset }
    }

    private fun offsetsOf(text: String, part: String, vararg nths: Int): List<Int> =
        nths.map { nth -> var at = -1; repeat(nth + 1) { at = text.indexOf(part, at + 1) }; at }

    // `sum += 1` assigns `sum` (Inko's `binary_assign_variable`): replacing it would assign the new, immutable variable.
    fun testACompoundAssignedOccurrenceIsNotOne() {
        for (operator in listOf("+=", "-=", "*=", "/=", "%=", "**=", "<<=", ">>=", ">>>=", "|=", "&=", "^=")) {
            val text = "fn a -> Int {\n  let mut sum = 0\n  foo(sum)\n  sum $operator 1\n}\n"
            assertEquals(operator, offsetsOf(text, "sum", 1), occurrencesOf(text, "sum", nth = 1))
        }
    }

    // After an assignment to a variable the expression reads, the same expression has another value.
    fun testOccurrencesAfterAnAssignmentAreNotOnes() {
        val compound = "fn a(items: Array[Int]) -> Int {\n  let mut sum = 0\n  let first = sum + items.size\n  sum += 1\n  first + (sum + items.size)\n}\n"
        assertEquals(offsetsOf(compound, "sum + items.size", 0), occurrencesOf(compound, "sum + items.size"))
        assertEquals(offsetsOf(compound, "sum + items.size", 1), occurrencesOf(compound, "sum + items.size", nth = 1))
        val plain = "fn a -> Int {\n  let mut x = 1\n  foo(x * 2)\n  x = 2\n  bar(x * 2)\n}\n"
        assertEquals(offsetsOf(plain, "x * 2", 0), occurrencesOf(plain, "x * 2"))
        // Before the assignment, both are the same value.
        val before = "fn a -> Int {\n  let mut x = 1\n  foo(x * 2)\n  bar(x * 2)\n  x -= 2\n  x\n}\n"
        assertEquals(offsetsOf(before, "x * 2", 0, 1), occurrencesOf(before, "x * 2"))
    }

    // A loop's next pass reads what its body assigned, so a `let` before the loop would be out of date.
    fun testOccurrencesInALoopThatAssignsAreNotOnes() {
        val text = "fn a -> Int {\n  let mut n = 1\n  foo(n * 2)\n  while n < 10 {\n    bar(n * 2)\n    n *= 2\n  }\n  0\n}\n"
        assertEquals(offsetsOf(text, "n * 2", 0), occurrencesOf(text, "n * 2"))
        assertEquals(offsetsOf(text, "n * 2", 1), occurrencesOf(text, "n * 2", nth = 1))
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

    // openflow's `Snapshot.of(state).rooms.get('bathroom')` is an `Option[ref RoomSnapshot]`, borrowed from the
    // snapshot the expression makes, which is dropped at the end of its statement: a variable can't keep it.
    fun testRefusesABorrowFromAValueTheExpressionMakes() {
        val text = "fn a(rooms: Map[String, Room]) -> Int {\n  show(Snapshot.of(s).rooms.get('b'))\n  show(rooms.get('b'))\n}\n"
        myFixture.configureByText("main.inko", text)
        Compiler()
            .hover(At("main.inko", text, "Snapshot.of"), "Option[ref Room]", length = "Snapshot.of(s).rooms.get('b')".length)
            .hover(At("main.inko", text, "rooms.get('b'))\n}"), "Option[ref Room]", length = "rooms.get('b')".length)
            .install()
        fun range(part: String) = text.lastIndexOf(part).let { TextRange(it, it + part.length) }
        assertEquals(
            "`Snapshot.of(s).rooms.get('b')` borrows from a value it makes itself, which is dropped at the end of the statement, so a variable can't keep it.",
            InkoIntroduceVariable.borrowsFromATemporary(myFixture.file as InkoFile, range("Snapshot.of(s).rooms.get('b')")),
        )
        // From a variable, the borrow lives as long as it does.
        assertNull(InkoIntroduceVariable.borrowsFromATemporary(myFixture.file as InkoFile, range("rooms.get('b')")))
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
