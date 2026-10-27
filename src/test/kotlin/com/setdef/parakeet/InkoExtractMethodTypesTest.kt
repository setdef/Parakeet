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
    private fun extract(code: String = this.code): String {
        val clean = code.replace("<selection>", "").replace("</selection>", "")
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

    // kvi's `t.equal(map.get(key_with_hash('a', 1)), …)`: an extracted expression's value has the compiler's type
    // too, from hover's answer for exactly that expression.
    fun testAnExpressionsValueTakesTheCompilersType() {
        val code = "fn first(items: Array[Int]) -> Bool {\n  <selection>items.get(0)</selection> == 1\n}\n"
        val clean = code.replace("<selection>", "").replace("</selection>", "")
        Compiler().hover(At("src/main.inko", clean, "items.get(0)"), "Int", length = 12).install()
        assertEquals(
            "fn first(items: Array[Int]) -> Bool {\n  extracted(items) == 1\n}\n\nfn extracted(items: mut Array[Int]) -> Int {\n  items.get(0)\n}\n",
            extract(code),
        )
    }

    private val pathCode = "fn total -> Int {\n  let p = make\n  <selection>p.size</selection>\n}\n"

    /** [pathCode], where the compiler's type for `p` is `Path`, which the module doesn't import. */
    private fun withPath() = Compiler().hover(At("src/main.inko", pathCode.replace("<selection>", "").replace("</selection>", ""), "p = make"), "Path", length = 1).install()

    // idoc's `let pwd = …` (a `Path`) and its tests' `t` (a `mut Test`): the type is imported where the index
    // knows a single type of that name, so the code compiles.
    fun testATypeTheModuleDoesntImportIsImported() {
        withPath()
        putTempDirInProjectContent()
        useFakeInko(FakeInkoDoc.cli { mirror ->
            listOf(FakeInkoDoc.module("std.fs.path", mirror.resolve("src/main.inko"), types = listOf(FakeInkoDoc.type("Path", 1))))
        })
        assertEquals(
            "import std.fs.path (Path)\n\nfn total -> Int {\n  let p = make\n  extracted(p)\n}\n\nfn extracted(p: mut Path) -> Int {\n  p.size\n}\n",
            extract(pathCode),
        )
    }

    // Without one, it's the placeholder: a name the module can't see wouldn't compile either.
    fun testATypeNoImportCanBeFoundForIsAPlaceholder() {
        withPath()
        val result = extract(pathCode)
        assertTrue(result, result.contains("fn extracted(p: Type) -> Int {"))
    }

    // inko-wobsite's `Hashes(Map.new)`: `Map.new` says only `Map`, which needs its type arguments.
    fun testTheCompilersTypeComesBeforeTheConstructorsName() {
        val code = "fn hashes -> Int {\n  count(<selection>Map.new</selection>)\n}\n"
        val clean = code.replace("<selection>", "").replace("</selection>", "")
        Compiler().hover(At("src/main.inko", clean, "Map.new"), "Map[String, Int]", length = 7).install()
        assertTrue(extract(code).contains("fn extracted -> Map[String, Int] {\n  Map.new\n}"))
    }

    // inko-wobsite's `Hashes(Map.new)` again, with no answer from Cuttlebone (its check crashed): `Map` alone,
    // from the constructor's name, needs type arguments, so it's the placeholder, which asks for them.
    fun testWithNoAnswerAGenericConstructorsTypeIsAPlaceholder() {
        val code = "fn hashes -> Int {\n  count(<selection>Map.new</selection>)\n}\n"
        Compiler().crashed().install()
        assertTrue(extract(code).contains("fn extracted -> Type {\n  Map.new\n}"))
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
