package com.setdef.parakeet

import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.psi.util.PsiTreeUtil

/**
 * Without Cuttlebone, what needs the compiler's answers is unavailable, not half done: searching for a
 * method's uses would find only its imports. Locals and modules, whose uses follow from the code, still work.
 */
class InkoWithoutCuttleboneTest : InkoTestCase() {
    private val text = "fn pub helper -> Int {\n  let total = 1\n  total\n}\n"

    private fun declaration(marker: String): InkoDeclaration {
        myFixture.configureByText("main.inko", text)
        return PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(text.indexOf(marker)), InkoDeclaration::class.java, false)!!
    }

    private fun findUsagesHandler(element: InkoDeclaration) =
        FindUsagesHandlerFactory.EP_NAME.getExtensions(project).firstOrNull { it.canFindUsages(element) }

    fun testFindUsagesOfAMethodIsUnavailable() {
        val method = declaration("helper")
        assertTrue(CuttleboneService.getInstance(project).unavailable)
        assertNull(findUsagesHandler(method))
        assertFalse(InkoFindUsagesProvider().canFindUsagesFor(method))
    }

    fun testRenameAndSafeDeleteOfAMethodAreUnavailable() {
        val method = declaration("helper")
        assertTrue(InkoRenameVeto().value(method))
        assertFalse(InkoRefactoringSupportProvider().isSafeDeleteAvailable(method))
    }

    fun testALocalsUsesStillWork() {
        val local = declaration("total =")
        assertTrue(InkoFindUsagesProvider().canFindUsagesFor(local))
        assertFalse(InkoRenameVeto().value(local))
    }
}
