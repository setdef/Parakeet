package com.setdef.parakeet

import com.intellij.psi.PsiElement

/** A bare `name` inside a type calls the receiver's own method first, as Inko does, before module methods. */
class InkoBareCallsTest : InkoTestCase(onDisk = true) {
    private val counter = """
        |fn helper -> Int {
        |  1
        |}
        |
        |type Counter {
        |  fn static zero -> Int {
        |    base
        |  }
        |
        |  fn static base -> Int {
        |    0
        |  }
        |
        |  fn helper -> Int {
        |    2
        |  }
        |
        |  fn quad -> Int {
        |    helper * 2
        |  }
        |}
        |
        |fn twice -> Int {
        |  helper * 2
        |}
        |""".trimMargin()

    override fun setUp() {
        super.setUp()
        // The fake first: opening the file indexes the project.
        useFakeInko(FakeInkoDoc.cli { mirror ->
            val path = mirror.resolve("src/app/counter.inko")
            listOf(
                FakeInkoDoc.module(
                    "app.counter", path,
                    methods = listOf(FakeInkoDoc.member("helper", path, 1, 1), FakeInkoDoc.member("twice", path, 23, 1)),
                    types = listOf(FakeInkoDoc.type(
                        "Counter", 5,
                        staticMethods = listOf(FakeInkoDoc.member("zero", path, 6), FakeInkoDoc.member("base", path, 10)),
                        instanceMethods = listOf(FakeInkoDoc.member("helper", path, 14), FakeInkoDoc.member("quad", path, 18)),
                    )),
                ),
            )
        })
        val file = myFixture.addFileToProject("src/app/counter.inko", counter)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
    }

    private fun lineOfTarget(marker: String): List<Int> {
        myFixture.editor.caretModel.moveToOffset(counter.indexOf(marker))
        val reference = myFixture.getReferenceAtCaretPositionWithAssertion() as InkoReference
        return reference.multiResolve(false).mapNotNull { it.element }.map(::line)
    }

    private fun line(element: PsiElement) = element.containingFile.viewProvider.document!!.getLineNumber(element.textOffset) + 1

    fun testInAnInstanceMethodTheTypesMethodWins() = assertEquals(listOf(14), lineOfTarget("helper * 2\n  }\n}"))

    fun testInAStaticMethodItsStaticMethods() = assertEquals(listOf(10), lineOfTarget("base\n"))

    fun testOutsideATypeModuleMethods() = assertEquals(listOf(1), lineOfTarget("helper * 2\n}\n"))
}
