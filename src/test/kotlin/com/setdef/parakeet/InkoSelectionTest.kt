package com.setdef.parakeet

import com.intellij.openapi.actionSystem.IdeActions

/** Extend Selection (⌥↑): each press grows the selection to the next enclosing piece. */
class InkoSelectionTest : InkoTestCase() {
    /** The selected text after each press of Extend Selection, from <caret> in [text]. */
    private fun steps(text: String, presses: Int): List<String> {
        myFixture.configureByText("main.inko", text)
        return (1..presses).map {
            myFixture.performEditorAction(IdeActions.ACTION_EDITOR_SELECT_WORD_AT_CARET)
            myFixture.editor.selectionModel.selectedText.orEmpty()
        }
    }

    private val method = "# Greets.\nfn greet(name: String) {\n  if ready? {\n    print('hi \${name}', <caret>count + 1)\n  }\n}\n"

    fun testGrowsThroughArgumentsBracketsStatementsAndBlocks() {
        val steps = steps(method, 11)
        assertEquals(
            listOf(
                "count",
                "count + 1",
                "'hi \${name}', count + 1",
                "('hi \${name}', count + 1)",
                "print('hi \${name}', count + 1)",
                "{\n    print('hi \${name}', count + 1)\n  }",
                "if ready? {\n    print('hi \${name}', count + 1)\n  }",
            ),
            steps.take(7).map { it.trim() }.distinct().take(7),
        )
        // Then the method, and the method with its comment.
        assertTrue(steps.last(), steps.any { it.startsWith("fn greet") && it.endsWith("}") })
        assertTrue(steps.last(), steps.any { it.startsWith("# Greets.") })
    }

    fun testSelectsAStringsContentsBeforeTheString() {
        val steps = steps("fn main {\n  print('hello wor<caret>ld')\n}\n", 3)
        assertEquals(listOf("world", "hello world", "'hello world'"), steps)
    }
}
