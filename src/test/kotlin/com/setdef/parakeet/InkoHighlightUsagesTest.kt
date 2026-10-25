package com.setdef.parakeet

import com.intellij.psi.util.PsiTreeUtil

/** Highlighting the occurrences that mean the same thing as the name at the caret. */
class InkoHighlightUsagesTest : InkoTestCase(onDisk = true) {
    override fun setUp() {
        super.setUp()
        // Parakeet's own reading of the code, which stands in only where Cuttlebone can't check it.
        cuttleboneCantCheckTheCode()
        myFixture.addFileToProject("src/app/util.inko", "type pub Helper {}\n")
        useFakeInko(FakeInkoDoc.cli { mirror ->
            listOf(FakeInkoDoc.module("app.util", mirror.resolve("src/app/util.inko"), types = listOf(FakeInkoDoc.type("Helper", 1))))
        })
    }

    /** [text] with highlights at <caret>'s name marked: «=declaration» and «read». */
    private fun highlighted(text: String): String {
        val caret = text.indexOf("<caret>")
        val source = text.replace("<caret>", "")
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("src/main.inko", source).virtualFile)
        val name = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(caret), InkoName::class.java, false)!!
        val handler = InkoHighlightUsagesHandlerFactory().createHighlightUsagesHandler(myFixture.editor, myFixture.file, name) as InkoHighlightUsagesHandler
        handler.computeUsages(handler.targets)
        val marks = handler.writeUsages.map { it to "=" } + handler.readUsages.map { it to "" }
        return marks.sortedByDescending { it.first.startOffset }.fold(source) { result, (range, kind) ->
            result.substring(0, range.startOffset) + "«$kind" + result.substring(range.startOffset, range.endOffset) + "»" + result.substring(range.endOffset)
        }
    }

    fun testALocalAndItsUses() = assertEquals(
        "fn a {\n  let «=count» = 1\n  «count» + «count»\n}\n\nfn b {\n  let count = 2\n}\n",
        highlighted("fn a {\n  let count = 1\n  <caret>count + count\n}\n\nfn b {\n  let count = 2\n}\n"),
    )

    fun testAParameterFromItsDeclaration() = assertEquals(
        "fn a(«=name»: String) {\n  «name»\n}\n\nfn b(name: Int) {}\n",
        highlighted("fn a(<caret>name: String) {\n  name\n}\n\nfn b(name: Int) {}\n"),
    )

    fun testAFunctionDeclaredInTheFile() = assertEquals(
        "fn «=helper» -> Int {\n  1\n}\n\nfn a {\n  «helper»\n  x.helper\n}\n",
        highlighted("fn helper -> Int {\n  1\n}\n\nfn a {\n  <caret>helper\n  x.helper\n}\n"),
    )

    fun testAnImportedTypeIncludingTheImport() = assertEquals(
        "import app.util («Helper»)\n\nfn a(h: «Helper») -> «Helper» {\n  «Helper»()\n}\n",
        highlighted("import app.util (Helper)\n\nfn a(h: Helper) -> Helper {\n  <caret>Helper()\n}\n"),
    )

    fun testFieldsWithinTheirType() = assertEquals(
        "type A {\n  let «=@count»: Int\n\n  fn b -> Int {\n    «@count»\n  }\n}\n\ntype B {\n  let @count: Int\n}\n",
        highlighted("type A {\n  let @count: Int\n\n  fn b -> Int {\n    <caret>@count\n  }\n}\n\ntype B {\n  let @count: Int\n}\n"),
    )

    // As Cuttlebone marks them (its Write): a local's assignments, plain, replacing (`:=`) or compound, are writes.
    fun testALocalsAssignmentsAreWrites() = assertEquals(
        "fn a -> Int {\n  let mut «=x» = 1\n  «=x» = 2\n  «=x» += 3\n  «=x» := 4\n  «x» == 5\n  «x» <= 6\n  «x»\n}\n",
        highlighted("fn a -> Int {\n  let mut x = 1\n  x = 2\n  x += 3\n  x := 4\n  x == 5\n  x <= 6\n  <caret>x\n}\n"),
    )

    fun testEveryCompoundAssignmentIsAWrite() {
        for (operator in listOf("+=", "-=", "*=", "/=", "%=", "**=", "<<=", ">>=", ">>>=", "|=", "&=", "^=")) {
            assertEquals(
                operator,
                "fn a -> Int {\n  let mut «=x» = 1\n  «=x» $operator 2\n  «x»\n}\n",
                highlighted("fn a -> Int {\n  let mut x = 1\n  x $operator 2\n  <caret>x\n}\n"),
            )
        }
    }

    fun testAFieldsAssignmentsAreWrites() = assertEquals(
        "type A {\n  let mut «=@n»: Int\n\n  fn mut b -> Int {\n    «=@n» = 1\n    «=@n» -= 2\n    «@n»\n  }\n}\n",
        highlighted("type A {\n  let mut @n: Int\n\n  fn mut b -> Int {\n    @n = 1\n    @n -= 2\n    <caret>@n\n  }\n}\n"),
    )
}
