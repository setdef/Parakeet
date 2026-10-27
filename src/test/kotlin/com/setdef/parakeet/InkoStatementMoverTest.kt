package com.setdef.parakeet

/** Move Statement Up and Down (⇧⌘↑ / Ctrl+Shift+Up and ⇧⌘↓ / Ctrl+Shift+Down). */
class InkoStatementMoverTest : InkoTestCase() {
    private fun move(action: String, text: String): String {
        myFixture.configureByText("main.inko", text)
        myFixture.performEditorAction(action)
        return myFixture.editor.document.text
    }

    fun testMovesAStatementPastItsNeighbor() {
        assertEquals(
            "fn run(a: Int) {\n  print(2)\n  print(1)\n}\n",
            move("MoveStatementUp", "fn run(a: Int) {\n  print(1)\n  <caret>print(2)\n}\n"),
        )
    }

    // The `}` ending an `if` is in the block the `if` is in, so from its line the whole `if` moves.
    fun testFromTheClosingBraceTheWholeStatementMoves() {
        assertEquals(
            "fn run(a: Int) {\n  if a > 2 {\n    print(a)\n  }\n  print(1)\n}\n",
            move("MoveStatementUp", "fn run(a: Int) {\n  print(1)\n  if a > 2 {\n    print(a)\n  <caret>}\n}\n"),
        )
    }
}
