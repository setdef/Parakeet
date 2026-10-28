package com.setdef.parakeet

import com.intellij.ide.actions.CopyReferenceAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PsiTestUtil

/** Copy Reference: module.Type.member names for declarations. */
class InkoQualifiedNamesTest : InkoTestCase(onDisk = true) {
    private val util = """
        |type pub Helper {
        |  let @count: Int
        |
        |  fn pub static new -> Helper {
        |    let local = 1
        |    Helper(count: local)
        |  }
        |}
        |
        |impl ToString for Helper {
        |  fn pub to_string -> String {
        |    ''
        |  }
        |}
        |
        |fn pub helper -> Int {
        |  1
        |}
        |""".trimMargin()

    private fun referenceAt(marker: String): String? {
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("src/app/util.inko", util).virtualFile)
        val element = myFixture.file.findElementAt(util.indexOf(marker))!!
        return CopyReferenceAction.elementToFqn(InkoQualifiedNameProvider().adjustElementToCopy(element))
    }

    fun testType() = assertEquals("app.util.Helper", referenceAt("Helper {"))

    fun testStaticMethod() = assertEquals("app.util.Helper.new", referenceAt("new"))

    fun testField() = assertEquals("app.util.Helper.count", referenceAt("@count"))

    fun testMethodInAnImplIsNamedAfterItsType() = assertEquals("app.util.Helper.to_string", referenceAt("to_string"))

    fun testModuleMethod() = assertEquals("app.util.helper", referenceAt("helper"))

    fun testModule() {
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("src/app/util.inko", util).virtualFile)
        assertEquals("app.util", CopyReferenceAction.elementToFqn(myFixture.file))
    }

    fun testLocalsHaveNoReference() = assertNull(InkoQualifiedNames.of(myFixture.run {
        configureFromExistingVirtualFile(addFileToProject("src/app/util.inko", util).virtualFile)
        file.findElementAt(util.indexOf("local ="))!!.parent
    }))

    // A standard library file isn't under a project's src/, so its module name comes from the index.
    fun testStandardLibraryNamesComeFromTheIndex() {
        val stdDirectory = FakeStd.write(java.nio.file.Path.of(myFixture.tempDirPath).parent.resolve("std-$name"), mapOf("std.string" to "type pub String {}\n"))
        val std = stdDirectory.resolve("std/string.inko")
        myFixture.addFileToProject("src/main.inko", "fn main {}\n")
        PsiTestUtil.addContentRoot(module, myFixture.tempDirFixture.getFile("")!!)
        useFakeInko(FakeStd.cli(stdDirectory))
        val file = myFixture.psiManager.findFile(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(std)!!)!!
        // Adding the content root (and the libraries the index finds) starts indexing; Copy Reference waits it out.
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertEquals("std.string.String", InkoQualifiedNames.of(file.findElementAt(file.text.indexOf("String"))!!.parent))
    }

    // Copy Reference works while indexing, when the file index (for project roots) isn't usable.
    fun testStandardLibraryFilesWhileIndexingGetNoReferenceRatherThanAnError() {
        val std = java.nio.file.Files.createDirectories(java.nio.file.Path.of(myFixture.tempDirPath, "std")).resolve("string.inko")
        java.nio.file.Files.writeString(std, "type pub String {}\n")
        val file = myFixture.psiManager.findFile(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(std)!!)!!
        com.intellij.testFramework.DumbModeTestUtils.runInDumbModeSynchronously(project) {
            assertNull(InkoQualifiedNames.of(file.findElementAt(file.text.indexOf("String"))!!.parent))
        }
    }
}
