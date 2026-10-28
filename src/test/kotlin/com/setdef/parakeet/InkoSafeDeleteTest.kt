package com.setdef.parakeet

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.safeDelete.SafeDeleteHandler

class InkoSafeDeleteTest : InkoTestCase(onDisk = true) {
    override fun setUp() {
        super.setUp()
        // Cuttlebone, answering what each test's code needs (and that nothing is anywhere else).
        Compiler().install()
    }

    private fun declaration(marker: String): InkoDeclaration =
        PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(myFixture.file.text.indexOf(marker)), InkoDeclaration::class.java, false)!!

    private fun safeDelete(text: String, marker: String): String {
        myFixture.configureByText("main.inko", text)
        SafeDeleteHandler.invoke(project, arrayOf(declaration(marker)), true)
        return myFixture.editor.document.text
    }

    fun testDeletesAnUnusedMethodWithItsDocs() = assertEquals(
        "type A {\n  fn kept {}\n}\n",
        safeDelete("type A {\n  # Does b.\n  # Really.\n  fn b -> Int {\n    1\n  }\n  fn kept {}\n}\n", "b ->"),
    )

    fun testDeletesAnUnusedLocalStatement() = assertEquals(
        "fn a {\n  bar\n}\n",
        safeDelete("fn a {\n  let unused = foo\n    .baz\n  bar\n}\n", "unused"),
    )

    fun testDeletesTypesFieldsAndConstructors() {
        assertEquals("type A {\n  let @b: Int\n}\n", safeDelete("type A {\n  let @a: Int\n  let @b: Int\n}\n", "@a"))
        assertEquals("type enum E {\n  case B\n}\n", safeDelete("type enum E {\n  case A(Int)\n  case B\n}\n", "A("))
        assertEquals("fn x {}\n", safeDelete("type Gone {}\nfn x {}\n", "Gone"))
    }

    fun testWarnsBeforeDeletingSomethingUsed() {
        // Usages resolve through the inko doc index, as in a real project.
        putTempDirInProjectContent()
        val source = "fn helper -> Int {\n  1\n}\n\nfn a -> Int {\n  helper\n}\n"
        useFakeInko(FakeInkoCli { FakeInkoCli.ok(stderr = "[]") })
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("src/main.inko", source).virtualFile)
        Compiler().resolves(At("src/main.inko", source, "helper\n}"), At("src/main.inko", source, "helper ->")).install()
        try {
            SafeDeleteHandler.invoke(project, arrayOf(declaration("helper ->")), true)
            fail("expected a usage conflict")
        } catch (e: BaseRefactoringProcessor.ConflictsInTestsException) {
            assertEquals("Usage of function helper that is not safe to delete.", e.messages.single())
        }
    }

    // kvi's `run`, deleted while `match run` still used it: Cuttlebone's check crashed, so the search had no
    // answer, and found no use. A name the compiler couldn't decide may be one, so it isn't safe to delete.
    fun testWithNoAnswerForAPossibleUseItRefuses() {
        putTempDirInProjectContent()
        val source = "fn helper -> Int {\n  1\n}\n\nfn a -> Int {\n  helper\n}\n"
        useFakeInko(FakeInkoCli { FakeInkoCli.ok(stderr = "[]") })
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("src/main.inko", source).virtualFile)
        Compiler().crashed().install()
        try {
            SafeDeleteHandler.invoke(project, arrayOf(declaration("helper ->")), true)
            fail("expected a conflict, not:\n${myFixture.editor.document.text}")
        } catch (e: BaseRefactoringProcessor.ConflictsInTestsException) {
            assertEquals(listOf("Cuttlebone has no answer for `helper` at main.inko:6, so it may be a use."), e.messages.toList())
        }
    }

    fun testParametersCannotBeSafeDeleted() {
        myFixture.configureByText("main.inko", "fn a(x: Int) {}\n")
        assertFalse(InkoRefactoringSupportProvider().isSafeDeleteAvailable(declaration("x:")))
    }
}
