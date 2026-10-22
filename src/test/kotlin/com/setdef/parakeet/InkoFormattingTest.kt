package com.setdef.parakeet

import com.intellij.openapi.actionSystem.IdeActions

class InkoFormattingTest : InkoTestCase() {
    fun testReformatPipesTheDocumentThroughInkoFmt() {
        val fake = useFakeInko(FakeInkoCli { call -> FakeInkoCli.ok(stdout = call.stdin!!.replace("   ", " ")) })
        myFixture.configureByText("main.inko", "fn   main {}\n")
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
        assertEquals("fn main {}\n", myFixture.editor.document.text)
        assertEquals(listOf("fmt", "-"), fake.calls.single().args)
    }

    fun testFailuresLeaveTheDocumentAlone() {
        useFakeInko(FakeInkoCli { FakeInkoCli.failed("syntax error") })
        myFixture.configureByText("main.inko", "fn   main {\n")
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
        assertEquals("fn   main {\n", myFixture.editor.document.text)
    }
}
