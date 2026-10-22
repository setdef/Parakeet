package com.setdef.parakeet

import com.intellij.navigation.NavigationItem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.PsiTestUtil
import com.intellij.util.indexing.FindSymbolParameters
import java.nio.file.Files
import java.nio.file.Path

/** Go to Class/Symbol, with `inko doc` output supplied by a fake. */
class InkoGotoTest : InkoTestCase(onDisk = true) {
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
        useFakeInko(FakeInkoDoc.cli { mirror ->
            val util = mirror.resolve("src/app/util.inko")
            listOf(
                FakeInkoDoc.module("app.util", util, types = listOf(
                    FakeInkoDoc.type("Helper", 1, instanceMethods = listOf(FakeInkoDoc.member("value", util, 2))),
                )),
                FakeInkoDoc.module("std.string", std, types = listOf(FakeInkoDoc.type("String", 1))),
            )
        })
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

    private fun names(contributor: InkoGotoContributor): Set<String> {
        val names = mutableSetOf<String>()
        contributor.processNames({ names.add(it) }, GlobalSearchScope.allScope(project), null)
        return names
    }

    private fun items(contributor: InkoGotoContributor, name: String, libraries: Boolean): List<NavigationItem> {
        val items = mutableListOf<NavigationItem>()
        contributor.processElementsWithName(name, { items.add(it) }, FindSymbolParameters.wrap(name, project, libraries))
        return items
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

    fun testClassesAreTypesAndTraits() {
        assertEquals(setOf("Helper", "String"), names(InkoGotoClassContributor()))
    }

    fun testSymbolsIncludeMethods() {
        assertTrue(names(InkoGotoSymbolContributor()).containsAll(setOf("Helper", "value", "String")))
        val item = items(InkoGotoSymbolContributor(), "value", libraries = false).single() as InkoDefinitionItem
        assertEquals("app.util.Helper", item.locationString)
    }

    fun testLibrariesOnlyWhenAskedFor() {
        assertEmpty(items(InkoGotoClassContributor(), "String", libraries = false))
        assertEquals(1, items(InkoGotoClassContributor(), "String", libraries = true).size)
    }

    fun testNavigatesToTheName() {
        val item = items(InkoGotoClassContributor(), "Helper", libraries = false).single()
        item.navigate(true)
        val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals("util.inko", editor.virtualFile!!.name)
        assertEquals("type pub ".length, editor.caretModel.offset)
    }
}
