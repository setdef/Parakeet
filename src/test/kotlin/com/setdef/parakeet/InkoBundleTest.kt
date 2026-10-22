package com.setdef.parakeet

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import org.jetbrains.plugins.textmate.TextMateService

class InkoBundleTest : InkoTestCase() {
    override fun setUp() {
        super.setUp()
        TextMateService.getInstance().ensureInitialized()
    }

    fun testBundleRegistersInkoGrammar() {
        val descriptor = TextMateService.getInstance().getLanguageDescriptorByExtension("inko")
        assertNotNull("Inko bundle was not loaded", descriptor)
        assertEquals("source.inko", descriptor!!.rootScopeName.toString())
    }

    fun testInkoFilesUseInkoFileType() {
        val file = myFixture.configureByText("main.inko", "fn main {}\n")
        assertEquals(InkoFileType.INSTANCE, file.virtualFile.fileType)
    }

    fun testHighlightingUsesTheTextMateGrammar() {
        val file = myFixture.configureByText("main.inko", "fn main {}\n")
        val highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(InkoLanguage, project, file.virtualFile)
        assertTrue(highlighter.javaClass.name, highlighter.javaClass.name.contains("TextMate"))
    }

    fun testTypingAClosingQuoteStepsOverIt() {
        myFixture.configureByText("main.inko", "let a = <caret>\n")
        myFixture.type("'hi'")
        assertEquals("let a = 'hi'\n", myFixture.editor.document.text)
    }

    fun testQuoteAutoClosing() {
        myFixture.configureByText("main.inko", "let a = <caret>\n")
        myFixture.type("'")
        assertEquals("let a = ''\n", myFixture.editor.document.text)
    }

    fun testLineCommentToggling() {
        myFixture.configureByText("main.inko", "<caret>let a = 1\n")
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
        myFixture.checkResult("# let a = 1\n")
    }

    fun testBracketAutoClosing() {
        myFixture.configureByText("main.inko", "fn main <caret>\n")
        myFixture.type("{")
        assertEquals("fn main {}\n", myFixture.editor.document.text)
    }
}
