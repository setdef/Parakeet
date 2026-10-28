package com.setdef.parakeet

import com.intellij.openapi.actionSystem.IdeActions

/**
 * Extend Selection (⌥↑): each press grows the selection to the next of Cuttlebone's selection ranges around the
 * caret. CuttleboneIntegrationTest checks the ranges themselves, against the real Cuttlebone.
 */
class InkoSelectionTest : InkoTestCase(onDisk = true) {
    private val text = "fn main {\n  print('héllo', count + 1)\n}\n"

    private fun range(from: String, to: String): org.eclipse.lsp4j.Range {
        fun position(offset: Int) = org.eclipse.lsp4j.Position(text.substring(0, offset).count { it == '\n' }, offset - (text.lastIndexOf('\n', offset - 1) + 1))
        val start = text.indexOf(from)
        return org.eclipse.lsp4j.Range(position(start), position(text.indexOf(to, start) + to.length))
    }

    /** The selected text after each press of Extend Selection, from the caret before [at]. */
    private fun steps(at: String, presses: Int): List<String> {
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("src/main.inko", text).virtualFile)
        myFixture.editor.caretModel.moveToOffset(text.indexOf(at))
        return (1..presses).map {
            myFixture.performEditorAction(IdeActions.ACTION_EDITOR_SELECT_WORD_AT_CARET)
            myFixture.editor.selectionModel.selectedText.orEmpty()
        }
    }

    fun testGrowsThroughCuttlebonesRanges() {
        val offset = text.indexOf("count")
        stubCuttlebone { method, file, at ->
            val ranges = listOf(range("count", "count"), range("count", "1"), range("'héllo'", "1"), range("(", ")"), range("print", ")"), range("fn", "}"))
            CuttleboneQueries.Answer(ranges.takeIf { method == "textDocument/selectionRange" && at == offset }, CuttleboneStatus(method, file.url, "fresh", null, 1.0))
        }
        assertEquals(
            listOf("count", "count + 1", "'héllo', count + 1", "('héllo', count + 1)", "print('héllo', count + 1)", text.trimEnd()),
            steps("count", 6),
        )
    }

    // Without an answer (Cuttlebone isn't running, say), the IDE's own steps are left: the word, the line.
    fun testWithoutAnAnswerOnlyTheIdesOwnStepsAreLeft() {
        stubCuttlebone { _, _, _ -> null }
        assertEquals("count", steps("count", 1).single())
    }
}
