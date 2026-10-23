package com.setdef.parakeet

import com.intellij.lang.injection.InjectedLanguageManager

/** Inko code in Markdown code fences (```inko). */
class InkoMarkdownTest : InkoTestCase() {
    fun testInkoFencesAreInko() {
        myFixture.configureByText("README.md", "# Example\n\n```inko\nfn main {\n  helper\n}\n```\n")
        val offset = myFixture.file.text.indexOf("helper")
        val injected = InjectedLanguageManager.getInstance(project).findInjectedElementAt(myFixture.file, offset)
        assertEquals(InkoLanguage, injected?.containingFile?.language)
    }
}
