package com.setdef.parakeet

import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext

/**
 * Setters: `fn value=` is called by `recv.value = x` (ast/src/parser.rs:
 * `method_name` appends the `=`, and `call_with_receiver` reads
 * `recv.name = value` as `assign_setter`). AUDIT.md finding 1.6.
 */
class InkoSetterTest : InkoTestCase(onDisk = true) {
    private val counter = "type pub Counter {\n  let mut @value: Int\n\n  fn pub mut value=(v: Int) {\n    @value = v\n  }\n\n  fn pub value -> Int {\n    @value\n  }\n}\n"
    private val main = "import app.counter (Counter)\n\nfn bump(c: mut Counter) {\n  c.value = 2\n  c.value=3\n  let x = c.value\n}\n"

    override fun setUp() {
        super.setUp()
        // Cuttlebone's answers for this code, as the compiler resolves it.
        fun inCounter(marker: String) = At("src/app/counter.inko", counter, marker)
        fun inMain(marker: String) = At("src/main.inko", main, marker)
        Compiler()
            .resolves(inCounter("@value = v"), inCounter("@value:"))
            .resolves(inCounter("@value\n"), inCounter("@value:"))
            .resolves(inMain("Counter)"), inCounter("Counter {"))
            .resolves(inMain("value = 2"), inCounter("value=("))
            .resolves(inMain("value=3"), inCounter("value=("))
            .resolves(inMain("value\n}"), inCounter("value ->"))
            .install()
        putTempDirInProjectContent()
        myFixture.addFileToProject("inko.pkg", "")
        // An `inko doc` that documents nothing: definitions come from the files' own source.
        useFakeInko(FakeInkoCli { FakeInkoCli.ok(stderr = "[]") })
        myFixture.addFileToProject("src/app/counter.inko", counter)
        myFixture.addFileToProject("src/main.inko", main)
    }

    private fun setter(): InkoDeclaration {
        val file = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("src/app/counter.inko"))!!
        return PsiTreeUtil.findChildrenOfType(file, InkoDeclaration::class.java).single { it.name == "value=" }
    }

    private fun mainText() = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("src/main.inko"))!!.text

    fun testInCodeBeingEditedAnAssignmentResolvesToTheSetter() {
        // Parakeet's own reading of the code, which stands in only where Cuttlebone can't check it.
        cuttleboneCantCheckTheCode()
        myFixture.configureFromTempProjectFile("src/main.inko")
        fun targets(marker: String): List<String> {
            val name = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(main.indexOf(marker)), InkoName::class.java, false)!!
            return InkoResolver.resolve(name).map { it.text }
        }
        assertEquals(listOf("value="), targets("value = 2"))
        assertEquals(listOf("value="), targets("value=3"))
        // A read calls the getter, not the setter.
        assertFalse(targets("value\n}").contains("value="))
    }

    fun testFindUsagesOfASetterFindsItsAssignments() {
        val usages = myFixture.findUsages(setter()).map { it.element!!.textRange.startOffset }.sorted()
        assertEquals(listOf(main.indexOf("value = 2"), main.indexOf("value=3")), usages)
    }

    fun testRenamingASetterRenamesItsAssignments() {
        // Only the setter: the offer to rename the getter too is declined.
        com.intellij.openapi.ui.TestDialogManager.setTestDialog(com.intellij.openapi.ui.TestDialog.NO, testRootDisposable)
        myFixture.renameElement(setter(), "amount=")
        assertEquals(main.replace("c.value = 2", "c.amount = 2").replace("c.value=3", "c.amount=3"), mainText())
    }

    fun testASettersNewNameEndsInEquals() {
        val validator = InkoRenameInputValidator()
        assertTrue(validator.isInputValid("amount=", setter(), ProcessingContext()))
        assertFalse(validator.isInputValid("amount", setter(), ProcessingContext()))
    }
}
