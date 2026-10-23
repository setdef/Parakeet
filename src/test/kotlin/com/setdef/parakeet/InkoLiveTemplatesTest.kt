package com.setdef.parakeet

import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.codeInsight.template.impl.TemplateSettings

class InkoLiveTemplatesTest : InkoTestCase() {
    private fun expand(text: String): String {
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
        myFixture.configureByText("main.inko", text)
        myFixture.type('\t')
        return myFixture.editor.document.text
    }

    fun testTemplatesAreRegistered() {
        // Expandable templates; the ones wrapping a $SELECTION$ are for Surround With (see InkoSurroundTest).
        val names = TemplateSettings.getInstance().templates.filter { it.groupName == "Inko" && !it.isSelectionTemplate }.map { it.key }.toSet()
        assertEquals(setOf("fn", "fnp", "type", "typep", "trait", "impl", "match", "main", "tests", "test"), names)
    }

    fun testMainExpands() {
        assertEquals("type async Main {\n  fn async main {\n    \n  }\n}\n", expand("main<caret>\n"))
    }

    fun testTestExpandsWithItsName() {
        assertEquals("  t.test('does something', fn (t) {\n    \n  })\n", expand("  test<caret>\n"))
    }

    fun testNotInComments() {
        val text = expand("# main<caret>\n")
        assertTrue(text, text.startsWith("# main") && !text.contains("type async"))
    }
}
