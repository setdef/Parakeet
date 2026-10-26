package com.setdef.parakeet

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import java.nio.file.Files
import java.nio.file.Path
import com.setdef.parakeet.InkoDefinitionKind as Kind

/** How the index fills gaps `inko doc` leaves: unsaved edits, changed files, and projects that don't compile. */
class InkoDocIndexTest : InkoTestCase(onDisk = true) {
    private val root: Path get() = Path.of(myFixture.tempDirPath)
    private val util = "type pub Helper {\n  fn pub value -> Int {\n    42\n  }\n}\n"

    /** Answers `inko doc` with app.util's Helper, and std.string's String; fails while [compiles] is false. */
    private var compiles = true
    private val standInImports = mutableListOf<String>()

    override fun setUp() {
        super.setUp()
        val std = Files.createDirectories(root.resolve("std")).resolve("string.inko")
        Files.writeString(std, "type builtin String {}\n")
        useFakeInko(FakeInkoCli { call ->
            if (call.args.firstOrNull() != "doc") return@FakeInkoCli FakeInkoCli.ok(stderr = "[]")
            val mirror = call.workingDirectory!!
            val standIn = mirror.resolve("src/parakeet_dependency_index.inko")
            val hasProject = Files.exists(mirror.resolve("src/app/util.inko"))
            if (hasProject && !compiles) return@FakeInkoCli FakeInkoCli.failed("[]")
            if (Files.exists(standIn)) standInImports += Files.readString(standIn)
            val docs = Files.createDirectories(mirror.resolve("build/docs"))
            Files.writeString(docs.resolve("std_string.json"), FakeInkoDoc.module("std.string", std, types = listOf(FakeInkoDoc.type("String", 1))))
            if (hasProject) {
                val file = mirror.resolve("src/app/util.inko")
                Files.writeString(docs.resolve("app_util.json"), FakeInkoDoc.module(
                    "app.util", file,
                    types = listOf(FakeInkoDoc.type("Helper", 1, instanceMethods = listOf(FakeInkoDoc.member("value", file, 2)))),
                ))
            }
            FakeInkoCli.ok()
        })
    }

    private fun symbols(): InkoSymbols = InkoDocIndex.getInstance(project).symbols(root)!!

    private fun open(path: String, text: String) {
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject(path, text).virtualFile)
    }

    fun testUnsavedDefinitionsAreKnownBeforeSaving() {
        open("src/app/util.inko", util)
        assertEmpty(symbols().named("fresh"))

        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.textLength)
        myFixture.type("\nfn pub fresh -> Int {\n  1\n}\n")

        val fresh = symbols().named("fresh").single()
        assertEquals(Kind.MODULE_METHOD, fresh.kind)
        assertEquals("fn pub fresh -> Int", fresh.signature)
        assertEquals(7, fresh.line)
        assertEquals(listOf("Helper"), symbols().named("Helper").map { it.name }) // Read from source, not duplicated.
        assertNotEmpty(symbols().named("String")) // The rest still comes from inko doc.
    }

    fun testSavedChangesAreKnownEvenWhenTheProjectNoLongerCompiles() {
        open("src/app/util.inko", util)
        assertNotEmpty(symbols().named("Helper"))

        compiles = false
        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.insertString(0, "fn pub broken -> Int {\n  'nope'\n}\n\n")
        }
        FileDocumentManager.getInstance().saveAllDocuments()

        assertEquals(Kind.MODULE_METHOD, symbols().named("broken").single().kind)
        assertEquals(5, symbols().named("Helper").single().line)
    }

    // `inko doc` documents what src/ imports, so std.test, which only tests import, was missing: no documentation
    // for `t.equal` in a test, and `Tests` in `import std.test (Tests)` resolved to nothing.
    fun testModulesOnlyTheTestsImportAreDocumentedToo() {
        val std = Files.createDirectories(root.resolve("inko-std"))
        val tests = Files.createDirectories(std.resolve("std")).resolve("test.inko")
        Files.writeString(tests, "type pub Tests {}\n")
        useFakeInko(FakeInkoCli { call ->
            when (call.args.firstOrNull()) {
                "print" -> FakeInkoCli.ok(stdout = "$std\n")
                "doc" -> {
                    val mirror = call.workingDirectory!!
                    val standIn = mirror.resolve("src/parakeet_dependency_index.inko")
                    if (Files.exists(standIn)) standInImports += Files.readString(standIn)
                    val docs = Files.createDirectories(mirror.resolve("build/docs"))
                    val file = mirror.resolve("src/app/util.inko")
                    Files.writeString(docs.resolve("app_util.json"), FakeInkoDoc.module("app.util", file, types = listOf(FakeInkoDoc.type("Helper", 1))))
                    // As inko doc does: what the project imports, the stand-in included.
                    if (standInImports.any { "import std.test\n" in it }) {
                        Files.writeString(docs.resolve("std_test.json"), FakeInkoDoc.module("std.test", tests, types = listOf(FakeInkoDoc.type("Tests", 1))))
                    }
                    FakeInkoCli.ok()
                }
                else -> FakeInkoCli.ok(stderr = "[]")
            }
        })
        myFixture.addFileToProject("src/app/util.inko", util)
        myFixture.addFileToProject("test/app/test_util.inko", "import app.util (Helper)\nimport std.missing\nimport std.test (Tests)\n\nfn pub tests(t: mut Tests) {}\n")

        assertEquals("std.test", symbols().named("Tests").single().module)
        assertNotEmpty(symbols().named("Helper"))
        // Only what exists, so the stand-in can't stop inko doc: not the project's own modules, nor what isn't there.
        assertEquals(listOf("import std.test\n"), standInImports)
    }

    fun testAProjectThatDoesNotCompileStillGetsTheStandardLibraryDependenciesAndItsOwnDefinitions() {
        compiles = false
        Files.writeString(root.resolve("inko.pkg"), "require https://github.com/yorickpeterse/inko-markdown 0.24.0 abc\nrequire inko 0.21.1\n")
        val dep = Files.createDirectories(root.resolve("dep/0a1b/v0/src/markdown"))
        Files.writeString(dep.resolve("../markdown.inko"), "")
        Files.writeString(dep.resolve("parser.inko"), "")
        Files.createDirectories(root.resolve("dep/9f9f/v0/src"))
        Files.writeString(root.resolve("dep/9f9f/v0/src/transitive.inko"), "") // Not a direct dependency.
        open("src/app/util.inko", "# Helps.\n$util")

        val symbols = symbols()
        assertNotEmpty(symbols.named("String"))
        assertEquals("Helps.", symbols.named("Helper").single().documentation)
        assertEquals("Helper", symbols.named("value").single().owner)
        assertEquals(listOf("import markdown\nimport markdown.parser\n"), standInImports)
    }
}
