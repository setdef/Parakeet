package com.setdef.parakeet

/**
 * Expression Type (⌃⇧P) without the compiler's type: it says why. The compiler's
 * types are in CuttleboneFeaturesTest and CuttleboneIntegrationTest.
 */
class InkoExpressionTypeTest : InkoTestCase(onDisk = true) {
    private val text = "fn main {\n  make(1).size\n}\n"

    /** What ⌃⇧P says for `make(1)`. */
    private fun typeOfTheCall(): String {
        val file = myFixture.addFileToProject("src/main.inko", text)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val provider = InkoExpressionTypeProvider()
        val expression = provider.getExpressionsAt(myFixture.file.findElementAt(text.indexOf("make"))!!).first { it.text == "make(1)" }
        return provider.getInformationHint(expression)
    }

    fun testWithoutCuttleboneItSaysItNeedsIt() =
        assertEquals("Unknown: Expression Type needs Cuttlebone, which isn't running", typeOfTheCall())

    fun testInCodeCuttleboneCantCheckItSaysSo() {
        cuttleboneCantCheckTheCode()
        assertEquals("Unknown: Cuttlebone can't type check the code here as it is", typeOfTheCall())
    }

    fun testWithoutATypeForExactlyThatExpressionItsUnknown() {
        // Hover there gives an expression of another range: no guess from it.
        Compiler().hover(At("src/main.inko", text, "make(1)"), "fn (Int) -> Int", length = 4).install()
        assertEquals("Unknown: the compiler has no type for this expression", typeOfTheCall())
    }

    fun testTheCompilersTypeForExactlyThatExpression() {
        Compiler().hover(At("src/main.inko", text, "make(1)"), "Array[Int]").install()
        assertEquals("Array[Int]", typeOfTheCall())
    }
}
