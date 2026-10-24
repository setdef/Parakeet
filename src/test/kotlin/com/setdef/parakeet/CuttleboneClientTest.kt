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

    fun testTurnsOffCuttlebonesDiagnostics() {
        // Errors are Parakeet's (`inko check`), so Cuttlebone's full checks would be for nothing.
        useFake(answered())
        open("src/main.inko", text)
        runningServer(waitForOpen = false)
        val options = received("initialize").single().getAsJsonObject("params").getAsJsonObject("initializationOptions")
        assertFalse(options.toString(), options.get("diagnostics")?.asBoolean ?: true)
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
        assertNull("the fake answers anywhere else with status unavailable", query(marker = "x ="))
        assertEquals(1, received(definition).size)
    }

    fun testWithoutAServerThereIsNoAnswerAndNothingStarts() {
        open("src/main.inko", text)
        waitFor("the precheck") { CuttleboneService.getInstance(project).status != null }
        assertTrue(CuttleboneService.getInstance(project).status is Cuttlebone.Status.NotFound)
        assertNull(query())
        assertNull(CuttleboneService.getInstance(project).server())
    }

    fun testAVersionMismatchNeverStartsIt() {
        useFake(answered(), version = "cuttlebone 0.1.0 (Inko 0.19.0)")
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

    fun testCancellingTheWaitCancelsTheRequest() {
        useFake(answered().copy(hang = true))
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
        waitFor("\$/cancelRequest") { received("\$/cancelRequest").isNotEmpty() }
    }

    fun testHighlightingNeverWaits() {
        useFake(answered().copy(delayMs = 500))
        open("src/main.inko", text)
        runningServer()
        val file = myFixture.file
        val answer = inBackground {
            val indicator = com.intellij.codeInsight.daemon.impl.DaemonProgressIndicator()
            ProgressManager.getInstance().runProcess<Any?>({ CuttleboneQueries.getInstance(project).definition(file, text.indexOf("to_string"), 5000) }, indicator)
        }
        assertNull(answer)
        waitFor("the answer, for the next pass") { query(budgetMs = 0) != null }
    }

    fun testStopsWithShutdownAndExit() {
        useFake(answered())
        open("src/main.inko", text)
        runningServer()
        CuttleboneService.getInstance(project).reset()
        waitFor("shutdown and exit") { received("shutdown").isNotEmpty() && received("exit").isNotEmpty() }
    }
}
