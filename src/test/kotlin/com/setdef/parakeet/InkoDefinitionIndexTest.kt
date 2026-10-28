package com.setdef.parakeet

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import java.nio.file.Files
import java.nio.file.Path
import com.setdef.parakeet.InkoDefinitionKind as Kind

/** The definitions a project can see, read from its code, its dependencies' and the standard library's. */
class InkoDefinitionIndexTest : InkoTestCase(onDisk = true) {
    private val root: Path get() = Path.of(myFixture.tempDirPath)
    private val util = "type pub Helper {\n  fn pub value -> Int {\n    42\n  }\n}\n"

    override fun setUp() {
        super.setUp()
        // `inko print std` names a standard library of one module, std.string.
        val std = Files.createDirectories(root.parent.resolve("std-$name"))
        Files.writeString(Files.createDirectories(std.resolve("std")).resolve("string.inko"), "type builtin String {}\n\ntrait pub ToString {\n  fn pub to_string -> String\n}\n")
        useFakeInko(FakeInkoCli { call ->
            if (call.args == listOf("print", "std")) FakeInkoCli.ok(stdout = "$std\n") else FakeInkoCli.ok(stderr = "[]")
        })
    }

    private fun symbols(): InkoSymbols = InkoDefinitionIndex.getInstance(project).symbols(root)!!

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
        assertEquals(listOf("Helper"), symbols().named("Helper").map { it.name })
        assertEquals("std.string", symbols().named("String").single().module)
    }

    // Whether the project compiles or not: nothing is compiled.
    fun testSavedChangesAreKnown() {
        open("src/app/util.inko", util)
        assertNotEmpty(symbols().named("Helper"))

        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.insertString(0, "fn pub broken -> Int {\n  'nope'\n}\n\n")
        }
        FileDocumentManager.getInstance().saveAllDocuments()

        assertEquals(Kind.MODULE_METHOD, symbols().named("broken").single().kind)
        assertEquals(5, symbols().named("Helper").single().line)
    }

    // A dependency's modules are named from its `src/`, as the compiler finds them (dep/<url>/v<major>/src), so two
    // `html`s are builder.html and markdown.html. Tests' modules are named from test/, as src/'s are from src/.
    fun testDependenciesAndTestsAreNamedAsTheCompilerNamesThem() {
        for (module in listOf("builder", "markdown")) {
            Files.writeString(Files.createDirectories(root.resolve("dep/$module-hash/v0/src/$module")).resolve("html.inko"), "type pub ${module.replaceFirstChar(Char::uppercase)}Html {}\n")
        }
        myFixture.addFileToProject("src/app/util.inko", util)
        myFixture.addFileToProject("test/app/test_util.inko", "fn pub tests(t: mut Tests) {}\n")

        assertEquals("builder.html", symbols().named("BuilderHtml").single().module)
        assertEquals("markdown.html", symbols().named("MarkdownHtml").single().module)
        assertEquals("app.test_util", symbols().named("tests").single().module)
    }

    // A method an `impl Trait for Type` block writes is the type's, in src/ and test/ (which traits each type
    // implements is Cuttlebone's: InkoTraitMarkersTest).
    fun testImplBlocksMethodsAreTheirTypes() {
        myFixture.addFileToProject("src/app/util.inko", "import std.string (ToString)\n\ntrait pub Show {\n  fn pub show -> String\n}\n\n$util\nimpl Show for Helper {\n  fn pub show -> String {\n    ''\n  }\n}\n\nimpl ToString for Helper {\n  fn pub to_string -> String {\n    ''\n  }\n}\n")
        myFixture.addFileToProject("test/app/test_util.inko", "import app.util (Show)\n\ntype Fake {}\n\nimpl Show for Fake {\n  fn pub show -> String {\n    'fake'\n  }\n}\n")

        assertEquals(listOf("Fake", "Helper"), symbols().named("show", Kind.INSTANCE_METHOD).mapNotNull { it.owner }.filter { it != "Show" }.sorted())
    }
}
