package com.setdef.parakeet

import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.usageView.UsageInfo
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Within one Cuttlebone, after a file is changed and changed back, Find Usages and Cuttlebone's
 * `textDocument/references` must describe the text as it is: SESSION-real-world-refactorings.md's "stale
 * answers within one Cuttlebone", where Safe Delete deleted kvi's `run`, still used. The real `cuttlebone`
 * runs behind a script that keeps what IntelliJ sent it and what it answered, for a reproduction at the
 * protocol's level (the path is printed).
 */
class CuttleboneStaleAnswersIntegrationTest : InkoTestCase(onDisk = true, realInko = true) {
    private val util = """
        |fn pub value -> Int {
        |  1
        |}
        |
        |fn pub twice -> Int {
        |  value + value
        |}
        |""".trimMargin()

    private val main = """
        |import app.util (value)
        |
        |fn pub run -> Int {
        |  value
        |}
        |""".trimMargin()

    /** What each test adds to the start of util.inko and takes away again: it moves every use in it. */
    private val added = "fn pub extra -> Int {\n  2\n}\n\n"

    private lateinit var protocol: Path
    private lateinit var utilFile: VirtualFile
    private lateinit var mainFile: VirtualFile

    override fun setUp() {
        super.setUp()
        val executable = System.getenv("CUTTLEBONE")?.takeIf { it.isNotEmpty() }
            ?: run { InkoProjectSettings.getInstance(project).cuttlebone = ""; Cuttlebone.executable(project)?.toString() }
        assertNotNull("cuttlebone must be installed (or CUTTLEBONE set) to run these tests", executable)
        // Outside the test's directory, which goes at the end of the test.
        protocol = Files.createTempDirectory("parakeet-protocol-${getTestName(true)}-")
        val script = protocol.resolve("cuttlebone")
        Files.writeString(script, """
            |#!/bin/sh
            |case "${'$'}{1-}" in --version) exec '$executable' "${'$'}@" ;; esac
            |tee -a '${protocol.resolve("sent.jsonrpc")}' | '$executable' "${'$'}@" | tee -a '${protocol.resolve("received.jsonrpc")}'
            |""".trimMargin())
        script.toFile().setExecutable(true)
        println("Testing against cuttlebone at $executable; what it was sent and answered is in $protocol")
        putTempDirInProjectContent()
        useCuttlebone(script.toString())
        utilFile = myFixture.addFileToProject("src/app/util.inko", util).virtualFile
        mainFile = myFixture.addFileToProject("src/main.inko", main).virtualFile
    }

    private fun document(file: VirtualFile): Document = FileDocumentManager.getInstance().getDocument(file)!!

    private fun open(file: VirtualFile) {
        myFixture.configureFromExistingVirtualFile(file)
        waitUntilSynced(file)
    }

    /**
     * Runs what the change left for later, as the IDE does before the next action, and waits until Cuttlebone has
     * [file]'s text as it is: its document's latest version, or, with none sent, the disk's.
     */
    private fun waitUntilSynced(file: VirtualFile) {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        PlatformTestUtil.waitWithEventsDispatching({ "Cuttlebone didn't get ${file.name}'s text: ${CuttleboneService.getInstance(project).status?.message}" }, {
            val server = CuttleboneService.getInstance(project).server()
            server != null && CuttleboneService.getInstance(project).isSynced(server, file, document(file))
        }, 60)
    }

    private fun edit(file: VirtualFile, change: (Document) -> Unit) {
        WriteCommandAction.runWriteCommandAction(project) { change(document(file)) }
        waitUntilSynced(file)
    }

    private fun <T> inBackground(action: () -> T): T {
        val future = ApplicationManager.getApplication().executeOnPooledThread<T> { ReadAction.compute<T, Throwable> { action() } }
        PlatformTestUtil.waitWithEventsDispatching("background action", { future.isDone }, 120)
        return future.get(1, TimeUnit.SECONDS)
    }

    private fun declaration(): InkoDeclaration {
        val psi = PsiManager.getInstance(project).findFile(utilFile)!!
        return PsiTreeUtil.getParentOfType(psi.findElementAt(psi.text.indexOf("value ->")), InkoDeclaration::class.java, false)!!
    }

    /** Every `value` in the two files' text as it is now, as `file:offset`: the declaration first. */
    private fun inText(): List<String> = listOf(utilFile, mainFile).flatMap { file ->
        Regex("\\bvalue\\b").findAll(document(file).text).map { "${file.name}:${it.range.first}" }.toList()
    }

    /** Cuttlebone's references to `value`, the declaration included, as `file:offset`. */
    private fun cuttlebone(): List<String> = inBackground {
        val declaration = declaration()
        val answer = CuttleboneQueries.getInstance(project).references(declaration.containingFile, declaration.textRange.startOffset, 60_000)
        assertNotNull("Cuttlebone has no references", answer?.value)
        answer!!.value!!.map { location ->
            val file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByNioFile(Path.of(java.net.URI(location.uri)))!!
            "${file.name}:${CuttleboneQueries.offset(document(file), location.range.start)}"
        }.sorted()
    }

    /** What Find Usages finds for `value`, as the IDE runs it, as `file:offset`. */
    private fun findUsages(): List<String> = inBackground {
        val declaration = declaration()
        val handler = InkoFindUsagesHandlerFactory().createFindUsagesHandler(declaration, false) ?: object : FindUsagesHandler(declaration) {}
        val options = FindUsagesOptions(project).apply {
            isUsages = true
            searchScope = GlobalSearchScope.projectScope(project)
        }
        val found = Collections.synchronizedList(mutableListOf<UsageInfo>())
        handler.processElementUsages(declaration, { found += it; true }, options)
        found.map { "${it.virtualFile!!.name}:${it.segment!!.startOffset}" }.sorted()
    }

    /** Cuttlebone's references and Find Usages describe the text as it is, after [step]. */
    private fun assertAnswersFitTheText(step: String) {
        val expected = inText()
        assertEquals("Cuttlebone's references, $step (see $protocol)", expected.sorted(), cuttlebone())
        assertEquals("Find Usages, $step (see $protocol)", expected.drop(1).sorted(), findUsages())
    }

    /** Changes util.inko, and has Cuttlebone answer for its changed text, so it has checked it. */
    private fun changeUtil() {
        edit(utilFile) { it.insertString(0, added) }
        assertAnswersFitTheText("with util.inko changed")
    }

    fun testChangedBackThroughTheDocument() {
        open(utilFile)
        assertAnswersFitTheText("at the start")
        changeUtil()
        edit(utilFile) { it.deleteString(0, added.length) }
        assertAnswersFitTheText("after util.inko was changed back through its document")
    }

    fun testChangedBackByUndo() {
        open(utilFile)
        assertAnswersFitTheText("at the start")
        changeUtil()
        val editor = FileEditorManager.getInstance(project).getSelectedEditor(utilFile)
        com.intellij.openapi.command.undo.UndoManager.getInstance(project).undo(editor)
        assertEquals(util, document(utilFile).text)
        waitUntilSynced(utilFile)
        assertAnswersFitTheText("after util.inko was changed back by undo")
    }

    // As the sweep put a project back: changed in an editor and saved, the editor closed, then put back through
    // the document with no editor open, and saved.
    fun testChangedBackWithItsEditorClosed() {
        open(utilFile)
        assertAnswersFitTheText("at the start")
        changeUtil()
        FileDocumentManager.getInstance().saveAllDocuments()
        FileEditorManager.getInstance(project).closeFile(utilFile)
        WriteCommandAction.runWriteCommandAction(project) { document(utilFile).setText(util) }
        FileDocumentManager.getInstance().saveAllDocuments()
        assertEquals(util, String(utilFile.contentsToByteArray()))
        open(mainFile)
        waitUntilSynced(utilFile)
        assertAnswersFitTheText("after util.inko was changed back with its editor closed")
    }

    // Opened again afterwards, as the next site opens the file it's in.
    fun testChangedBackWithItsEditorClosedThenOpenedAgain() {
        open(utilFile)
        changeUtil()
        FileDocumentManager.getInstance().saveAllDocuments()
        FileEditorManager.getInstance(project).closeFile(utilFile)
        WriteCommandAction.runWriteCommandAction(project) { document(utilFile).setText(util) }
        FileDocumentManager.getInstance().saveAllDocuments()
        open(utilFile)
        assertAnswersFitTheText("after util.inko was changed back with its editor closed, and opened again")
    }

    /** Every [name] in the two files' text as it is now, as `file:offset`. */
    private fun named(name: String): List<String> = listOf(utilFile, mainFile).flatMap { file ->
        Regex("\\b$name\\b").findAll(document(file).text).map { "${file.name}:${it.range.first}" }.toList()
    }

    // In the IDE: a Rename that changes both files (main.inko not open, so its document isn't sent), undone.
    fun testRenamedThenUndone() {
        open(utilFile)
        assertAnswersFitTheText("at the start")
        myFixture.renameElement(declaration(), "amount")
        assertEquals(emptyList<String>(), named("value"))
        assertEquals(5, named("amount").size)
        FileDocumentManager.getInstance().saveAllDocuments()
        waitUntilSynced(utilFile)
        val editor = FileEditorManager.getInstance(project).getSelectedEditor(utilFile)
        // Undoing a change to several files asks first.
        val dialog = com.intellij.openapi.ui.TestDialogManager.setTestDialog(com.intellij.openapi.ui.TestDialog.OK)
        try {
            com.intellij.openapi.command.undo.UndoManager.getInstance(project).undo(editor)
        } finally {
            com.intellij.openapi.ui.TestDialogManager.setTestDialog(dialog)
        }
        assertEquals(util, document(utilFile).text)
        assertEquals(main, document(mainFile).text)
        waitUntilSynced(utilFile)
        assertAnswersFitTheText("after a Rename was undone, main.inko unsaved")
        FileDocumentManager.getInstance().saveAllDocuments()
        waitUntilSynced(utilFile)
        assertAnswersFitTheText("after a Rename was undone, and saved")
    }

    // A file with no editor (one a refactoring, or undoing one, changes): IntelliJ sends Cuttlebone only the
    // documents open in an editor, so Cuttlebone reads this one from disk.
    fun testChangedBackInAFileWithNoEditor() {
        open(utilFile)
        assertAnswersFitTheText("at the start")
        WriteCommandAction.runWriteCommandAction(project) { document(mainFile).insertString(0, "# A comment.\n\n") }
        FileDocumentManager.getInstance().saveAllDocuments()
        waitUntilSynced(utilFile)
        assertAnswersFitTheText("with main.inko changed and saved")
        WriteCommandAction.runWriteCommandAction(project) { document(mainFile).setText(main) }
        waitUntilSynced(utilFile)
        assertAnswersFitTheText("after main.inko was changed back, with no editor")
    }
}
