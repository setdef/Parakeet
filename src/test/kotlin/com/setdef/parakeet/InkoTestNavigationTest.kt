package com.setdef.parakeet

import com.intellij.openapi.fileEditor.FileEditorManager
import java.nio.file.Files
import java.nio.file.Path

/** Go to Test (⌘⇧T) and Create Test, by the src/…/x.inko ↔ test/…/test_x.inko layout. */
class InkoTestNavigationTest : InkoTestCase(onDisk = true) {
    private val root: Path get() = Path.of(myFixture.tempDirPath)

    fun testMapsModulesAndTests() {
        // A test/ directory only belongs to a project with a src/ beside it, as for the compiler.
        Files.createDirectories(root.resolve("src"))
        assertEquals(root.resolve("test/app/test_util.inko"), InkoTestFiles.testFor(root.resolve("src/app/util.inko")))
        assertEquals(root.resolve("src/app/util.inko"), InkoTestFiles.sourceFor(root.resolve("test/app/test_util.inko")))
        assertNull(InkoTestFiles.testFor(root.resolve("test/app/test_util.inko")))
        assertNull(InkoTestFiles.sourceFor(root.resolve("test/app/helpers.inko")))
    }

    private fun selectedFile(): String {
        val file = FileEditorManager.getInstance(project).selectedTextEditor!!.virtualFile!!
        return root.relativize(file.toNioPath()).toString()
    }

    fun testGoesFromAModuleToItsTestAndBack() {
        myFixture.addFileToProject("test/app/test_util.inko", "import std.test (Tests)\n\nfn pub tests(t: mut Tests) {}\n")
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("src/app/util.inko", "fn pub helper -> Int {\n  1\n}\n").virtualFile)
        myFixture.performEditorAction("GotoTest")
        assertEquals("test/app/test_util.inko", selectedFile())

        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("test/app/test_util.inko"))
        myFixture.performEditorAction("GotoTest")
        assertEquals("src/app/util.inko", selectedFile())
    }

    fun testCreatesAMissingTestFromTheTemplate() {
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("src/app/util.inko", "fn pub helper -> Int {\n  1\n}\n").virtualFile)
        val creator = InkoTestCreator()
        assertTrue(creator.isAvailable(project, myFixture.editor, myFixture.file))
        creator.createTest(project, myFixture.editor, myFixture.file)
        assertEquals("test/app/test_util.inko", selectedFile())
        assertTrue(Files.readString(root.resolve("test/app/test_util.inko")).contains("fn pub tests(t: mut Tests)"))
        assertFalse(creator.isAvailable(project, myFixture.editor, myFixture.file))
    }
}
