package com.setdef.parakeet

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import java.nio.file.Files
import java.nio.file.Path

/** Go to definition. */
class InkoResolverTest : InkoTestCase(onDisk = true) {
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

    override fun setUp() {
        super.setUp()
        // Parakeet's own reading of the code, which stands in only where Cuttlebone can't check it.
        cuttleboneCantCheckTheCode()
        myFixture.addFileToProject("src/app/util.inko", util)
        // Like the real std: a doc comment and imports before the name. And a type no file mentions.
        val std = FakeStd.write(Path.of(myFixture.tempDirPath).parent.resolve("std-$name"), mapOf(
            "std.string" to "# A string.\nimport std.cmp (Equal)\n\ntype builtin String {}\n\ntype pub Unmentioned {\n  fn pub value -> Int {\n    1\n  }\n}\n",
        ))
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(std.resolve("std/string.inko"))
        useFakeInko(FakeStd.cli(std))
    }

    private fun targetsAt(marker: String, nth: Int = 0): List<String> {
        var index = -1
        repeat(nth + 1) { index = main.indexOf(marker, index + 1) }
        val file = myFixture.addFileToProject("src/main.inko", main)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(index)
        val reference = myFixture.getReferenceAtCaretPositionWithAssertion() as InkoReference
        return reference.multiResolve(false).mapNotNull { it.element?.location() }
    }

    private fun PsiElement.location(): String {
        if (this is PsiFile) return name
        val line = containingFile.viewProvider.document!!.getLineNumber(textOffset) + 1
        return "${containingFile.name}:$line:$text"
    }

    fun testTypeName() = assertEquals(listOf("util.inko:1:Helper"), targetsAt("Helper.new"))

    fun testImportedTypeInImportList() = assertEquals(listOf("util.inko:1:Helper"), targetsAt("Helper,"))

    fun testModuleInImportPath() = assertEquals(listOf("util.inko"), targetsAt("util ("))

    fun testStaticMethod() = assertEquals(listOf("util.inko:4:new"), targetsAt("new"))

    // h is a Helper (from Helper.new), so Unmentioned's value (in std) isn't offered.
    fun testAKnownReceiverTypeNarrowsMethods() = assertEquals(listOf("util.inko:8:value"), targetsAt("value"))

    fun testImportedFunction() = assertEquals(listOf("util.inko:13:helper"), targetsAt("helper\n"))

    fun testFunctionThroughModuleAlias() = assertEquals(listOf("util.inko:13:helper"), targetsAt("helper\n    let"))

    fun testModuleAlias() = assertEquals(listOf("util.inko"), targetsAt("util.helper"))

    fun testStandardLibraryTypeFindsTheNameNotTheLocation() = assertEquals(listOf("string.inko:4:String"), targetsAt("String"))

    fun testLocalVariablesResolveToTheirDeclaration() = assertEquals(listOf("main.inko:6:h"), targetsAt("h.value"))

    // A definition's name is a declaration (what usages resolve to), not a reference.
    // A local named like an imported module hides it, as the compiler has it: `html.head` in yorickpeterse.com's
    // `html.Document.html('en', fn (html) { html.head })` is the closure argument's method, not the module's.
    fun testALocalHidesTheModuleItIsNamedLike() {
        val text = main.replace("    util.helper\n", "    [Helper.new].each(fn (util) { util.value })\n")
        val file = myFixture.addFileToProject("src/main.inko", text)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        fun at(marker: String): List<String> {
            myFixture.editor.caretModel.moveToOffset(text.indexOf(marker))
            return (myFixture.getReferenceAtCaretPositionWithAssertion() as InkoReference).multiResolve(false).mapNotNull { it.element?.location() }
        }
        assertEquals(listOf("main.inko:9:util"), at("util.value"))
        assertEquals(listOf("util.inko:8:value"), at("value })"))
    }

    fun testDefinitionsAreDeclarationsNotReferences() {
        val file = myFixture.addFileToProject("src/main.inko", main)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val name = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(main.indexOf("main {")), InkoName::class.java, false)
        assertTrue(name is InkoDeclaration)
        assertNull(name!!.reference)
    }
}
