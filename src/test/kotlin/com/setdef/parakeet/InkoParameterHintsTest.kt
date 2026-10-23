package com.setdef.parakeet

import com.intellij.psi.util.PsiTreeUtil

/** Parameter-name hints, with inko doc output supplied by a fake. */
class InkoParameterHintsTest : InkoTestCase(onDisk = true) {
    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("src/app/util.inko", "fn pub greet(name: String, mut times: Int) {}\n")
        useFakeInko(FakeInkoDoc.cli { mirror ->
            val file = mirror.resolve("src/app/util.inko")
            listOf(
                FakeInkoDoc.module(
                    "app.util", file,
                    methods = listOf(FakeInkoDoc.member("greet", file, 1, 1, signature = "fn pub greet(name: String, mut times: Int)")),
                    types = listOf(
                        FakeInkoDoc.type("A", 1, instanceMethods = listOf(FakeInkoDoc.member("size", file, 1, signature = "fn pub size(unit: Int) -> Int"))),
                        FakeInkoDoc.type("B", 1, instanceMethods = listOf(FakeInkoDoc.member("size", file, 1, signature = "fn pub size(scale: Float) -> Int"))),
                    ),
                ),
            )
        })
    }

    /** The file with each hint written in as `«name:»`. */
    private fun hinted(text: String): String {
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("src/main.inko", text).virtualFile)
        val hints = PsiTreeUtil.collectElements(myFixture.file) { it.isToken(InkoTokens.LPAREN) }.flatMap { InkoParameterHints.at(it) }
        return hints.sortedByDescending { it.first }.fold(text) { result, (offset, name) -> result.substring(0, offset) + "«$name:»" + result.substring(offset) }
    }

    fun testNamesPositionalArguments() = assertEquals(
        "import app.util (greet)\n\nfn a {\n  greet(«name:»'Alice', «times:»f(1, 2))\n}\n",
        hinted("import app.util (greet)\n\nfn a {\n  greet('Alice', f(1, 2))\n}\n"),
    )

    fun testSkipsNamedArgumentsAndMatchingNames() = assertEquals(
        "import app.util (greet)\n\nfn a(name: String) {\n  greet(name, times: 2)\n}\n",
        hinted("import app.util (greet)\n\nfn a(name: String) {\n  greet(name, times: 2)\n}\n"),
    )

    fun testNoHintsWhenTheCalleeIsAmbiguous() {
        val text = "fn a(x) {\n  x.size(1)\n}\n"
        assertEquals(text, hinted(text))
    }

    fun testNoHintsOnDeclarationsOrUnfinishedCalls() {
        val text = "import app.util (greet)\n\nfn greet2(name: String) {\n  greet('a'\n}\n"
        assertEquals(text, hinted(text))
    }

    fun testTheProviderIsRegisteredForInko() {
        myFixture.configureByText("main.inko", "")
        assertNotNull(InkoParameterHintsProvider().createCollector(myFixture.file, myFixture.editor))
    }
}
