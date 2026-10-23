package com.setdef.parakeet

import com.intellij.psi.PsiManager

/** Moving Inko files updates the imports of their modules. */
class InkoMoveFileTest : InkoTestCase(onDisk = true) {
    override fun setUp() {
        super.setUp()
        putTempDirInProjectContent()
        myFixture.addFileToProject("src/app/util.inko", "fn pub helper -> Int {\n  42\n}\n")
        myFixture.addFileToProject("src/app/other/util.inko", "fn pub other -> Int {\n  1\n}\n")
        myFixture.addFileToProject("src/lib/.keep", "")
        useFakeInko(FakeInkoDoc.cli { mirror ->
            listOf(
                FakeInkoDoc.module("app.util", mirror.resolve("src/app/util.inko"), methods = listOf(FakeInkoDoc.member("helper", mirror.resolve("src/app/util.inko"), 1, 1))),
                FakeInkoDoc.module("app.other.util", mirror.resolve("src/app/other/util.inko"), methods = listOf(FakeInkoDoc.member("other", mirror.resolve("src/app/other/util.inko"), 1, 1))),
            )
        })
    }

    private fun text(path: String) = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir(path))!!.text

    fun testMovingAFileUpdatesItsImports() {
        myFixture.addFileToProject("src/main.inko", "import app.util (helper)\nimport app.util\nimport app.other.util (other)\n\nfn main {\n  helper + util.helper + other\n}\n")
        myFixture.moveFile("src/app/util.inko", "src/lib")
        assertEquals(
            "import lib.util (helper)\nimport lib.util\nimport app.other.util (other)\n\nfn main {\n  helper + util.helper + other\n}\n",
            text("src/main.inko"),
        )
    }

    fun testRenamingADirectoryUpdatesTheImportsOfEveryModuleInIt() {
        myFixture.addFileToProject("src/main.inko", "import app.util (helper)\nimport app.other.util (other)\n\nfn main {\n  helper + other\n}\n")
        val directory = PsiManager.getInstance(project).findDirectory(myFixture.findFileInTempDir("src/app"))!!
        myFixture.renameElement(directory, "core")
        assertEquals("import core.util (helper)\nimport core.other.util (other)\n\nfn main {\n  helper + other\n}\n", text("src/main.inko"))
    }

    fun testRenamingANestedDirectory() {
        myFixture.addFileToProject("src/main.inko", "import app.util (helper)\nimport app.other.util (other)\n")
        val directory = PsiManager.getInstance(project).findDirectory(myFixture.findFileInTempDir("src/app/other"))!!
        myFixture.renameElement(directory, "extra")
        assertEquals("import app.util (helper)\nimport app.extra.util (other)\n", text("src/main.inko"))
    }

    fun testMovingIntoAnotherModulesDirectory() {
        myFixture.addFileToProject("src/main.inko", "import app.util (helper)\n")
        myFixture.moveFile("src/app/util.inko", "src/app/other/nested".also { myFixture.addFileToProject("$it/.keep", "") })
        assertEquals("import app.other.nested.util (helper)\n", text("src/main.inko"))
    }

    fun testWarnsWhenAModuleWouldLeaveSrc() {
        myFixture.addFileToProject("src/main.inko", "import app.util (helper)\n")
        myFixture.addFileToProject("scripts/.keep", "")
        val util = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("src/app/util.inko"))!!
        val scripts = PsiManager.getInstance(project).findDirectory(myFixture.findFileInTempDir("scripts"))!!
        val usages = com.intellij.psi.search.searches.ReferencesSearch.search(util).findAll().map { com.intellij.usageView.UsageInfo(it) }
        val conflicts = com.intellij.util.containers.MultiMap<com.intellij.psi.PsiElement, String>()
        InkoMoveFileHandler().detectConflicts(conflicts, arrayOf(util), usages.toTypedArray(), scripts)
        assertEquals(listOf("util.inko would be outside src/ and test/, so its imports couldn't name it."), conflicts.values().toList())
    }

    fun testMovingADirectoryUpdatesEachModuleInIt() {
        myFixture.addFileToProject("src/main.inko", "import app.other.util (other)\n")
        val other = PsiManager.getInstance(project).findDirectory(myFixture.findFileInTempDir("src/app/other"))!!
        val lib = PsiManager.getInstance(project).findDirectory(myFixture.findFileInTempDir("src/lib"))!!
        com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesProcessor(project, arrayOf(other), lib, true, false, false, null, null).run()
        assertEquals("import lib.other.util (other)\n", text("src/main.inko"))
    }
}
