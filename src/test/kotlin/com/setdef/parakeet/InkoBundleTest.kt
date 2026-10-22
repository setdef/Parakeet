package com.setdef.parakeet

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.plugins.textmate.TextMateFileType
import org.jetbrains.plugins.textmate.TextMateService

class InkoBundleTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        TextMateService.getInstance().ensureInitialized()
    }

    fun testBundleRegistersInkoGrammar() {
        val descriptor = TextMateService.getInstance().getLanguageDescriptorByExtension("inko")
        assertNotNull("Inko bundle was not loaded", descriptor)
        assertEquals("source.inko", descriptor!!.rootScopeName.toString())
    }

    fun testInkoFilesUseTextMateFileType() {
        val file = myFixture.configureByText("main.inko", "fn main {}\n")
        assertEquals(TextMateFileType.INSTANCE, file.virtualFile.fileType)
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
