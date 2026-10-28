package com.setdef.parakeet

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Files
import java.nio.file.Path

/** The standard library and dependencies under External Libraries, with `dep` and `build` excluded. */
class InkoLibrariesTest : InkoTestCase(onDisk = true) {
    private lateinit var std: Path

    override fun setUp() {
        super.setUp()
        putTempDirInProjectContent()
        std = Files.createTempDirectory("inko-std")
        Files.createDirectories(std.resolve("std"))
        Files.writeString(std.resolve("std/string.inko"), "type pub String {}\n")
        myFixture.addFileToProject("inko.pkg", "require inko 0.20.0\n")
        myFixture.addFileToProject("src/main.inko", "fn main {}\n")
        myFixture.addFileToProject("dep/abc/v1/src/dependency.inko", "fn pub hello {}\n")
        useFakeInko(FakeInkoCli { call ->
            when (call.args) {
                listOf("print", "std") -> FakeInkoCli.ok(stdout = "$std\n")
                listOf("--version") -> FakeInkoCli.ok(stdout = "inko 0.21.1\n")
                else -> FakeInkoCli.ok()
            }
        })
        // Every file "fails" to check, so an unchecked file shows no errors.
        pullDiagnostics { _, _ -> listOf(InkoCheckTest.diagnostic(0, 0, 1, "checked", "x")) }
    }

    private val root: Path get() = Path.of(myFixture.tempDirPath)

    private fun file(path: Path) = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!

    private fun excluded(): List<String> =
        ModuleRootManager.getInstance(module).contentEntries.flatMap { it.excludeFolderUrls }.map { it.substringAfterLast('/') }.sorted()

    fun testListsTheStandardLibraryAndDependencies() {
        InkoLibraries.getInstance(project).refresh()
        val libraries = InkoLibraries.getInstance(project).libraries
        assertEquals(listOf("Inko 0.21.1 standard library", "Inko dependencies"), libraries.map { it.presentableText })
        val index = ProjectFileIndex.getInstance(project)
        assertTrue(index.isInLibrary(file(std.resolve("std/string.inko"))))
        assertTrue(index.isInLibrary(file(root.resolve("dep/abc/v1/src/dependency.inko"))))
    }

    fun testExcludesDepAndBuildFromTheProject() {
        InkoLibraries.getInstance(project).refresh()
        assertEquals(listOf("build", "dep"), excluded())
        assertFalse(ProjectFileIndex.getInstance(project).isInContent(file(root.resolve("dep/abc/v1/src/dependency.inko"))))
        assertTrue(ProjectFileIndex.getInstance(project).isInContent(file(root.resolve("src/main.inko"))))
    }

    fun testExcludesOnlyOnceSoUnexcludingSticks() {
        InkoLibraries.getInstance(project).refresh()
        ModuleRootModificationUtil.updateModel(module) { model -> model.contentEntries.forEach { entry -> entry.excludeFolders.forEach(entry::removeExcludeFolder) } }
        InkoLibraries.getInstance(project).refresh()
        assertEmpty(excluded())
    }

    fun testLibraryFilesAreNotChecked() {
        InkoLibraries.getInstance(project).refresh()
        fun problems(): List<HighlightInfo> = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }
        myFixture.configureFromExistingVirtualFile(file(root.resolve("dep/abc/v1/src/dependency.inko")))
        assertEmpty(problems())
        myFixture.configureFromTempProjectFile("src/main.inko")
        assertEquals(listOf("checked"), problems().map { it.description })
    }
}
