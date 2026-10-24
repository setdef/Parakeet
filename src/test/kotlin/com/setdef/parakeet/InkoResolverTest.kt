package com.setdef.parakeet

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import java.nio.file.Files
import java.nio.file.Path

/** Go to definition, with `inko doc` output supplied by a fake. */
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
        val root = Path.of(myFixture.tempDirPath)
        myFixture.addFileToProject("src/app/util.inko", util)
        // Like the real std: a doc comment first, and a location (1:1) that isn't where the name is.
        val std = Files.createDirectories(root.resolve("std")).resolve("string.inko")
        Files.writeString(std, "# A string.\nimport std.cmp (Equal)\n\ntype builtin String {}\n")
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(std)

        useFakeInko(FakeInkoDoc.cli { mirror ->
            val utilFile = mirror.resolve("src/app/util.inko")
            listOf(
                FakeInkoDoc.module(
                    "app.util", utilFile,
                    methods = listOf(FakeInkoDoc.member("helper", utilFile, 13, 1)),
                    types = listOf(FakeInkoDoc.type(
                        "Helper", 1,
                        fields = listOf(FakeInkoDoc.member("count", utilFile, 2)),
                        staticMethods = listOf(FakeInkoDoc.member("new", utilFile, 4)),
                        instanceMethods = listOf(FakeInkoDoc.member("value", utilFile, 8)),
                    )),
                ),
                FakeInkoDoc.module(
                    "std.string", std,
                    types = listOf(
                        FakeInkoDoc.type("String", 1),
                        FakeInkoDoc.type("Unmentioned", 1, instanceMethods = listOf(FakeInkoDoc.member("value", std, 1))),
                    ),
                ),
            )
        })
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
    fun testDefinitionsAreDeclarationsNotReferences() {
        val file = myFixture.addFileToProject("src/main.inko", main)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val name = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(main.indexOf("main {")), InkoName::class.java, false)
        assertTrue(name is InkoDeclaration)
        assertNull(name!!.reference)
    }
}
