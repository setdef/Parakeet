package com.setdef.parakeet

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.lookup.LookupElementPresentation

/**
 * Completion: how Cuttlebone's answer (`textDocument/completion`, stubbed here; CuttleboneIntegrationTest has the real
 * one) is shown and inserted, and Parakeet's own reading of the code, which stands in only where Cuttlebone can't
 * check the code being edited.
 */
class InkoCompletionTest : InkoTestCase(onDisk = true) {
    private var autocomplete = true

    override fun setUp() {
        super.setUp()
        // Parakeet's own reading of the code, which stands in only where Cuttlebone can't check it; the tests of
        // Cuttlebone's answer stub it.
        cuttleboneCantCheckTheCode()
        // Always show the list, even for a single match, so tests can inspect it.
        autocomplete = CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = false
        myFixture.addFileToProject("inko.pkg", "")
        myFixture.addFileToProject("src/app/util.inko", "type pub Helper {\n  fn pub static new -> Helper {\n    Helper()\n  }\n\n  fn pub value -> Int {\n    42\n  }\n}\n\nfn pub helper -> Int {\n  1\n}\n")
        // Definitions are read from the code: app.other's too.
        myFixture.addFileToProject("src/app/other.inko", "type pub Other {\n  fn pub size -> Int {\n    1\n  }\n}\n\nlet pub LIMIT = 10\nlet SECRET = 1\n")
        useFakeInko(FakeInkoCli { FakeInkoCli.ok(stderr = "[]") })
    }

    override fun tearDown() {
        try {
            CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = autocomplete
        } finally {
            super.tearDown()
        }
    }

    private fun complete(text: String): List<String> {
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("src/main.inko", text.replace("<caret>", "")).virtualFile)
        myFixture.editor.caretModel.moveToOffset(text.indexOf("<caret>"))
        myFixture.completeBasic()
        return myFixture.lookupElementStrings.orEmpty()
    }

    fun testStaticMembersOfAType() {
        assertEquals(listOf("new"), complete("import app.util (Helper)\n\nfn a {\n  Helper.<caret>\n}\n"))
    }

    fun testInstanceMethodsOfTypesInScope() {
        assertEquals(listOf("value"), complete("import app.util (Helper)\n\nfn a(h: Helper) {\n  h.va<caret>\n}\n"))
    }

    fun testLocalsAndParametersInScope() {
        val items = complete("fn a(count: Int) {\n  let total = 1\n  if x {\n    let gone = 2\n  }\n  <caret>\n  let later = 3\n}\n")
        assertTrue(items.toString(), items.containsAll(listOf("count", "total")))
        assertTrue(items.toString(), "gone" !in items && "later" !in items)
    }

    fun testUntypedReceiversPreferTypesTheFileMentions() {
        // x's type is unknown; Helper is mentioned and Other isn't, so only Helper's methods are offered.
        assertEquals(listOf("value"), complete("import app.util (Helper)\n\nfn a(x) -> Helper {\n  x.<caret>\n}\n"))
    }

    fun testAKnownLocalTypeNarrowsMembers() {
        // Other is mentioned too, but h is a Helper.
        assertEquals(listOf("value"), complete("import app.util (Helper)\nimport app.other (Other)\n\nfn a(o: Other) {\n  let h = Helper.new\n  h.<caret>\n}\n"))
    }

    fun testSelfMembersAreTheEnclosingTypes() {
        val util = "type pub Helper {\n  fn pub static new -> Helper {\n    Helper()\n  }\n\n  fn pub value -> Int {\n    self.<caret>\n  }\n}\n\nfn pub helper -> Int {\n  1\n}\n"
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("src/app/util.inko", util.replace("<caret>", "")).virtualFile)
        myFixture.editor.caretModel.moveToOffset(util.indexOf("<caret>"))
        myFixture.completeBasic()
        assertEquals(listOf("value"), myFixture.lookupElementStrings)
    }

    fun testModuleMembersThroughAnAlias() {
        assertEquals(listOf("Helper", "helper"), complete("import app.util\n\nfn a {\n  util.<caret>\n}\n").sorted())
    }

    // A local named like the module hides it: these are a Helper's members, not the module's.
    fun testALocalHidesTheModuleItIsNamedLike() {
        val members = complete("import app.util (Helper)\nimport app.util\n\nfn a {\n  [Helper.new].each(fn (util: Helper) { util.<caret> })\n}\n")
        assertTrue(members.toString(), "value" in members)
        assertFalse(members.toString(), "helper" in members)
    }

    fun testFieldsFromTheFileItself() {
        assertEquals(listOf("@count", "@name"), complete("type A {\n  let @count: Int\n  let @name: String\n\n  fn b {\n    @<caret>\n  }\n}\n").sorted())
    }

    // Only what typing needs: the names in scope are Cuttlebone's.
    fun testKeywordsAndLocals() {
        val items = complete("import app.util (helper)\n\ntype Local {}\n\nfn a(count: Int) {\n  <caret>\n}\n")
        assertTrue(items.toString(), items.containsAll(listOf("let", "match", "count")))
        assertTrue(items.toString(), "Local" !in items && "helper" !in items)
    }

    fun testShowsWhereAndTheSignature() {
        complete("import app.util (Helper)\n\nfn a {\n  Helper.<caret>\n}\n")
        val presentation = LookupElementPresentation.renderElement(myFixture.lookupElements!!.single())
        assertEquals(" -> Helper", presentation.tailText)
        assertEquals("app.util.Helper", presentation.typeText)
    }

    fun testSelectingAnItemInsertsIt() {
        complete("import app.util (Helper)\n\nfn a(h: Helper) {\n  h.v<caret>\n}\n")
        myFixture.finishLookup('\n')
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains("  h.value\n"))
    }

    fun testWorksWithoutInko() {
        useFakeInko(FakeInkoCli(available = false))
        assertTrue(complete("fn a {\n  ma<caret>\n}\n").contains("match"))
    }

    fun testContexts() {
        assertEquals(InkoCompletionContext.ImportPath("std.st"), InkoCompletionContext.of("import std.st"))
        assertEquals(InkoCompletionContext.ImportedSymbol("std.stdio", "Std"), InkoCompletionContext.of("import std.stdio (\n  Stdout,\n  Std"))
        assertEquals(InkoCompletionContext.Field("@co"), InkoCompletionContext.of("  @co"))
        assertEquals(InkoCompletionContext.Field("@"), InkoCompletionContext.of("  @"))
        assertEquals(InkoCompletionContext.Bare("emp"), InkoCompletionContext.of("let x = emp"))
        val member = InkoCompletionContext.of("foo.bar") as InkoCompletionContext.Member
        assertEquals("foo" to "bar", member.receiver to member.prefix)
    }


    private fun answering(vararg items: org.eclipse.lsp4j.CompletionItem) = stubCuttlebone { method, file, _ ->
        if (method == "textDocument/completion") CuttleboneQueries.Answer(items.toList(), CuttleboneStatus(method, file.url, "fresh", null, 1.0)) else null
    }

    private fun item(label: String, kind: org.eclipse.lsp4j.CompletionItemKind, detail: String? = null, module: String? = null, sortText: String? = null) =
        org.eclipse.lsp4j.CompletionItem(label).apply {
            this.kind = kind
            this.detail = detail
            this.sortText = sortText
            module?.let { labelDetails = org.eclipse.lsp4j.CompletionItemLabelDetails().apply { description = it } }
        }

    fun testCuttlebonesItemsWithWhereAndTheSignature() {
        answering(item("value", org.eclipse.lsp4j.CompletionItemKind.Method, "fn pub value -> Int", "app.util"), item("valid", org.eclipse.lsp4j.CompletionItemKind.Variable, "let valid: Bool"))
        assertEquals(listOf("valid", "value"), complete("fn a(h: Helper) {\n  h.va<caret>\n}\n").sorted())
        val presentation = LookupElementPresentation.renderElement(myFixture.lookupElements!!.single { it.lookupString == "value" })
        assertEquals(" -> Int", presentation.tailText)
        assertEquals("app.util", presentation.typeText)
    }

    fun testCuttlebonesGroupsInItsOrder() {
        answering(
            item("Zebra", org.eclipse.lsp4j.CompletionItemKind.Class, sortText = "3Zebra"),
            item("total", org.eclipse.lsp4j.CompletionItemKind.Variable, sortText = "0total"),
            item("let", org.eclipse.lsp4j.CompletionItemKind.Keyword, sortText = "4let"),
        )
        assertEquals(listOf("total", "Zebra", "let"), complete("fn a {\n  <caret>\n}\n"))
    }

    fun testChoosingANameToImportMakesItsImport() {
        answering(item("LIMIT", org.eclipse.lsp4j.CompletionItemKind.Constant, "let pub LIMIT = 10", "app.other").apply {
            additionalTextEdits = listOf(org.eclipse.lsp4j.TextEdit(org.eclipse.lsp4j.Range(org.eclipse.lsp4j.Position(0, 0), org.eclipse.lsp4j.Position(0, 0)), "import app.other (LIMIT)\n\n"))
        }, item("LIMB", org.eclipse.lsp4j.CompletionItemKind.Constant))
        complete("fn main {\n  LIM<caret>\n}\n")
        myFixture.lookup!!.currentItem = myFixture.lookup!!.items.single { it.lookupString == "LIMIT" }
        myFixture.finishLookup('\n')
        assertEquals("import app.other (LIMIT)\n\nfn main {\n  LIMIT\n}\n", myFixture.editor.document.text)
    }

    // An import's names are matched by the name being typed, not the whole path.
    fun testAModulesNextNameInAnImport() {
        answering(item("util", org.eclipse.lsp4j.CompletionItemKind.Module, "app.util"), item("other", org.eclipse.lsp4j.CompletionItemKind.Module, "app.other"))
        assertEquals(listOf("util"), complete("import app.ut<caret>\n"))
    }
}
