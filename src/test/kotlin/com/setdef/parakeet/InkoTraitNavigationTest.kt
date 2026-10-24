package com.setdef.parakeet

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.searches.DefinitionsScopedSearch

/** Go to Implementation (⌥⌘B) and Go to Super Method (⌘U), from inko doc's trait data. */
class InkoTraitNavigationTest : InkoTestCase(onDisk = true) {
    private val util = """
        |trait pub Show {
        |  fn pub show -> String
        |}
        |
        |type pub Helper {}
        |
        |impl Show for Helper {
        |  fn pub show -> String {
        |    'h'
        |  }
        |}
        |
        |type pub Other {}
        |
        |impl Show for Other {
        |  fn pub show -> String {
        |    'o'
        |  }
        |}
        |""".trimMargin()

    override fun setUp() {
        super.setUp()
        // Parakeet's own reading of the code, which stands in only where Cuttlebone can't check it.
        cuttleboneCantCheckTheCode()
        myFixture.addFileToProject("src/app/util.inko", util)
        useFakeInko(FakeInkoDoc.cli { mirror ->
            val file = mirror.resolve("src/app/util.inko")
            listOf(FakeInkoDoc.module(
                "app.util", file,
                types = listOf(
                    FakeInkoDoc.type("Helper", 5, instanceMethods = listOf(FakeInkoDoc.member("show", file, 8)), implementedTraits = listOf("app.util" to "Show")),
                    FakeInkoDoc.type("Other", 13, instanceMethods = listOf(FakeInkoDoc.member("show", file, 16)), implementedTraits = listOf("app.util" to "Show")),
                ),
                traits = listOf(FakeInkoDoc.trait(
                    "Show", 1,
                    requiredMethods = listOf(FakeInkoDoc.member("show", file, 2)),
                    implementations = listOf("app.util" to "Helper", "app.util" to "Other"),
                )),
            ))
        })
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("src/app/util.inko"))
    }

    private fun offsetOf(marker: String, nth: Int = 0): Int {
        var index = -1
        repeat(nth + 1) { index = util.indexOf(marker, index + 1) }
        assertTrue("'$marker' #$nth isn't in the file", index >= 0)
        return index
    }

    private fun PsiElement.location(): String {
        val line = containingFile.viewProvider.document!!.getLineNumber(textOffset) + 1
        return "$line:$text"
    }

    private fun implementationsOf(marker: String, nth: Int = 0): List<String> {
        val declaration = myFixture.file.findElementAt(offsetOf(marker, nth))!!.parent as InkoDeclaration
        return DefinitionsScopedSearch.search(declaration).findAll().map { it.location() }.sorted()
    }

    fun testATraitsImplementationsAreItsTypes() = assertEquals(listOf("13:Other", "5:Helper"), implementationsOf("Show {"))

    fun testATraitMethodsImplementationsAreTheImplMethods() = assertEquals(listOf("16:show", "8:show"), implementationsOf("show -> String\n"))

    // ⌥⌘B on a usage resolves it to its declaration first, then searches from there.
    fun testGoToImplementationFromAUsageOfTheTrait() {
        val usage = myFixture.file.findElementAt(offsetOf("Show for Helper"))!!.parent as InkoName
        val declaration = usage.reference!!.resolve() as InkoDeclaration
        assertEquals(listOf("13:Other", "5:Helper"), DefinitionsScopedSearch.search(declaration).findAll().map { it.location() }.sorted())
    }

    private fun gotoSuperFrom(marker: String, nth: Int = 0): String {
        myFixture.editor.caretModel.moveToOffset(offsetOf(marker, nth))
        myFixture.performEditorAction("GotoSuperMethod")
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        val offset = editor.caretModel.offset
        return "${editor.document.getLineNumber(offset) + 1}:${editor.document.text.substring(offset).substringBefore(' ')}"
    }

    fun testSuperOfAnImplMethodIsTheTraitMethod() = assertEquals("2:show", gotoSuperFrom("'o'"))

    fun testSuperOfAnImplIsTheTrait() = assertEquals("1:Show", gotoSuperFrom("for Other"))

    fun testSuperOfATypeIsTheTraitItImplements() = assertEquals("1:Show", gotoSuperFrom("Helper {}"))
}
