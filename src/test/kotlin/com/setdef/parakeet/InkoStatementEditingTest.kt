package com.setdef.parakeet

import com.intellij.openapi.actionSystem.IdeActions

class InkoStatementEditingTest : InkoTestCase() {
    private fun move(text: String, down: Boolean): String {
        myFixture.configureByText("main.inko", text)
        myFixture.performEditorAction(if (down) IdeActions.ACTION_MOVE_STATEMENT_DOWN_ACTION else IdeActions.ACTION_MOVE_STATEMENT_UP_ACTION)
        return myFixture.editor.document.text
    }

    fun testMovesAStatementPastItsNeighbor() = assertEquals(
        "fn a {\n  let b = 2\n  let a = 1\n}\n",
        move("fn a {\n  let <caret>a = 1\n  let b = 2\n}\n", down = true),
    )

    fun testMovesBlocksAsAWhole() = assertEquals(
        "fn a {\n  let b = 2\n  if x {\n    foo\n  }\n}\n",
        move("fn a {\n  <caret>if x {\n    foo\n  }\n  let b = 2\n}\n", down = true),
    )

    fun testMovesMultiLineChainsAsAWhole() = assertEquals(
        "fn a {\n  let b = foo\n    .bar\n  let a = 1\n}\n",
        move("fn a {\n  let a = 1\n  let <caret>b = foo\n    .bar\n}\n", down = false),
    )

    fun testMovesMethodsWithTheirDocComments() = assertEquals(
        "type A {\n  # B does b.\n  fn b {}\n\n  # A does a.\n  fn a {}\n}\n",
        move("type A {\n  # A does a.\n  fn <caret>a {}\n\n  # B does b.\n  fn b {}\n}\n", down = true),
    )

    fun testStaysInItsBlock() {
        val text = "fn a {\n  if x {\n    <caret>foo\n  }\n  bar\n}\n"
        assertEquals(text.replace("<caret>", ""), move(text, down = true))
    }

    private fun complete(text: String): String {
        myFixture.configureByText("main.inko", text)
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_COMPLETE_STATEMENT)
        return myFixture.editor.document.text.let { it.substring(0, myFixture.caretOffset) + "<caret>" + it.substring(myFixture.caretOffset) }
    }

    fun testAddsABody() = assertEquals("fn a {\n  if ready {\n    <caret>\n  }\n}\n", complete("fn a {\n  if read<caret>y\n}\n"))

    fun testAddsAMethodBody() = assertEquals("type A {\n  fn pub name -> String {\n    <caret>\n  }\n}\n", complete("type A {\n  fn pub name -> Str<caret>ing\n}\n"))

    fun testClosesBracketsAndStartsANewLine() = assertEquals("fn a {\n  foo(bar(1))\n  <caret>\n}\n", complete("fn a {\n  foo(bar(<caret>1\n}\n"))

    fun testPutsAClosuresBodyInsideTheCall() = assertEquals(
        "fn a {\n  items.each(fn (x) {\n    <caret>\n  })\n}\n",
        complete("fn a {\n  items.each(fn (<caret>x)\n}\n"),
    )

    // Typing `while x {` in a method pairs it with the method's `}`, which is less indented: still open.
    fun testGoesInsideAnOpenBlock() = assertEquals("fn a {\n  while x {\n    <caret>\n  }\n}\n", complete("fn a {\n  while x {<caret>\n}\n"))

    fun testLeavesRequiredTraitMethodsWithoutABody() = assertEquals(
        "trait A {\n  fn name -> String\n  <caret>\n}\n",
        complete("trait A {\n  fn name -> Str<caret>ing\n}\n"),
    )
}
