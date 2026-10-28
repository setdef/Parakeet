package com.setdef.parakeet

import com.intellij.psi.PsiManager
import com.intellij.refactoring.BaseRefactoringProcessor

/**
 * Moving Inko files makes Cuttlebone's edits to their imports (workspace/willRenameFiles), asked before the move.
 * CuttleboneFileMovesIntegrationTest checks the edits themselves, against the real Cuttlebone.
 */
class InkoFileMovesTest : InkoTestCase(onDisk = true) {
    private val main = "import app.util (helper)\n\nfn main {\n  helper\n}\n"

    override fun setUp() {
        super.setUp()
        putTempDirInProjectContent()
        myFixture.addFileToProject("src/app/util.inko", "fn pub helper -> Int {\n  42\n}\n")
        myFixture.addFileToProject("src/main.inko", main)
        myFixture.addFileToProject("src/lib/.keep", "")
        useFakeInko(FakeInkoCli { FakeInkoCli.ok(stderr = "[]") })
    }

    private fun text(path: String) = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir(path))!!.text

    private fun answer(edits: Map<String, List<org.eclipse.lsp4j.TextEdit>>?, status: String = "fresh") = stubCuttlebone { method, file, offset ->
        val asked = method == CuttleboneQueries.FILE_RENAMES && file.path.endsWith("/src/app/util.inko") && offset == 0
        CuttleboneQueries.Answer(edits.takeIf { asked }, CuttleboneStatus(method, file.url, status, "declarationErrors".takeIf { status != "fresh" }, 1.0))
    }

    private fun edit(line: Int, start: Int, end: Int, text: String) =
        org.eclipse.lsp4j.TextEdit(org.eclipse.lsp4j.Range(org.eclipse.lsp4j.Position(line, start), org.eclipse.lsp4j.Position(line, end)), text)

    fun testMovingAFileMakesCuttlebonesEdits() {
        answer(mapOf(myFixture.findFileInTempDir("src/main.inko").url to listOf(edit(0, 7, 15, "lib.util"))))
        myFixture.moveFile("src/app/util.inko", "src/lib")
        assertEquals(main.replace("app.util", "lib.util"), text("src/main.inko"))
    }

    fun testWithoutCuttlebonesAnswerItsAConflict() {
        answer(null, status = "unavailable")
        try {
            myFixture.moveFile("src/app/util.inko", "src/lib")
            fail("moved without the conflict")
        } catch (conflict: BaseRefactoringProcessor.ConflictsInTestsException) {
            assertTrue(conflict.message, conflict.messages.single().contains("Cuttlebone has no answer for which imports name the modules in util.inko"))
        }
        assertEquals(main, text("src/main.inko"))
    }

    fun testWarnsWhenAModuleWouldLeaveSrc() {
        myFixture.addFileToProject("scripts/.keep", "")
        val util = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("src/app/util.inko"))!!
        val scripts = PsiManager.getInstance(project).findDirectory(myFixture.findFileInTempDir("scripts"))!!
        val conflicts = com.intellij.util.containers.MultiMap<com.intellij.psi.PsiElement, String>()
        InkoMoveFileHandler().detectConflicts(conflicts, arrayOf(util), emptyArray(), scripts)
        assertEquals(listOf("util.inko would be outside src/ and test/, so its imports couldn't name it."), conflicts.values().toList())
    }
}
