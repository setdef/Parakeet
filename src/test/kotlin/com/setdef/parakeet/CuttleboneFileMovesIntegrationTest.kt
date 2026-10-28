package com.setdef.parakeet

import com.intellij.psi.PsiManager

/**
 * Moving and renaming Inko files and directories updates the imports of their modules, from Cuttlebone's edits
 * (workspace/willRenameFiles), against the real `cuttlebone` and `inko` (InkoFileMovesTest has the glue alone).
 */
class CuttleboneFileMovesIntegrationTest : InkoTestCase(onDisk = true, realInko = true) {
    override fun setUp() {
        super.setUp()
        val executable = System.getenv("CUTTLEBONE")?.takeIf { it.isNotEmpty() }
            ?: run { InkoProjectSettings.getInstance(project).cuttlebone = ""; Cuttlebone.executable(project)?.toString() }
        assertNotNull("cuttlebone must be installed (or CUTTLEBONE set) to run these tests", executable)
        putTempDirInProjectContent()
        useCuttlebone(executable!!)
        myFixture.addFileToProject("src/app/util.inko", "fn pub helper -> Int {\n  42\n}\n")
        myFixture.addFileToProject("src/app/other/util.inko", "fn pub other -> Int {\n  1\n}\n")
        myFixture.addFileToProject("src/lib/.keep", "")
    }

    private fun text(path: String) = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir(path))!!.text

    /** Adds src/main.inko as [text], opens it, and waits for Cuttlebone to have it. */
    private fun main(text: String) {
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("src/main.inko", text).virtualFile)
        com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching({ "Cuttlebone didn't start: ${CuttleboneService.getInstance(project).status?.message}" }, {
            val server = CuttleboneService.getInstance(project).server()
            server != null && CuttleboneService.getInstance(project).sentVersions[server.getDocumentIdentifier(myFixture.file.virtualFile).uri] ==
                server.getDocumentVersion(myFixture.editor.document)
        }, TimingLimits.seconds(60))
    }

    fun testMovingAFileUpdatesItsImports() {
        main("import app.util (helper)\nimport app.util\nimport app.other.util (other)\n\nfn main {\n  helper + util.helper + other\n}\n")
        myFixture.moveFile("src/app/util.inko", "src/lib")
        assertEquals(
            "import lib.util (helper)\nimport lib.util\nimport app.other.util (other)\n\nfn main {\n  helper + util.helper + other\n}\n",
            text("src/main.inko"),
        )
    }

    fun testRenamingADirectoryUpdatesTheImportsOfEveryModuleInIt() {
        main("import app.util (helper)\nimport app.other.util (other)\n\nfn main {\n  helper + other\n}\n")
        val directory = PsiManager.getInstance(project).findDirectory(myFixture.findFileInTempDir("src/app"))!!
        myFixture.renameElement(directory, "core")
        assertEquals("import core.util (helper)\nimport core.other.util (other)\n\nfn main {\n  helper + other\n}\n", text("src/main.inko"))
    }

    fun testRenamingANestedDirectory() {
        main("import app.util (helper)\nimport app.other.util (other)\n")
        val directory = PsiManager.getInstance(project).findDirectory(myFixture.findFileInTempDir("src/app/other"))!!
        myFixture.renameElement(directory, "extra")
        assertEquals("import app.util (helper)\nimport app.extra.util (other)\n", text("src/main.inko"))
    }

    fun testMovingIntoAnotherModulesDirectory() {
        main("import app.util (helper)\n")
        myFixture.moveFile("src/app/util.inko", "src/app/other/nested".also { myFixture.addFileToProject("$it/.keep", "") })
        assertEquals("import app.other.nested.util (helper)\n", text("src/main.inko"))
    }

    fun testMovingADirectoryUpdatesEachModuleInIt() {
        main("import app.other.util (other)\n")
        val other = PsiManager.getInstance(project).findDirectory(myFixture.findFileInTempDir("src/app/other"))!!
        val lib = PsiManager.getInstance(project).findDirectory(myFixture.findFileInTempDir("src/lib"))!!
        com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesProcessor(project, arrayOf(other), lib, true, false, false, null, null).run()
        assertEquals("import lib.other.util (other)\n", text("src/main.inko"))
    }
}
