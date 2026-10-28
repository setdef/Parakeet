package com.setdef.parakeet

import com.intellij.codeInsight.daemon.impl.MockWolfTheProblemSolver
import com.intellij.codeInsight.daemon.impl.WolfTheProblemSolverImpl
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.problems.WolfTheProblemSolver
import com.intellij.testFramework.InspectionTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.createGlobalContextForTool

/** Project-wide errors, from Cuttlebone's pulls (CuttleboneIntegrationTest has the real one's). */
class InkoProjectCheckTest : InkoTestCase(onDisk = true) {
    private val diagnostics = mapOf(
        "src/app/broken.inko" to listOf(InkoCheckTest.diagnostic(1, 2, 6, "the symbol 'nope' is undefined", "invalid-symbol")),
        "src/app/warned.inko" to listOf(InkoCheckTest.diagnostic(0, 18, 24, "the symbol 'Stdout' is unused", "unused-symbol", warning = true)),
    )

    override fun setUp() {
        super.setUp()
        putTempDirInProjectContent()
        myFixture.addFileToProject("inko.pkg", "")
        myFixture.addFileToProject("src/app/broken.inko", "fn pub broken -> Int {\n  nope\n}\n")
        myFixture.addFileToProject("src/app/fine.inko", "fn pub fine -> Int {\n  1\n}\n")
        myFixture.addFileToProject("src/app/warned.inko", "import std.stdio (Stdout)\n\nfn pub warned -> Int {\n  1\n}\n")
        stubCuttlebone { method, file, _ ->
            val pulled = diagnostics.entries.firstOrNull { file.path.endsWith("/${it.key}") }?.value.orEmpty()
            CuttleboneQueries.Answer(pulled.takeIf { method == "textDocument/diagnostic" }, null)
        }
    }

    /** The files marked in this test's project (the light test project, and its services, outlive each test). */
    private fun InkoProjectCheck.marked() = problemFiles().filter { it.path.startsWith(myFixture.tempDirPath) }.toSet()

    /** A workspace pull's report: [files]' diagnostics, in full. */
    private fun report(files: Map<String, List<org.eclipse.lsp4j.Diagnostic>>) = org.eclipse.lsp4j.WorkspaceDiagnosticReport(files.map { (path, items) ->
        org.eclipse.lsp4j.WorkspaceDocumentDiagnosticReport(org.eclipse.lsp4j.WorkspaceFullDocumentDiagnosticReport(items, myFixture.findFileInTempDir(path).url, null).apply { resultId = path })
    })

    // Light tests replace the IDE's problem-file service with a mock, so this checks what's reported to it.
    fun testMarksFilesWithErrorsAsProblems() {
        val check = InkoProjectCheck.getInstance(project)
        check.update(report(diagnostics))
        // Warnings aren't problems, and clean files aren't either.
        assertEquals(setOf(myFixture.findFileInTempDir("src/app/broken.inko")), check.marked())

        // Once it's fixed, the mark goes.
        check.update(report(mapOf("src/app/broken.inko" to emptyList())))
        assertEmpty(check.marked())
    }

    // A report that says a file is unchanged leaves its mark as it was.
    fun testAnUnchangedReportKeepsTheMark() {
        val check = InkoProjectCheck.getInstance(project)
        check.update(report(diagnostics))
        val broken = myFixture.findFileInTempDir("src/app/broken.inko")
        check.update(org.eclipse.lsp4j.WorkspaceDiagnosticReport(listOf(
            org.eclipse.lsp4j.WorkspaceDocumentDiagnosticReport(org.eclipse.lsp4j.WorkspaceUnchangedDocumentDiagnosticReport("src/app/broken.inko", broken.url, null)),
        )))
        assertEquals(setOf(broken), check.marked())
    }

    // Against the IDE's real problem-file service, which (from 2025.3) asserts it's called off the EDT, as pulls are.
    fun testReportsToTheRealProblemSolverOffTheEdt() {
        val mock = WolfTheProblemSolver.getInstance(project) as MockWolfTheProblemSolver
        mock.setDelegate(WolfTheProblemSolverImpl.createTestInstance(project))
        try {
            val check = InkoProjectCheck.getInstance(project)
            val broken = myFixture.findFileInTempDir("src/app/broken.inko")
            offTheEdt { check.update(report(diagnostics)) }
            assertEquals(setOf(broken), check.marked())
            assertTrue("the Project view and the Problems tool window mark it", mock.isProblemFile(broken))

            // Fixed: the mark is cleared the same way.
            offTheEdt { check.update(report(mapOf("src/app/broken.inko" to emptyList()))) }
            assertEmpty(check.marked())
            assertFalse(mock.isProblemFile(broken))
        } finally {
            mock.resetDelegate()
        }
    }

    /** Runs [action] on a pooled thread, running what it hands the EDT (where the test runs) meanwhile. */
    private fun offTheEdt(action: () -> Unit) {
        val future = com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread(action)
        PlatformTestUtil.waitWithEventsDispatching("the report didn't finish", { future.isDone }, TimingLimits.seconds(30))
        future.get()
    }

    fun testInspectCodeReportsEveryModulesDiagnostics() {
        val tool = LocalInspectionToolWrapper(InkoCompilerInspection())
        val scope = com.intellij.analysis.AnalysisScope(project)
        val context = createGlobalContextForTool(scope, project, listOf(tool))
        InspectionTestUtil.runTool(tool, scope, context)
        val problems = context.getPresentation(tool).problemDescriptors.map { it.descriptionTemplate }.sorted()
        assertEquals(listOf("the symbol 'Stdout' is unused", "the symbol 'nope' is undefined"), problems)
    }

    fun testSilentWhileEditing() {
        myFixture.enableInspections(InkoCompilerInspection())
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("src/app/broken.inko"))
        val ours = myFixture.doHighlighting().filter { it.inspectionToolId == "InkoCompiler" }
        assertEmpty(ours)
    }
}
