package com.setdef.parakeet

import com.intellij.codeInsight.unwrap.UnwrapHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile

/** Unwrap/Remove (⌘⇧Delete): the reverse of Surround With. */
class InkoUnwrapTest : InkoTestCase() {
    /** The options offered at <caret> in [text], and the text after choosing [choice]. */
    private fun unwrap(text: String, choice: String? = null): Pair<List<String>, String> {
        myFixture.configureByText("main.inko", text)
        var offered = emptyList<String>()
        val handler = object : UnwrapHandler() {
            override fun selectOption(options: MutableList<out MyUnwrapAction>, editor: Editor, file: PsiFile) {
                offered = options.map { it.templatePresentation.text }
                options.firstOrNull { it.templatePresentation.text == choice }?.actionPerformed(com.intellij.testFramework.TestActionEvent.createTestEvent())
            }
        }
        handler.invoke(project, myFixture.editor, myFixture.file)
        return offered to myFixture.editor.document.text
    }

    private val ifElse = "fn main {\n  if ready? {\n    start\n    go\n  } else {\n    <caret>wait\n  }\n}\n"

    fun testOffersInnermostFirst() {
        assertEquals(listOf("Unwrap 'else...'", "Remove 'else...'"), unwrap(ifElse).first)
    }

    fun testUnwrapsTheIfBranch() {
        val (_, result) = unwrap(ifElse.replace("<caret>", "").replace("start", "<caret>start"), "Unwrap 'if...'")
        assertEquals("fn main {\n  start\n  go\n}\n", result)
    }

    fun testUnwrapsTheElseBranch() {
        assertEquals("fn main {\n  wait\n}\n", unwrap(ifElse, "Unwrap 'else...'").second)
    }

    fun testRemovesTheElseBranch() {
        assertEquals("fn main {\n  if ready? {\n    start\n    go\n  }\n}\n", unwrap(ifElse, "Remove 'else...'").second)
    }

    fun testUnwrapsElseOfAnElseIfChain() {
        val text = "fn main {\n  if a {\n    one\n  } else if b {\n    two\n  } else {\n    <caret>three\n  }\n}\n"
        assertEquals("fn main {\n  three\n}\n", unwrap(text, "Unwrap 'else...'").second)
    }

    fun testUnwrapsLoopsAndNestedConstructsKeepIndentation() {
        val text = "fn main {\n  while running? {\n    if ok? {\n      <caret>step\n    }\n  }\n}\n"
        val (offered, result) = unwrap(text, "Unwrap 'while...'")
        assertEquals(listOf("Unwrap 'if...'", "Unwrap 'while...'"), offered)
        assertEquals("fn main {\n  if ok? {\n    step\n  }\n}\n", result)
    }

    fun testForRecoverAndBareBlocks() {
        assertEquals("fn main {\n  print(x)\n}\n", unwrap("fn main {\n  for x in xs {\n    <caret>print(x)\n  }\n}\n", "Unwrap 'for...'").second)
        assertEquals("fn main {\n  let a = Thing.new\n}\n", unwrap("fn main {\n  let a = recover { <caret>Thing.new }\n}\n", "Unwrap 'recover...'").second)
        assertEquals("fn main {\n  inner\n}\n", unwrap("fn main {\n  {\n    <caret>inner\n  }\n}\n", "Unwrap '{...}'").second)
    }

    fun testNothingToUnwrapInAMethodBody() {
        assertEquals(emptyList<String>(), unwrap("fn main {\n  <caret>go\n}\n").first)
    }
}
