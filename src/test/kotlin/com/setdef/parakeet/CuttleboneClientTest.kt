package com.setdef.parakeet

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiFile

/** Starting, syncing, restarting and asking Cuttlebone, with a fake server. */
class CuttleboneClientTest : CuttleboneTestCase() {
    private val text = "fn pub main {\n  let x = 1\n  x.to_string\n}\n"
    private val definition = "textDocument/definition"

    private fun query(file: PsiFile = myFixture.file, marker: String = "to_string", budgetMs: Long = 2000) =
        inBackground { CuttleboneQueries.getInstance(project).definition(file, text.indexOf(marker), budgetMs) }

    private fun answered() = FakeCuttlebone.Script(log = "", answers = listOf(
        answerAt(text, "to_string", definition, listOf(locationAt("src/main.inko", text, "x =")))))

    fun testStartsWhenAnInkoFileOpensAndSyncsItsText() {
        useFake(answered())
        assertEquals("nothing starts before a file opens", 0, launches())
        open("src/main.inko", text)
        runningServer()

        val initialize = received("initialize").single().getAsJsonObject("params")
        assertTrue(initialize.getAsJsonObject("initializationOptions").get("statusNotifications").asBoolean)
        assertEquals("[\"utf-16\"]", initialize.getAsJsonObject("capabilities").getAsJsonObject("general").get("positionEncodings").toString())
        waitFor("didOpen") { received("textDocument/didOpen").isNotEmpty() }
        assertEquals(text, received("textDocument/didOpen").single().getAsJsonObject("params").getAsJsonObject("textDocument").get("text").asString)

        // Unsaved edits are synced as they happen.
        myFixture.editor.caretModel.moveToOffset(text.indexOf("1\n"))
        myFixture.type("41 + ")
        // Typed a character at a time; incremental changes, each with its text.
        waitFor("didChange") {
            received("textDocument/didChange").joinToString("") { change ->
                change.getAsJsonObject("params").getAsJsonArray("contentChanges").joinToString("") { it.asJsonObject.get("text").asString }
            }.contains("41 + ")
        }
    }

    // Errors are Cuttlebone's, which Parakeet pulls (so Cuttlebone pushes none, and checks after edits for the pulls
    // to answer), and moving files asks Cuttlebone for the edits.
    fun testPullsDiagnosticsAndAsksForFileRenames() {
        useFake(answered())
        open("src/main.inko", text)
        runningServer(waitForOpen = false)
        val params = received("initialize").single().getAsJsonObject("params")
        assertNull("diagnostics aren't turned off", params.getAsJsonObject("initializationOptions").get("diagnostics"))
        val capabilities = params.getAsJsonObject("capabilities")
        assertTrue(capabilities.toString(), capabilities.getAsJsonObject("textDocument").has("diagnostic"))
        assertTrue(capabilities.toString(), capabilities.getAsJsonObject("workspace").getAsJsonObject("fileOperations").get("willRename").asBoolean)
    }

    fun testAnEditReachesTheServerBeforeTheNextQuery() {
        useFake(answered())
        open("src/main.inko", text)
        runningServer(waitForOpen = false)
        // Edited right after the server started, before IntelliJ may have sent didOpen:
        // the server might only have the file on disk, so no question is asked.
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(0, "\n") }
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        if (!synced()) assertNull(query(marker = "x ="))
        waitFor("the editor's text to be synced") { synced() }
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.deleteString(0, 1) }
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.replaceString(text.indexOf("1\n"), text.indexOf("1\n") + 1, "42")
        }
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        query()
        val methods = messages().mapNotNull { it.get("method")?.asString }
        assertTrue(methods.toString(), methods.indexOf("textDocument/didOpen") in 0 until methods.indexOf(definition))
        assertTrue(methods.toString(), methods.lastIndexOf("textDocument/didChange") in 0 until methods.indexOf(definition))
    }

    // IntelliJ 2026.2 writes a saved document to disk after the save returns (vfs.async-content-write), and Cuttlebone
    // reads the files no editor has from disk: a refactoring's edits there must be on disk before the next question.
    fun testASavedFileIsOnDiskBeforeTheNextQuery() {
        val other = myFixture.addFileToProject("src/other.inko", "# 0\n" + "# padding\n".repeat(400_000)).virtualFile
        useFake(answered())
        open("src/main.inko", text)
        runningServer()
        val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(other)!!
        var edit = 0
        val stale = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val queries = CuttleboneQueries.getInstance(project)
        queries.trace = object : CuttleboneQueries.Trace {
            override fun sent(request: Long, method: String, document: com.intellij.openapi.editor.Document) {
                if (!java.nio.file.Files.readString(java.nio.file.Path.of(other.path)).startsWith("# $edit\n")) stale += edit
            }
            override fun answered(request: Long, status: CuttleboneStatus?) {}
            override fun failed(request: Long, error: Throwable) {}
        }
        try {
            for (next in 1..20) {
                edit = next
                com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) { document.replaceString(0, document.text.indexOf('\n'), "# $next") }
                com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
                com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveDocument(document)
                assertNotNull("edit $next: no answer", query())
            }
        } finally {
            queries.trace = null
        }
        assertEquals("the edits Cuttlebone was asked after with an older file on disk", emptyList<Int>(), stale)
    }

    fun testAnEditedFileIsAskedAboutAsSoonAsItsTextIsSent() {
        useFake(answered())
        open("src/main.inko", text)
        // Edited before the server starts, so only didOpen can give the server this text.
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.replaceString(text.indexOf("1\n"), text.indexOf("1\n") + 1, "2")
        }
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        runningServer(waitForOpen = false)
        waitFor("didOpen") { received("textDocument/didOpen").isNotEmpty() }
        val sent = received("textDocument/didOpen").single().getAsJsonObject("params").getAsJsonObject("textDocument").get("text").asString
        assertEquals(text.replace("1\n", "2\n"), sent)
        assertNotNull("asked as soon as didOpen was sent, with no grace period", inBackground {
            CuttleboneQueries.getInstance(project).definition(myFixture.file, text.indexOf("to_string"), 2000)
        })
    }

    fun testAnswersAreFreshLocationsAndCached() {
        useFake(answered())
        open("src/main.inko", text)
        runningServer()
        val answer = query()!!
        assertEquals("1:6", answer.value!!.single().range.start.let { "${it.line}:${it.character}" })
        assertEquals("fresh", answer.status?.status)
        query()
        assertEquals("asked again from the cache", 1, received(definition).size)
    }

    fun testUnavailableIsNoAnswer() {
        useFake(answered())
        open("src/main.inko", text)
        runningServer()
        // The fake answers anywhere else with status unavailable, reason noHir: code that doesn't parse.
        val answer = query(marker = "x =")!!
        assertNull(answer.value)
        assertTrue("noHir: the code is being edited", answer.editing)
        assertEquals(1, received(definition).size)
    }

    fun testUnavailableForAnotherReasonIsNotEditing() {
        useFake(FakeCuttlebone.Script(log = "", answers = listOf(answerAt(text, "to_string", definition, null, status = "unavailable").copy(reason = "timeout"))))
        open("src/main.inko", text)
        runningServer()
        assertNull("no answer, and nothing to fall back on", query())
    }

    fun testWithoutAServerThereIsNoAnswerAndNothingStarts() {
        open("src/main.inko", text)
        waitFor("the precheck") { CuttleboneService.getInstance(project).status != null }
        assertTrue(CuttleboneService.getInstance(project).status is Cuttlebone.Status.NotFound)
        assertNull(query())
        assertNull(CuttleboneService.getInstance(project).server())
    }

    fun testWithoutCuttleboneASetupMessageIsShownOnce() {
        fun shown() = com.intellij.notification.NotificationsManager.getNotificationsManager()
            .getNotificationsOfType(com.intellij.notification.Notification::class.java, project).filter { it.title == "Parakeet needs Cuttlebone" }
        shown().forEach { it.expire() }
        open("src/main.inko", text)
        waitFor("the setup message") { shown().isNotEmpty() }
        open("src/other.inko", text)
        CuttleboneService.getInstance(project).canStart()
        com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val notification = shown().single()
        assertTrue(notification.content, notification.content.contains("isn't installed") || notification.content.contains("wasn't found"))
        assertEquals(listOf("How to set up Cuttlebone", "Settings…"), notification.actions.map { it.templateText })
        assertTrue(CuttleboneService.getInstance(project).unavailable)
        notification.expire()
    }

    fun testAVersionMismatchNeverStartsIt() {
        useFake(answered(), version = "cuttlebone 0.14.0 (Inko 0.19.0)")
        useFakeInko(FakeInkoCli { call -> if (call.args == listOf("--version")) FakeInkoCli.ok("inko 0.21.1\n") else FakeInkoCli.ok(stderr = "[]") })
        open("src/main.inko", text)
        waitFor("the precheck") { CuttleboneService.getInstance(project).status != null }
        val status = CuttleboneService.getInstance(project).status
        assertTrue(status?.message, status is Cuttlebone.Status.Refused)
        assertTrue(status!!.message, status.message.contains("works with Inko 0.19.0 only, but the project uses Inko 0.21.1"))
        assertEquals(0, launches())
        assertNull(query())
    }

    fun testARefusedInitializeIsSaidOnceAndNotRetried() {
        useFake(FakeCuttlebone.Script(log = "", initializeError = mapOf(
            "code" to -32803,
            "message" to "Cuttlebone 0.1.0 works with Inko 0.21.1 only, but inko is Inko 0.20.0.",
            "data" to mapOf("retry" to false, "code" to "inkoVersionMismatch"),
        )))
        open("src/main.inko", text)
        waitFor("the refusal") { CuttleboneService.getInstance(project).status is Cuttlebone.Status.Refused }
        // Opening another file doesn't try again.
        open("src/other.inko", "fn pub other {}\n")
        Thread.sleep(1500)
        assertEquals(1, launches())
        assertNull(query())
    }

    fun testRestartsAfterACrash() {
        useFake(answered().copy(crashLaunches = 1))
        open("src/main.inko", text)
        runningServer()
        assertNull("the first launch crashes on the first query", query())
        waitFor("the restart") { launches() == 2 && CuttleboneService.getInstance(project).server() != null }
        waitFor("an answer from the restarted server") { query() != null }
        assertEquals(2, launches())
    }

    fun testAHungServerFallsBackWithinTheBudget() {
        useFake(answered().copy(hang = true))
        open("src/main.inko", text)
        runningServer()
        val start = System.nanoTime()
        assertNull(query(budgetMs = 300))
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("waited $elapsedMs ms", elapsedMs < 1500)
    }

    fun testASlowAnswerIsUsedNextTime() {
        useFake(answered().copy(delayMs = 800))
        open("src/main.inko", text)
        runningServer()
        assertNull("over budget", query(budgetMs = 100))
        waitFor("the late answer") { query(budgetMs = 0) != null }
        assertEquals("the late answer was cached, not asked for again", 1, received(definition).size)
    }

    // Cancelling a running query kills Cuttlebone's worker, and every check it kept with it (milestone 6), so
    // the next question would pay a first check again: moving the caret, which cancels the usage highlighting
    // pass, would keep the file from ever being checked in time. The request finishes, for the next pass.
    fun testCancellingTheWaitLeavesTheRequestToFinish() {
        useFake(answered().copy(delayMs = 800))
        open("src/main.inko", text)
        runningServer()
        val file = myFixture.file
        val indicator = EmptyProgressIndicator()
        val cancelled = inBackground {
            Thread { Thread.sleep(200); indicator.cancel() }.start()
            try {
                ProgressManager.getInstance().runProcess<Any?>({ CuttleboneQueries.getInstance(project).definition(file, text.indexOf("to_string"), 10_000) }, indicator)
                false
            } catch (_: ProcessCanceledException) {
                true
            }
        }
        assertTrue(cancelled)
        waitFor("the answer, for the next pass") { query(budgetMs = 0)?.value != null }
        assertEquals(emptyList<Any>(), received("\$/cancelRequest"))
        assertEquals(1, received(definition).size)
    }

    private fun edit() {
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(myFixture.editor.document.text.indexOf("1\n"), "4") }
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    private fun cancelled() = received("\$/cancelRequest").map { it.getAsJsonObject("params").get("id") }

    // Since milestone 7, Cuttlebone answers every request for the text it was asked about, even after an edit,
    // at a check each: a request about text that has changed since is one nobody will see, so it's cancelled,
    // the one Cuttlebone is checking as well as those queued behind it.
    fun testAnEditCancelsTheRequestsAboutTheTextBefore() {
        useFake(answered().copy(hang = true))
        open("src/main.inko", text)
        runningServer()
        waitFor("didOpen") { received("textDocument/didOpen").isNotEmpty() }
        assertNull("no answer yet", query(budgetMs = 0))
        assertNull("no answer yet", query(marker = "x =", budgetMs = 0))
        waitFor("the requests") { received(definition).size == 2 }
        edit()
        // Well within IntelliJ's own 30 s request timeout, which also cancels.
        waitFor("the requests to be cancelled", timeoutSeconds = 5) { cancelled().size == 2 }
        assertEquals(received(definition).map { it.get("id") }.toSet(), cancelled().toSet())
    }

    // A search (Find Usages, Rename) waits for its answer outside highlighting, and shows what it finds: an edit
    // meanwhile doesn't take its answer away.
    fun testAnEditLeavesTheRequestsASearchIsWaitingFor() {
        useFake(answered().copy(delayMs = 1500))
        open("src/main.inko", text)
        runningServer()
        waitFor("didOpen") { received("textDocument/didOpen").isNotEmpty() }
        val file = myFixture.file
        val offset = text.indexOf("to_string")
        val search = com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread<CuttleboneQueries.Answer<*>?> {
            com.intellij.openapi.application.ReadAction.compute<CuttleboneQueries.Answer<*>?, Throwable> {
                CuttleboneQueries.getInstance(project).definition(file, offset, CuttleboneQueries.SEARCH_BUDGET_MS)
            }
        }
        waitFor("the request") { received(definition).size == 1 }
        edit()
        waitFor("the search's answer") { search.isDone }
        assertNotNull(search.get()?.value)
        assertEquals(emptyList<Any>(), received("\$/cancelRequest"))
    }

    fun testHighlightingDoesntWaitForWhatOnlyTheEditorsActionsShow() {
        val hover = "textDocument/hover"
        useFake(answered().copy(delayMs = 500, answers = answered().answers + answerAt(text, "to_string", hover, mapOf("contents" to "Int"))))
        open("src/main.inko", text)
        runningServer()
        val file = myFixture.file
        fun hover() = CuttleboneQueries.getInstance(project).hover(file, text.indexOf("to_string"), 5000)
        val answer = inBackground { inHighlighting { hover() } }
        assertNull(answer)
        waitFor("the answer, for the next pass") { inBackground { inHighlighting { hover() } } != null }
    }

    // Inspections and intentions resolve names in the highlighting passes, and after a file's first check
    // Cuttlebone answers from the check it kept, so resolving waits there, within its budget.
    fun testHighlightingWaitsForADefinitionWithinItsBudget() {
        useFake(answered().copy(delayMs = 200))
        open("src/main.inko", text)
        runningServer()
        val file = myFixture.file
        val answer = inBackground { inHighlighting { CuttleboneQueries.getInstance(project).definition(file, text.indexOf("to_string")) } }
        assertNotNull(answer?.value)
    }

    private fun <T> inHighlighting(action: () -> T): T =
        ProgressManager.getInstance().runProcess<T>({ action() }, com.intellij.codeInsight.daemon.impl.DaemonProgressIndicator())

    // Parakeet stops Cuttlebone gracefully: IntelliJ sends `shutdown`, waits 10 s for its answer (Lsp4jServerConnector,
    // a fixed time no wait here covers), sends `exit` if the answer came, and disconnects. A fake starved of CPU can
    // miss those 10 s, and be gone before it has read either, so what was written to it (CuttleboneInputTap) is
    // checked, not what it logged: `shutdown`, a stop rather than a kill, and no server left.
    fun testStopsWithShutdown() {
        useFake(answered())
        open("src/main.inko", text)
        runningServer()
        // The service outlives each test: only this stop's messages count.
        val sent = CuttleboneService.getInstance(project).sentMessages.apply { clear() }
        CuttleboneService.getInstance(project).reset()
        waitFor("shutdown, and the server stopped") { (sent["shutdown"]?.get() ?: 0) > 0 && CuttleboneService.getInstance(project).server() == null }
    }
}
