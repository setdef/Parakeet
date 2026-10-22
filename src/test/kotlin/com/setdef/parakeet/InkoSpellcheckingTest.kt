package com.setdef.parakeet

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ex.InspectionToolRegistrar
import com.intellij.spellchecker.inspections.SpellCheckingInspection

class InkoSpellcheckingTest : InkoTestCase() {
    private fun typos(text: String): List<String> {
        // Enable it by short name: the implementation is Grazie's in 2026.x, the spellchecker module's before.
        val tool = InspectionToolRegistrar.getInstance().createTools().single { it.shortName == SpellCheckingInspection.SPELL_CHECKING_INSPECTION_TOOL_NAME }
        myFixture.enableInspections(tool.tool as LocalInspectionTool)
        myFixture.configureByText("main.inko", text)
        return myFixture.doHighlighting()
            .filter { it.description?.startsWith("Typo") == true }
            .map { myFixture.editor.document.getText(com.intellij.openapi.util.TextRange(it.startOffset, it.endOffset)) }
    }

    fun testChecksCommentsAndPlainStrings() {
        assertEquals(listOf("mistaek", "speling"), typos("# A mistaek.\nfn a -> String {\n  'bad speling'\n}\n"))
    }

    fun testSkipsCodeAndInterpolatedStrings() {
        assertEmpty(typos("fn qwzx -> String {\n  \"\${qwzx} ok\"\n}\n"))
    }
}
