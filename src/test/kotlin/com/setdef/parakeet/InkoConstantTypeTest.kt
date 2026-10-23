package com.setdef.parakeet

/**
 * A constant's type, from `inko doc`'s signature `let pub LIMIT: Int`
 * (compiler/src/docs.rs). AUDIT.md finding 1.14.
 */
class InkoConstantTypeTest : InkoTestCase(onDisk = true) {
    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("inko.pkg", "")
        myFixture.addFileToProject("src/app/limits.inko", "let pub LIMIT = 10\nlet pub NAMES = ['a']\n")
        useFakeInko(FakeInkoDoc.cli { mirror ->
            val file = mirror.resolve("src/app/limits.inko")
            listOf(
                FakeInkoDoc.module("app.limits", file, constants = listOf(
                    FakeInkoDoc.member("LIMIT", file, 1, 1, signature = "let pub LIMIT: Int"),
                    FakeInkoDoc.member("NAMES", file, 2, 1, signature = "let pub NAMES: Array[String]"),
                )),
                FakeInkoDoc.module("std.int", mirror.resolve("std/int.inko"), types = listOf(FakeInkoDoc.type("Int", 1))),
            )
        })
    }

    private fun typeAt(text: String, marker: String): String? {
        val file = myFixture.addFileToProject("src/main.inko", text)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val start = text.indexOf(marker)
        return InkoExpressionTypes.of(myFixture.file, com.intellij.openapi.util.TextRange(start, start + marker.length))
    }

    fun testAConstantsTypeComesFromItsSignature() {
        val text = "import app.limits (LIMIT, NAMES)\n\nfn twice -> Int {\n  LIMIT * 2 + NAMES.size\n}\n"
        assertEquals("Int", typeAt(text, "LIMIT"))
        assertEquals("Array[String]", typeAt(text, "NAMES"))
    }
}
