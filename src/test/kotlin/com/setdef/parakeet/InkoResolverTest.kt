package com.setdef.parakeet

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
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

    private fun location(line: Int, column: Int) = """{"lines": {"start": $line, "end": $line}, "columns": {"start": $column, "end": $column}}"""

    private fun member(name: String, file: Path, line: Int, column: Int = 3) =
        """{"name": "$name", "file": "$file", "location": ${location(line, column)}}"""

    override fun setUp() {
        super.setUp()
        val root = Path.of(myFixture.tempDirPath)
        myFixture.addFileToProject("src/app/util.inko", util)
        // Like the real std: a doc comment first, and a location (1:1) that isn't where the name is.
        val std = Files.createDirectories(root.resolve("std")).resolve("string.inko")
        Files.writeString(std, "# A string.\nimport std.cmp (Equal)\n\ntype builtin String {}\n")
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(std)

        useFakeInko(FakeInkoCli { call ->
            assertEquals(listOf("doc", "--private", "--dependencies"), call.args)
            val mirror = call.workingDirectory!!
            val utilFile = mirror.resolve("src/app/util.inko")
            val docs = Files.createDirectories(mirror.resolve("build/docs"))
            Files.writeString(docs.resolve("app_util.json"), """
                {"name": "app.util", "file": "$utilFile", "constants": [],
                 "methods": [${member("helper", utilFile, 13, 1)}],
                 "types": [{"name": "Helper", "location": ${location(1, 1)}, "constructors": [],
                            "fields": [{"name": "count", "location": ${location(2, 3)}}],
                            "static_methods": [${member("new", utilFile, 4)}],
                            "instance_methods": [${member("value", utilFile, 8)}]}],
                 "traits": []}
            """)
            Files.writeString(docs.resolve("std_string.json"), """
                {"name": "std.string", "file": "$std", "constants": [], "methods": [],
                 "types": [{"name": "String", "location": ${location(1, 1)}, "constructors": [], "fields": [],
                            "static_methods": [], "instance_methods": []},
                           {"name": "Unmentioned", "location": ${location(1, 1)}, "constructors": [], "fields": [],
                            "static_methods": [], "instance_methods": [${member("value", std, 1)}]}],
                 "traits": []}
            """)
            Files.writeString(docs.resolve("\$meta.json"), """{"readme": ""}""")
            FakeInkoCli.ok()
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

    fun testInstanceMethodPrefersTypesTheFileMentions() = assertEquals(listOf("util.inko:8:value"), targetsAt("value"))

    fun testImportedFunction() = assertEquals(listOf("util.inko:13:helper"), targetsAt("helper\n"))

    fun testFunctionThroughModuleAlias() = assertEquals(listOf("util.inko:13:helper"), targetsAt("helper\n    let"))

    fun testModuleAlias() = assertEquals(listOf("util.inko"), targetsAt("util.helper"))

    fun testStandardLibraryTypeFindsTheNameNotTheLocation() = assertEquals(listOf("string.inko:4:String"), targetsAt("String"))

    fun testLocalVariablesDoNotResolve() = assertEmpty(targetsAt("h.value"))

    fun testDefinitionsDoNotResolve() = assertEmpty(targetsAt("main {"))
}
