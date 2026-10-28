package com.setdef.parakeet

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PsiTestUtil
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Finding the Inko projects, and indexing them at startup. */
class InkoProjectRootsTest : InkoTestCase(onDisk = true) {
    private lateinit var std: Path
    private lateinit var contentRoot: VirtualFile

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("inko.pkg", "")
        // In the IDE the project directory is a content root; make the test project match.
        contentRoot = myFixture.tempDirFixture.getFile("")!!
        PsiTestUtil.addContentRoot(module, contentRoot)
        myFixture.addFileToProject("src/app/util.inko", "type pub Helper {\n  fn pub value -> Int {\n    42\n  }\n}\n")
        std = Files.createDirectories(Path.of(myFixture.tempDirPath).parent.resolve("std-${name}")).resolve("string.inko")
        Files.writeString(std, "type builtin String {}\n")
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(std)
        useFakeInko(FakeInkoCli { FakeInkoCli.ok(stderr = "[]") })
    }

    override fun tearDown() {
        try {
            PsiTestUtil.removeContentEntry(module, contentRoot)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testFindsTheInkoProject() {
        assertEquals(listOf(Path.of(myFixture.tempDirPath)), InkoProjectRoots.find(project))
    }

    // Regression: any content root with a src/ directory used to count, so opening
    // a Ruby or Java project ran inko doc on it at startup.
    fun testIgnoresContentRootsWithoutInkoSources() {
        val other = Files.createDirectories(Path.of(myFixture.tempDirPath).parent.resolve("not-inko-$name/src"))
        Files.writeString(other.resolve("main.rb"), "puts 1\n")
        val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(other.parent)!!
        PsiTestUtil.addContentRoot(module, root)
        try {
            assertEquals(listOf(Path.of(myFixture.tempDirPath)), InkoProjectRoots.find(project))
        } finally {
            PsiTestUtil.removeContentEntry(module, root)
        }
    }

    // Regression: startup searched the file name index without a read action, which the
    // IDE rejects on its background threads ("Read access is allowed from inside read-action only").
    fun testIndexesOnStartupFromABackgroundThread() {
        ApplicationManager.getApplication().executeOnPooledThread {
            runBlocking { InkoIndexOnStartup().execute(project) }
        }.get(60, TimeUnit.SECONDS)
        assertNotEmpty(InkoDefinitionIndex.getInstance(project).symbols(Path.of(myFixture.tempDirPath))!!.named("Helper"))
    }
}
