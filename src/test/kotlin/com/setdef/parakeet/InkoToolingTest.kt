package com.setdef.parakeet

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.TempDirTestFixture
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import org.jetbrains.plugins.textmate.TextMateService

/** Exercises the real `inko` executable, which must be installed (PATH or ivm). */
class InkoToolingTest : BasePlatformTestCase() {
    // inko check and inko fmt need real files on disk, not the in-memory test file system.
    override fun createTempDirTestFixture(): TempDirTestFixture = TempDirTestFixtureImpl()

    override fun setUp() {
        super.setUp()
        TextMateService.getInstance().ensureInitialized()
        assertNotNull("inko must be installed to run these tests", InkoExecutable.detect())
    }

    private fun problems(): List<HighlightInfo> =
        myFixture.doHighlighting().filter { it.severity >= HighlightSeverity.WEAK_WARNING && it.description != null }

    fun testCompilerErrorsAreShown() {
        myFixture.addFileToProject("src/app/util.inko", "fn pub helper -> Int {\n  42\n}\n")
        val file = myFixture.addFileToProject(
            "src/main.inko",
            "import app.util (helper)\n\ntype async Main {\n  fn async main {\n    let x: String = helper\n  }\n}\n",
        )
        myFixture.configureFromExistingVirtualFile(file.virtualFile)

        val error = problems().single { it.severity == HighlightSeverity.ERROR }
        assertEquals("this pattern expects a value of type 'String', but the value's type is 'Int'", error.description)
        assertEquals("x: String", myFixture.editor.document.getText(TextRange(error.startOffset, error.endOffset)))
    }

    fun testWarningsAreShown() {
        val file = myFixture.addFileToProject("src/app/util.inko", "import std.stdio (Stdout)\n\nfn pub helper -> Int {\n  42\n}\n")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)

        val warning = problems().single()
        assertEquals(HighlightSeverity.WARNING, warning.severity)
        assertEquals("Stdout", myFixture.editor.document.getText(TextRange(warning.startOffset, warning.endOffset)))
    }

    fun testUnsavedChangesAreChecked() {
        val file = myFixture.addFileToProject("src/app/util.inko", "fn pub helper -> Int {\n  42\n}\n")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        assertEmpty(problems())

        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("42"))
        myFixture.type("undefined_thing + ")

        val error = problems().single()
        assertEquals("the symbol 'undefined_thing' is undefined", error.description)
    }

    // Regression: when another module imports the edited one, the compiler used to
    // load the saved file from disk instead of the unsaved copy.
    fun testUnsavedChangesInImportedModulesAreChecked() {
        val file = myFixture.addFileToProject("src/app/util.inko", "fn pub helper -> Int {\n  42\n}\n")
        myFixture.addFileToProject(
            "src/main.inko",
            "import app.util (helper)\n\ntype async Main {\n  fn async main {\n    helper\n  }\n}\n",
        )
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        assertEmpty(problems())

        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("42"))
        myFixture.type("unsaved_only + ")

        val error = problems().single()
        assertEquals("the symbol 'unsaved_only' is undefined", error.description)
    }

    fun testReformatUsesInkoFmt() {
        val file = myFixture.addFileToProject("src/main.inko", "fn   main   {\nlet a=1\n}\n")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
        assertEquals("fn main {\n  let a = 1\n}\n", myFixture.editor.document.text)
    }

    fun testTestFilesCanImportProjectModules() {
        myFixture.addFileToProject("src/app/util.inko", "fn pub helper -> Int {\n  42\n}\n")
        val file = myFixture.addFileToProject(
            "test/app/test_util.inko",
            "import app.util (helper)\n\nfn pub tests -> Int {\n  helper\n}\n",
        )
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        assertEmpty(problems())
    }

    fun testProjectRoot() {
        val root = java.nio.file.Path.of(myFixture.tempDirPath)
        myFixture.addFileToProject("src/app/util.inko", "")
        assertEquals(root, Inko.projectRoot(root.resolve("src/app/util.inko")))
        assertEquals(root, Inko.projectRoot(root.resolve("test/app/test_util.inko")))
        assertEquals(root, Inko.projectRoot(root.resolve("script.inko")))
        // A test directory without a sibling src isn't a project root.
        assertEquals(root.resolve("other/test"), Inko.projectRoot(root.resolve("other/test/a.inko")))
    }
}
