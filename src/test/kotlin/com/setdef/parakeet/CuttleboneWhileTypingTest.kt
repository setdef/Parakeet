package com.setdef.parakeet

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzerSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.util.ProgressIndicatorUtils
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PlatformTestUtil

/**
 * What's asked of Cuttlebone while a person types. In code being typed, each question about new text costs
 * Cuttlebone a check (up to three, when it doesn't parse), so what runs on every keystroke asks only once the
 * text is still (CuttleboneTypingReplayIntegrationTest measures it against the real server).
 */
class CuttleboneWhileTypingTest : InkoTestCase(onDisk = true) {
    private val text = "fn a {\n  let count = 1\n  count + count\n}\n"

    /** The methods asked, in order. */
    private val asked = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun requests() = asked.map { it.removePrefix("textDocument/") }

    private fun handler(): InkoHighlightUsagesHandler {
        val name = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(text.indexOf("count +")), InkoName::class.java, false)!!
        return InkoHighlightUsagesHandlerFactory().createHighlightUsagesHandler(myFixture.editor, myFixture.file, name) as InkoHighlightUsagesHandler
    }

    private fun type(at: Int, what: String) {
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(at, what) }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    /** Runs the pass as IntelliJ does: in the background, in a read action that an edit cancels. */
    private fun inBackground(handler: InkoHighlightUsagesHandler) = ApplicationManager.getApplication().executeOnPooledThread {
        ProgressIndicatorUtils.runInReadActionWithWriteActionPriority { handler.computeUsages(handler.targets) }
    }

    override fun setUp() {
        super.setUp()
        putTempDirInProjectContent()
        stubCuttlebone { method, file, _ ->
            asked += method
            CuttleboneQueries.Answer(emptyList<Any>(), CuttleboneStatus(method, file.url, "fresh", null, 1.0))
        }
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("src/main.inko", text).virtualFile)
    }

    // The usage highlighting pass runs each time the caret moves, so on every keystroke, for the name being typed.
    fun testUsageHighlightingAsksOnceTheTextIsStill() {
        val edited = System.nanoTime()
        type(text.length, "\n")
        val handler = handler()
        val future = inBackground(handler)
        PlatformTestUtil.waitWithEventsDispatching("usage highlighting", { future.isDone }, TimingLimits.seconds(10))
        val waitedMs = (System.nanoTime() - edited) / 1_000_000
        assertTrue("asked after $waitedMs ms", waitedMs >= DaemonCodeAnalyzerSettings.getInstance().autoReparseDelay)
        assertEquals("the highlights, then the caret's definition", listOf("documentHighlight", "definition"), requests())
    }

    fun testUsageHighlightingDoesntAskAboutTextThatChangesMeanwhile() {
        type(text.length, "\n")
        val handler = handler()
        val future = inBackground(handler)
        Thread.sleep(100)
        // The next keystroke: the pass that's waiting has nothing to show for the text it was given.
        ApplicationManager.getApplication().invokeAndWait { type(myFixture.editor.document.textLength, "\n") }
        PlatformTestUtil.waitWithEventsDispatching("usage highlighting", { future.isDone }, TimingLimits.seconds(10))
        assertEquals(emptyList<String>(), requests())
    }

    // In code Cuttlebone can't check, the definition at the same place has no answer either.
    fun testUsageHighlightingDoesntPrefetchWhereTheCodeCantBeChecked() {
        stubCuttlebone { method, file, _ ->
            asked += method
            CuttleboneQueries.Answer<Any>(null, CuttleboneStatus(method, file.url, "unavailable", "noHir", 1.0))
        }
        val handler = handler()
        inBackground(handler).let { future ->
            PlatformTestUtil.waitWithEventsDispatching("usage highlighting", { future.isDone }, TimingLimits.seconds(10))
        }
        assertEquals("the highlights only", listOf("documentHighlight"), requests())
    }
}
