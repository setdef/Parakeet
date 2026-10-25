package com.setdef.parakeet

import com.intellij.codeInsight.template.impl.TemplateManagerImpl

/**
 * Extract Method's types for locals with no written type (`let mut sum = 0`): the compiler's, from
 * Cuttlebone's hover at the local's name. It writes code, so only a fresh answer counts; without one, the
 * type is a placeholder, as before.
 */
class InkoExtractMethodTypesTest : InkoTestCase(onDisk = true) {
    private val code = "fn total(items: Array[Int]) -> Int {\n  let mut sum = 0\n  <selection>sum += items.size</selection>\n  sum\n}\n"
    private val clean = code.replace("<selection>", "").replace("</selection>", "")

    /** Runs Extract Method on the selection in [code], accepting the suggested name, and returns the result. */
    private fun extract(): String {
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
        val file = myFixture.addFileToProject("src/main.inko", clean)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.selectionModel.setSelection(code.indexOf("<selection>"), code.indexOf("</selection>") - "<selection>".length)
        myFixture.performEditorAction("ExtractMethod")
        TemplateManagerImpl.getTemplateState(myFixture.editor)?.gotoEnd(false)
        return myFixture.editor.document.text
    }

    fun testAnUntypedLocalTakesTheCompilersType() {
        Compiler().hover(At("src/main.inko", clean, "sum = 0"), "Int", length = 3).install()
        assertEquals(
            "fn total(items: Array[Int]) -> Int {\n  let mut sum = 0\n  sum = extracted(sum, items)\n  sum\n}\n\n" +
                "fn extracted(sum: Int, items: mut Array[Int]) -> Int {\n  let mut sum = sum\n  sum += items.size\n  sum\n}\n",
            extract(),
        )
    }

    // A stale answer is from the last version that type checked: it may not be the type now.
    fun testAStaleTypeIsNotWritten() {
        stubCuttlebone { method, file, _ ->
            val hover = org.eclipse.lsp4j.Hover(org.eclipse.lsp4j.MarkupContent("markdown", "```inko\nInt\n```"))
            CuttleboneQueries.Answer(hover, CuttleboneStatus(method, file.url, "stale", null, 1.0))
        }
        assertTrue(extract().contains("fn extracted(sum: Type, items: mut Array[Int]) -> Type {"))
    }

    fun testWithoutAnAnswerTheTypeIsAPlaceholder() {
        cuttleboneCantCheckTheCode()
        assertTrue(extract().contains("fn extracted(sum: Type, items: mut Array[Int]) -> Type {"))
    }

    // A type the compiler couldn't infer fully would not compile either.
    fun testAPartlyInferredTypeIsNotWritten() {
        Compiler().hover(At("src/main.inko", clean, "sum = 0"), "Array[?]", length = 3).install()
        assertTrue(extract().contains("fn extracted(sum: Type, items: mut Array[Int]) -> Type {"))
    }
}
