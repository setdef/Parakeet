package com.setdef.parakeet

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.lookup.LookupElementPresentation

/** Completion, with `inko doc` output supplied by a fake. */
class InkoCompletionTest : InkoTestCase(onDisk = true) {
    private var autocomplete = true

    override fun setUp() {
        super.setUp()
        // Parakeet's own reading of the code, which stands in only where Cuttlebone can't check it.
        cuttleboneCantCheckTheCode()
        // Always show the list, even for a single match, so tests can inspect it.
        autocomplete = CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = false
        myFixture.addFileToProject("inko.pkg", "")
        myFixture.addFileToProject("src/app/util.inko", "type pub Helper {\n  fn pub static new -> Helper {\n    Helper()\n  }\n\n  fn pub value -> Int {\n    42\n  }\n}\n\nfn pub helper -> Int {\n  1\n}\n")
        useFakeInko(FakeInkoDoc.cli { mirror ->
            val util = mirror.resolve("src/app/util.inko")
            listOf(
                FakeInkoDoc.module(
                    "app.util", util,
                    methods = listOf(FakeInkoDoc.member("helper", util, 11, 1, signature = "fn pub helper -> Int")),
                    types = listOf(FakeInkoDoc.type(
                        "Helper", 1,
                        staticMethods = listOf(FakeInkoDoc.member("new", util, 2, signature = "fn pub static new -> Helper")),
                        instanceMethods = listOf(FakeInkoDoc.member("value", util, 6, signature = "fn pub value -> Int")),
                    )),
                ),
                FakeInkoDoc.module(
                    "app.other", mirror.resolve("src/app/other.inko"),
                    types = listOf(FakeInkoDoc.type(
                        "Other", 1, instanceMethods = listOf(FakeInkoDoc.member("size", mirror.resolve("src/app/other.inko"), 2)),
                    )),
                    constants = listOf(
                        FakeInkoDoc.member("LIMIT", mirror.resolve("src/app/other.inko"), 5, 1, signature = "let pub LIMIT = 10"),
                        FakeInkoDoc.member("SECRET", mirror.resolve("src/app/other.inko"), 6, 1, signature = "let SECRET = 1") + ("public" to false),
                    ),
                ),
            )
        })
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

    fun testModulePathsInImports() {
        assertEquals(listOf("app.other", "app.util"), complete("import app.<caret>\n").sorted())
    }

    fun testSymbolsInsideImportParentheses() {
        assertEquals(listOf("Helper", "helper", "self"), complete("import app.util (<caret>)\n").sorted())
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

    fun testFieldsFromTheFileItself() {
        assertEquals(listOf("@count", "@name"), complete("type A {\n  let @count: Int\n  let @name: String\n\n  fn b {\n    @<caret>\n  }\n}\n").sorted())
    }

    fun testKeywordsAndNamesInScope() {
        val items = complete("import app.util (helper)\n\ntype Local {}\n\nfn a {\n  <caret>\n}\n")
        assertTrue(items.toString(), items.containsAll(listOf("let", "match", "Local", "helper", "a")))
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

    fun testOffersNamesFromModulesNotYetImported() {
        val items = complete("fn main {\n  Oth<caret>\n}\n")
        assertTrue(items.toString(), "Other" in items)
    }

    fun testChoosingAnUnimportedNameImportsIt() {
        complete("fn main {\n  LIMI<caret>\n}\n")
        // A single match is inserted when the list would only hold it.
        myFixture.lookup?.let { lookup -> lookup.currentItem = lookup.items.single { it.lookupString == "LIMIT" }; myFixture.finishLookup('\n') }
        assertEquals("import app.other (LIMIT)\n\nfn main {\n  LIMIT\n}\n", myFixture.editor.document.text)
    }

    fun testAddsToAnExistingImportOfTheModule() {
        complete("import app.other (Other)\n\nfn main {\n  LIMI<caret>\n}\n")
        myFixture.lookup?.let { lookup -> lookup.currentItem = lookup.items.single { it.lookupString == "LIMIT" }; myFixture.finishLookup('\n') }
        assertEquals("import app.other (Other, LIMIT)\n\nfn main {\n  LIMIT\n}\n", myFixture.editor.document.text)
    }

    fun testDoesNotOfferPrivateNamesFromOtherModules() {
        assertFalse("SECRET" in complete("fn main {\n  SEC<caret>\n}\n"))
    }

    fun testNamesInScopeComeBeforeUnimportedOnes() {
        val items = complete("import app.util (Helper)\n\nfn main {\n  <caret>\n}\n")
        assertTrue(items.toString(), items.indexOf("Helper") < items.indexOf("Other"))
    }

    fun testOnlyOneEntryPerModuleAndName() {
        assertEquals(1, complete("fn main {\n  Oth<caret>\n}\n").count { it == "Other" })
    }
}
