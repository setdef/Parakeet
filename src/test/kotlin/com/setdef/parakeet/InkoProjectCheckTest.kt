package com.setdef.parakeet

import com.intellij.codeInsight.daemon.impl.MockWolfTheProblemSolver
import com.intellij.codeInsight.daemon.impl.WolfTheProblemSolverImpl
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.problems.WolfTheProblemSolver
import com.intellij.testFramework.InspectionTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.createGlobalContextForTool
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

/** Project-wide errors, with `inko check`'s output supplied by a fake. */
class InkoProjectCheckTest : InkoTestCase(onDisk = true) {
    private fun diagnostic(file: String, line: Int, columns: IntRange, message: String, level: String = "error") =
        """{"id": "x", "level": "$level", "file": "$file", "lines": [$line, $line], "columns": [${columns.first}, ${columns.last}], "message": "$message"}"""

    override fun setUp() {
        super.setUp()
        putTempDirInProjectContent()
        myFixture.addFileToProject("inko.pkg", "")
        myFixture.addFileToProject("src/app/broken.inko", "fn pub broken -> Int {\n  nope\n}\n")
        myFixture.addFileToProject("src/app/fine.inko", "fn pub fine -> Int {\n  1\n}\n")
        myFixture.addFileToProject("src/app/warned.inko", "import std.stdio (Stdout)\n\nfn pub warned -> Int {\n  1\n}\n")
        useFakeInko(FakeInkoCli { call ->
            if (call.args.take(3) != listOf("check", "--format", "json") || call.args.size != 3) return@FakeInkoCli FakeInkoCli.ok(stderr = "[]")
            val mirror = call.workingDirectory!!
            FakeInkoCli.failed(
                "[${diagnostic(mirror.resolve("src/app/broken.inko").toString(), 2, 3..6, "the symbol 'nope' is undefined")}," +
                    "${diagnostic(mirror.resolve("src/app/warned.inko").toString(), 1, 19..24, "the symbol 'Stdout' is unused", "warning")}," +
                    "${diagnostic("/std/string.inko", 1, 1..1, "not ours")}]",
            )
        })
    }

    private val root: Path get() = Path.of(myFixture.tempDirPath)

    fun testChecksTheWholeProjectAndKeepsItsOwnModules() {
        val result = InkoProjectCheck.getInstance(project).check(root)!!
        assertEquals(setOf(root.resolve("src/app/broken.inko"), root.resolve("src/app/warned.inko")), result.diagnostics.keys)
    }

    // Light tests replace the IDE's problem-file service with a mock, so this checks what's reported to it.
    fun testMarksFilesWithErrorsAsProblems() {
        val check = InkoProjectCheck.getInstance(project)
        check.report(root, check.check(root)!!)
        // Warnings aren't problems, and clean files aren't either.
        assertEquals(setOf(myFixture.findFileInTempDir("src/app/broken.inko")), check.problemFiles(root))

        // Once it's fixed, the mark goes.
        check.report(root, InkoProjectCheck.Result(emptyMap(), 0))
        assertEmpty(check.problemFiles(root))
    }

    // The background check a save or a project opening schedules, against the IDE's real problem-file service,
    // which (from 2025.3) asserts it's called off the EDT.
    fun testScheduledChecksReportToTheRealProblemSolverOffTheEdt() {
        val mock = WolfTheProblemSolver.getInstance(project) as MockWolfTheProblemSolver
        mock.setDelegate(WolfTheProblemSolverImpl.createTestInstance(project))
        try {
            val check = InkoProjectCheck.getInstance(project)
            val broken = myFixture.findFileInTempDir("src/app/broken.inko")
            check.launch(root, immediately = true).awaitDispatchingEvents()
            assertEquals(setOf(broken), check.problemFiles(root))

            // Fixed: the mark is cleared the same way.
            useFakeInko(FakeInkoCli { FakeInkoCli.ok(stderr = "[]") })
            check.launch(root, immediately = true).awaitDispatchingEvents()
            assertEmpty(check.problemFiles(root))
        } finally {
            mock.resetDelegate()
        }
    }

    /** Waits for the job, running what it hands the EDT (where the test runs) meanwhile. */
    private fun Job.awaitDispatchingEvents() {
        PlatformTestUtil.waitWithEventsDispatching("the project check didn't finish", { isCompleted }, 30)
        runBlocking { join() }
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
