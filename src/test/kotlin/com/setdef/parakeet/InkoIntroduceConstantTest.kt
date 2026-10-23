package com.setdef.parakeet

import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.openapi.util.TextRange

class InkoIntroduceConstantTest : InkoTestCase() {
    private fun range(text: String, part: String, nth: Int = 0): TextRange {
        var start = -1
        repeat(nth + 1) { start = text.indexOf(part, start + 1) }
        return TextRange(start, start + part.length)
    }

    private fun unsupported(text: String, part: String) = InkoIntroduceConstant.unsupported(InkoTokenStream(text), range(text, part))

    fun testAllowsWhatInkoConstantsCanHold() {
        val text = "let LIMIT = 10\n\nfn a -> Int {\n  60 * 60 + LIMIT * 2 + [1, 2].size + 'hi'.size + (1 + 2) + -1\n}\n"
        assertNull(unsupported(text, "60 * 60"))
        assertNull(unsupported(text, "LIMIT * 2"))
        assertNull(unsupported(text, "[1, 2]"))
        assertNull(unsupported(text, "'hi'"))
        assertNull(unsupported(text, "(1 + 2)"))
        assertNull(unsupported(text, "-1"))
    }

    fun testRefusesWhatInkoConstantsCantHold() {
        val text = "fn a(x: Int) -> Int {\n  x + 1 + Foo(1).size + foo(2) + '${'$'}{x}'.size + Foo.new.size\n}\n"
        assertNotNull(unsupported(text, "x + 1"))
        assertNotNull(unsupported(text, "Foo(1)"))
        assertNotNull(unsupported(text, "foo(2)"))
        assertTrue(unsupported(text, "'${'$'}{x}'")!!.contains("\${"))
        assertNotNull(unsupported(text, "Foo.new"))
    }

    fun testPlacesItAfterImportsAndReplacesOccurrencesInTheWholeFile() {
        val text = "import std.stdio (Stdout)\n\nfn a -> Int {\n  60 * 60\n}\n\nfn b -> Int {\n  60 * 60 * 2\n}\n"
        val stream = InkoTokenStream(text)
        val expression = range(text, "60 * 60")
        val occurrences = InkoIntroduceVariable.occurrences(stream, expression, fileWide = true)
        assertEquals(2, occurrences.size)
        myFixture.configureByText("main.inko", text)
        InkoIntroduceConstant.apply(project, myFixture.editor, InkoIntroduceConstant.plan(stream, expression, occurrences).getOrThrow())
        assertEquals(
            "import std.stdio (Stdout)\n\nlet VALUE = 60 * 60\n\nfn a -> Int {\n  VALUE\n}\n\nfn b -> Int {\n  VALUE * 2\n}\n",
            myFixture.editor.document.text,
        )
    }

    fun testPlacesItAfterOtherConstantsWithAFreshName() {
        val text = "let VALUE = 1\n\nfn a -> Int {\n  VALUE + 2\n}\n"
        val stream = InkoTokenStream(text)
        val plan = InkoIntroduceConstant.plan(stream, range(text, "VALUE + 2"), listOf(range(text, "VALUE + 2"))).getOrThrow()
        assertEquals("VALUE_2", plan.name)
        myFixture.configureByText("main.inko", text)
        InkoIntroduceConstant.apply(project, myFixture.editor, plan)
        assertEquals("let VALUE = 1\nlet VALUE_2 = VALUE + 2\n\nfn a -> Int {\n  VALUE_2\n}\n", myFixture.editor.document.text)
    }

    fun testNamesStringConstantsAfterWhatTheyAre() {
        val text = "fn a -> String {\n  'Hello'.to_upper\n}\n"
        assertEquals("STRING", InkoIntroduceConstant.plan(InkoTokenStream(text), range(text, "'Hello'"), emptyList()).getOrThrow().name)
    }

    fun testTheIntroduceConstantActionUsesInkosHandler() {
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
        myFixture.configureByText("main.inko", "fn a -> Int {\n  <selection>60 * 60</selection>\n}\n")
        myFixture.performEditorAction("IntroduceConstant")
        TemplateManagerImpl.getTemplateState(myFixture.editor)?.gotoEnd(false)
        assertEquals("let VALUE = 60 * 60\n\nfn a -> Int {\n  VALUE\n}\n", myFixture.editor.document.text)
    }
}
