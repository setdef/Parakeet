package com.setdef.parakeet

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.searches.DefinitionsScopedSearch

/** Go to Implementation (⌥⌘B) and Go to Super Method (⌘U), from Cuttlebone's answers (the type database's). */
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

    private fun at(marker: String, nth: Int = 0) = At("src/app/util.inko", util, marker, nth = nth)

    /** Cuttlebone's answers for util.inko: what implements Show and its method, and what each implementation implements. */
    private fun traits(implementations: List<At> = listOf(at("show -> String {"), at("show -> String {", nth = 1))) = Compiler()
        .resolves(at("Show for Helper"), at("Show {"))
        .resolves(at("Show for Other"), at("Show {"))
        .answers("textDocument/implementation", at("Show {")) { listOf(at("Helper {}"), at("Other {}")).map(::location) }
        .answers("textDocument/implementation", at("show -> String\n")) { implementations.map(::location) }
        .answers("textDocument/declaration", at("show -> String {")) { listOf(location(at("show -> String\n"))) }
        .answers("textDocument/declaration", at("show -> String {", nth = 1)) { listOf(location(at("show -> String\n"))) }
        .answers("typeHierarchy/supertypes", at("Helper {}")) { listOf(location(at("Show {"))) }

    override fun setUp() {
        super.setUp()
        traits().install()
        myFixture.addFileToProject("src/app/util.inko", util)
        useFakeInko(FakeInkoCli { FakeInkoCli.ok(stderr = "[]") })
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

    // inko-syntax's `impl Lexer for DummyLexer` and openflow's test drivers: an implementation in `test/` is one
    // Cuttlebone's type database has too.
    fun testATraitMethodsImplementationsInTestsToo() {
        putTempDirInProjectContent()
        val test = "import app.util (Show)\n\ntype Fake {}\n\nimpl Show for Fake {\n  fn pub show -> String {\n    'f'\n  }\n}\n"
        myFixture.addFileToProject("test/app/test_util.inko", test)
        traits(listOf(at("show -> String {"), at("show -> String {", nth = 1), At("test/app/test_util.inko", test, "show"))).install()
        val declaration = myFixture.file.findElementAt(offsetOf("show -> String\n"))!!.parent as InkoDeclaration
        val found = DefinitionsScopedSearch.search(declaration).findAll().map { "${it.containingFile.name}:${it.location()}" }.sorted()
        assertEquals(listOf("test_util.inko:6:show", "util.inko:16:show", "util.inko:8:show"), found)
    }

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

    private val main = """
        |import app.util (Helper, Show)
        |
        |fn shout[T: Show](value: ref T) -> String {
        |  value.show
        |}
        |
        |fn run(h: Helper) -> String {
        |  h.show
        |}
        |""".trimMargin()

    /** main.inko, with Cuttlebone's answers: `value.show` calls the trait's method, `h.show` Helper's. */
    private fun withMain() {
        putTempDirInProjectContent()
        myFixture.addFileToProject("src/main.inko", main)
        val traitMethod = At("src/app/util.inko", util, "show -> String\n")
        traits()
            .resolves(At("src/main.inko", main, "show\n}\n\nfn"), traitMethod)
            .resolves(At("src/main.inko", main, "show\n}\n", nth = 1), At("src/app/util.inko", util, "show -> String {"))
            .resolves(At("src/main.inko", main, "Show]"), At("src/app/util.inko", util, "Show {"))
            .resolves(At("src/main.inko", main, "Helper)"), At("src/app/util.inko", util, "Helper {"))
            .install()
    }

    // A call on a type that implements the trait calls the implementation, which the compiler gives an id of its
    // own: Find Usages of the trait's method counts its implementations' calls too.
    fun testFindUsagesOfATraitMethodIncludesItsImplementationsCalls() {
        withMain()
        val traitMethod = myFixture.file.findElementAt(offsetOf("show -> String\n"))!!.parent as InkoDeclaration
        val usages = myFixture.findUsages(traitMethod).map { "${it.element!!.containingFile.name}:${it.element!!.textOffset}" }.sorted()
        assertEquals(listOf("main.inko:${main.indexOf("show\n}\n\nfn")}", "main.inko:${main.lastIndexOf("show")}").sorted(), usages)
    }

    // Where references have no answer, each call is resolved, and the same rule counts the implementations' calls.
    fun testWithoutReferencesATraitMethodStillIncludesItsImplementationsCalls() {
        withMain()
        InkoCompilerReferences.getInstance(project).eachCandidate = true
        try {
            val traitMethod = myFixture.file.findElementAt(offsetOf("show -> String\n"))!!.parent as InkoDeclaration
            val usages = myFixture.findUsages(traitMethod).map { "${it.element!!.containingFile.name}:${it.element!!.textOffset}" }.sorted()
            assertEquals(listOf("main.inko:${main.indexOf("show\n}\n\nfn")}", "main.inko:${main.lastIndexOf("show")}").sorted(), usages)
        } finally {
            InkoCompilerReferences.getInstance(project).eachCandidate = false
        }
    }

    // A search for the trait itself is only its own: its implementing types are what Go to Implementation lists,
    // not uses of it. In inko-syntax, each lexer's `type Lexer` implements the trait `Lexer`, so Find Usages of
    // the trait listed every use of those types, and renaming the trait would have renamed them.
    fun testASearchForATraitDoesntCountItsImplementingTypes() {
        val trait = myFixture.file.findElementAt(offsetOf("Show {"))!!.parent as InkoDeclaration
        assertEquals(listOf(trait), InkoSearch.alsoImplementations(trait))
    }

    // Otherwise the implementations would no longer implement it.
    fun testRenamingATraitMethodRenamesItsImplementations() {
        withMain()
        myFixture.renameElement(myFixture.file.findElementAt(offsetOf("show -> String\n"))!!.parent as InkoDeclaration, "display")
        assertEquals(util.replace("show", "display"), myFixture.file.text)
        assertEquals(main.replace(".show", ".display"), com.intellij.psi.PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("src/main.inko"))!!.text)
    }

    fun testRenamingAnImplementationRenamesTheTraitsMethod() {
        withMain()
        myFixture.renameElement(myFixture.file.findElementAt(offsetOf("show -> String {"))!!.parent as InkoDeclaration, "display")
        assertEquals(util.replace("show", "display"), myFixture.file.text)
    }
}
