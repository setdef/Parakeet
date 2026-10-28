package com.setdef.parakeet

import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit

/**
 * Quick fixes are Cuttlebone's (`textDocument/codeAction`): asked for the diagnostic at the caret, offered by its
 * title, and made as its edits say, in whichever file. Stubbed here; CuttleboneIntegrationTest has the real ones.
 */
class InkoQuickFixesTest : InkoTestCase(onDisk = true) {
    private val text = "import std.stdio (Stdout, Stderr)\n\nfn a {\n  Stdout.new\n}\n"
    private val unused = InkoCheckTest.covering(text, "Stderr", "unused-symbol", "the symbol 'Stderr' is unused", warning = true)!!
    private var actions: List<CodeAction> = emptyList()
    private val asked = mutableListOf<String>()

    override fun setUp() {
        super.setUp()
        stubCuttlebone { method, file, offset ->
            when (method) {
                "textDocument/diagnostic" -> CuttleboneQueries.Answer(listOf(unused), null)
                "textDocument/codeAction" -> {
                    asked += "${file.name}:$offset"
                    CuttleboneQueries.Answer(actions, CuttleboneStatus(method, file.url, "fresh", null, 1.0))
                }
                else -> null
            }
        }
    }

    private fun action(title: String, diagnostic: org.eclipse.lsp4j.Diagnostic, vararg edits: Pair<String, TextEdit>) = CodeAction(title).apply {
        diagnostics = listOf(diagnostic)
        edit = WorkspaceEdit(edits.groupBy({ myFixture.findFileInTempDir(it.first)!!.url }, { it.second }))
    }

    private fun edit(line: Int, start: Int, end: Int, newText: String) = TextEdit(Range(Position(line, start), Position(line, end)), newText)

    private fun open() = myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("src/main.inko", text).virtualFile)

    fun testOffersCuttlebonesFixAndMakesItsEdits() {
        open()
        actions = listOf(action("Remove Stderr from the import", unused, "src/main.inko" to edit(0, 24, 32, "")))
        myFixture.launchAction(allQuickFixes().single { it.text == "Remove Stderr from the import" })
        myFixture.checkResult("import std.stdio (Stdout)\n\nfn a {\n  Stdout.new\n}\n")
        assertEquals("asked about the diagnostic's start", listOf("main.inko:${text.indexOf("Stderr")}"), asked.distinct())
    }

    fun testMakesEditsInOtherFiles() {
        myFixture.addFileToProject("src/other.inko", "let VALUE = 1\n")
        open()
        actions = listOf(action("Make it so", unused, "src/main.inko" to edit(0, 24, 32, ""), "src/other.inko" to edit(0, 4, 4, "mut ")))
        myFixture.launchAction(allQuickFixes().single { it.text == "Make it so" })
        myFixture.checkResult("import std.stdio (Stdout)\n\nfn a {\n  Stdout.new\n}\n")
        assertEquals("let mut VALUE = 1\n", com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(myFixture.findFileInTempDir("src/other.inko")!!)!!.text)
    }

    fun testOnlyTheDiagnosticsOwnFixes() {
        open()
        val other = InkoCheckTest.covering(text, "Stdout", "unused-symbol", "the symbol 'Stdout' is unused", warning = true)!!
        actions = listOf(action("Remove Stderr from the import", unused, "src/main.inko" to edit(0, 24, 32, "")), action("Someone else's", other, "src/main.inko" to edit(0, 0, 0, "")))
        assertEquals(listOf("Remove Stderr from the import"), allQuickFixes().map { it.text }.filter { it == "Remove Stderr from the import" || it == "Someone else's" })
    }

    fun testNotOfferedOnceTheFileChanges() {
        open()
        actions = listOf(action("Remove Stderr from the import", unused, "src/main.inko" to edit(0, 24, 32, "")))
        val fix = allQuickFixes().single { it.text == "Remove Stderr from the import" }
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(text.length, "\n") }
        assertFalse(fix.isAvailable(project, myFixture.editor, myFixture.file))
    }

    fun testNotAskedForADiagnosticCuttleboneHasNoFixFor() {
        stubCuttlebone { method, file, _ ->
            when (method) {
                "textDocument/diagnostic" -> CuttleboneQueries.Answer(listOf(InkoCheckTest.covering(text, "Stderr", "invalid-type", "the type is wrong")!!), null)
                "textDocument/codeAction" -> { asked += file.name; null }
                else -> null
            }
        }
        open()
        allQuickFixes()
        assertEquals(emptyList<String>(), asked)
    }
}
