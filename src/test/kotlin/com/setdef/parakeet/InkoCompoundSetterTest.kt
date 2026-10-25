package com.setdef.parakeet

import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.safeDelete.SafeDeleteHandler

/**
 * Compound setter calls: `c.value += 1` is `binary_assign_setter` (ast/src/parser.rs, `call_with_receiver`),
 * which the compiler lowers to the setter `value=` called with the getter `value`'s result plus 1
 * (compiler/src/hir.rs, `binary_assign_setter`). So it's a use of both. Cuttlebone's `definition` there is the
 * setter, and its `references` of either accessor list it.
 */
class InkoCompoundSetterTest : InkoTestCase(onDisk = true) {
    private val counter = "type pub Counter {\n  let mut @value: Int\n\n  fn pub mut value=(v: Int) {\n    @value = v\n  }\n\n  fn pub value -> Int {\n    @value\n  }\n}\n"
    private val main = "import app.counter (Counter)\n\nfn bump(c: mut Counter) {\n  c.value += 1\n}\n\nfn set(c: mut Counter) {\n  c.value = 2\n}\n\nfn get(c: ref Counter) -> Int {\n  c.value\n}\n"

    private fun inCounter(marker: String) = At("src/app/counter.inko", counter, marker)
    private fun inMain(text: String, marker: String) = At("src/main.inko", text, marker)

    /** The project, with [text] as main.inko. */
    private fun project(text: String = main) {
        putTempDirInProjectContent()
        myFixture.addFileToProject("inko.pkg", "")
        // An `inko doc` that documents nothing: definitions come from the files' own source.
        useFakeInko(FakeInkoCli { FakeInkoCli.ok(stderr = "[]") })
        myFixture.addFileToProject("src/app/counter.inko", counter)
        myFixture.addFileToProject("src/main.inko", text)
    }

    /**
     * Cuttlebone's answers for [text] (main.inko), as the real one gives them: the compound assignment's
     * definition is the setter. Its references have no answer (as when a file in the project doesn't parse), so
     * a search resolves each candidate.
     */
    private fun withoutReferences(text: String = main) {
        val compiler = Compiler()
            .resolves(inCounter("@value = v"), inCounter("@value:"))
            .resolves(inCounter("@value\n"), inCounter("@value:"))
            .resolves(inMain(text, "Counter)"), inCounter("Counter {"))
            .resolves(inMain(text, "value += 1"), inCounter("value="))
            .editing(inMain(text, "import"))
        if ("value = 2" in text) compiler.resolves(inMain(text, "value = 2"), inCounter("value="))
        if ("c.value\n}" in text) compiler.resolves(At("src/main.inko", text, "value\n}"), inCounter("value ->"))
        compiler.install()
    }

    // The method, not the field `@value`, which is also named `value`.
    private fun declaration(path: String, name: String): InkoDeclaration {
        val file = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir(path))!!
        return PsiTreeUtil.findChildrenOfType(file, InkoDeclaration::class.java).single { it.text == name }
    }

    private fun setter() = declaration("src/app/counter.inko", "value=")
    private fun getter() = declaration("src/app/counter.inko", "value")

    private fun usages(declaration: InkoDeclaration) = myFixture.findUsages(declaration).map { it.element!!.textRange.startOffset }.sorted()

    private fun nameAt(text: String, marker: String): InkoName =
        PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(text.indexOf(marker)), InkoName::class.java, false)!!

    fun testWithoutReferencesSearchesCountACompoundAssignmentForTheGetterAndTheSetter() {
        project()
        withoutReferences()
        assertEquals(listOf(main.indexOf("value += 1"), main.indexOf("value = 2")), usages(setter()))
        assertEquals(listOf(main.indexOf("value += 1"), main.indexOf("value\n}")), usages(getter()))
    }

    fun testWithoutReferencesSafeDeletingAGetterOnlyACompoundAssignmentUsesWarns() {
        val text = "import app.counter (Counter)\n\nfn bump(c: mut Counter) {\n  c.value += 1\n}\n"
        project(text)
        withoutReferences(text)
        try {
            SafeDeleteHandler.invoke(project, arrayOf(getter()), true)
            fail("expected a usage conflict")
        } catch (e: BaseRefactoringProcessor.ConflictsInTestsException) {
            assertEquals("Usage of method value that is not safe to delete.", e.messages.single())
        }
    }

    fun testRenamingTheSetterSaysACompoundAssignmentAlsoCallsTheGetter() {
        project()
        withoutReferences()
        try {
            myFixture.renameElement(setter(), "amount=")
            fail("expected a conflict")
        } catch (e: BaseRefactoringProcessor.ConflictsInTestsException) {
            assertEquals(listOf("`c.value += 1` at main.inko:4 also calls `value`, which isn't renamed, so it would call `amount` instead."), e.messages.toList())
        }
    }

    fun testRenamingTheGetterSaysACompoundAssignmentAlsoCallsTheSetter() {
        project()
        withoutReferences()
        try {
            myFixture.renameElement(getter(), "amount")
            fail("expected a conflict")
        } catch (e: BaseRefactoringProcessor.ConflictsInTestsException) {
            assertEquals(listOf("`c.value += 1` at main.inko:4 also calls `value=`, which isn't renamed, so it would call `amount=` instead."), e.messages.toList())
        }
    }

    fun testRenamingAnAccessorAnywayRenamesTheCompoundAssignment() {
        project()
        withoutReferences()
        BaseRefactoringProcessor.ConflictsInTestsException.withIgnoredConflicts<Throwable> { myFixture.renameElement(setter(), "amount=") }
        val text = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("src/main.inko"))!!.text
        assertEquals(main.replace("c.value += 1", "c.amount += 1").replace("c.value = 2", "c.amount = 2"), text)
    }

    // Parakeet's own reading of the code, which stands in only where Cuttlebone can't check it, agrees with Cuttlebone.
    fun testInCodeBeingEditedACompoundAssignmentResolvesToTheSetter() {
        project()
        cuttleboneCantCheckTheCode()
        myFixture.configureFromTempProjectFile("src/main.inko")
        assertEquals(listOf("value="), InkoResolver.resolve(nameAt(main, "value += 1")).map { it.text })
    }

    fun testInCodeBeingEditedFindUsagesCountsACompoundAssignmentForTheGetterAndTheSetter() {
        project()
        cuttleboneCantCheckTheCode()
        assertEquals(listOf(main.indexOf("value += 1"), main.indexOf("value = 2")), usages(setter()))
        assertEquals(listOf(main.indexOf("value += 1"), main.indexOf("value\n}")), usages(getter()))
    }

    // As Cuttlebone's highlights: from the compound assignment, the setter's uses; from a read, the getter's, which include it.
    fun testInCodeBeingEditedHighlightingCountsACompoundAssignmentForTheGetterAndTheSetter() {
        project()
        cuttleboneCantCheckTheCode()
        myFixture.configureFromTempProjectFile("src/main.inko")
        fun highlighted(marker: String) = InkoUsages.inFile(nameAt(main, marker)).map { it.first.textRange.startOffset }.sorted()
        assertEquals(listOf(main.indexOf("value += 1"), main.indexOf("value = 2")), highlighted("value += 1"))
        assertEquals(listOf(main.indexOf("value += 1"), main.indexOf("value\n}")), highlighted("value\n}"))
    }
}
