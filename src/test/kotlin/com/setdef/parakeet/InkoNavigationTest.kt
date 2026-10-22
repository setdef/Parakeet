package com.setdef.parakeet

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.TempDirTestFixture
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl

/** Go to definition, backed by `inko doc` (needs `inko` installed). */
class InkoNavigationTest : BasePlatformTestCase() {
    override fun createTempDirTestFixture(): TempDirTestFixture = TempDirTestFixtureImpl()

    private val util = """
        |type pub Helper {
        |  let @count: Int
        |
        |  fn pub static new -> Helper {
        |    Helper(count: 0)
        |  }
        |
        |  fn pub value -> Int {
        |    @count
        |  }
        |}
        |
        |fn pub helper -> Int {
        |  42
        |}
        |""".trimMargin()

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("src/app/util.inko", util)
    }

    private fun targets(source: String): List<PsiElement> {
        val file = myFixture.addFileToProject("src/main.inko", source.replace("<caret>", ""))
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(source.indexOf("<caret>"))
        val reference = myFixture.getReferenceAtCaretPositionWithAssertion() as InkoReference
        return reference.multiResolve(false).mapNotNull { it.element }
    }

    private fun PsiElement.location(): String {
        val file = containingFile
        val line = file.viewProvider.document!!.getLineNumber(textOffset) + 1
        return if (this is PsiFile) file.name else "${file.name}:$line:$text"
    }

    private val main = """
        |import app.util (Helper, helper)
        |import app.util
        |
        |type async Main {
        |  fn async main {
        |    let h = Helper.new
        |    h.value
        |    helper
        |    util.helper
        |    let s: String = 'x'
        |  }
        |}
        |""".trimMargin()

    private fun at(marker: String, nth: Int = 0): String {
        var index = -1
        repeat(nth + 1) { index = main.indexOf(marker, index + 1) }
        return main.substring(0, index) + "<caret>" + main.substring(index)
    }

    fun testTypeName() {
        assertEquals(listOf("util.inko:1:Helper"), targets(at("Helper.new")).map { it.location() })
    }

    fun testImportedTypeInImportList() {
        assertEquals(listOf("util.inko:1:Helper"), targets(at("Helper,")).map { it.location() })
    }

    fun testModuleInImportPath() {
        assertEquals(listOf("util.inko"), targets(at("util (")).map { it.location() })
    }

    fun testStaticMethod() {
        assertEquals(listOf("util.inko:4:new"), targets(at("new")).map { it.location() })
    }

    fun testInstanceMethod() {
        assertEquals(listOf("util.inko:8:value"), targets(at("value")).map { it.location() })
    }

    fun testImportedFunction() {
        assertEquals(listOf("util.inko:13:helper"), targets(at("helper\n")).map { it.location() })
    }

    fun testFunctionThroughModuleAlias() {
        assertEquals(listOf("util.inko:13:helper"), targets(at("helper\n    let")).map { it.location() })
    }

    fun testModuleAlias() {
        assertEquals(listOf("util.inko"), targets(at("util.helper")).map { it.location() })
    }

    fun testStandardLibraryType() {
        val locations = targets(at("String")).map { it.location() }
        assertEquals(1, locations.size)
        assertTrue(locations.single(), locations.single().startsWith("string.inko:"))
    }

    fun testLocalVariablesDoNotResolve() {
        assertEmpty(targets(at("h.value")))
    }
}
