package com.setdef.parakeet

import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.RunConfigurationProducer
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.CapturingProcessAdapter
import com.intellij.execution.process.ProcessOutput
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.psi.PsiElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.TempDirTestFixture
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import java.nio.file.Files
import java.nio.file.Path

/** Run configurations, gutter markers, and console links (needs `inko` installed). */
class InkoRunTest : BasePlatformTestCase() {
    override fun createTempDirTestFixture(): TempDirTestFixture = TempDirTestFixtureImpl()

    private val root: Path get() = Path.of(myFixture.tempDirPath)

    private fun leafAt(path: String, text: String, marker: String): PsiElement {
        val file = myFixture.addFileToProject(path, text)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        return myFixture.file.findElementAt(text.indexOf(marker))!!
    }

    private fun configurationFor(element: PsiElement): InkoRunConfiguration? {
        @Suppress("UNCHECKED_CAST")
        val context = ConfigurationContext.createEmptyContextForLocation(PsiLocation(element) as Location<PsiElement>)
        val producer = RunConfigurationProducer.getInstance(InkoRunConfigurationProducer::class.java)
        return producer.createConfigurationFromContext(context)?.configuration as? InkoRunConfiguration
    }

    private val mainSource = "import std.stdio (Stdout)\n\ntype async Main {\n  fn async main {\n    Stdout.new.print('hello from inko')\n  }\n}\n"

    fun testMainHasRunMarkerAndRunConfiguration() {
        val leaf = leafAt("src/hello.inko", mainSource, "main {")
        assertNotNull(InkoRunTargets.forMarker(leaf))
        val configuration = configurationFor(leaf)!!
        assertEquals(InkoCommand.RUN, configuration.command)
        assertEquals(root.resolve("src/hello.inko").toString(), configuration.options.target)
        assertEquals("Run hello.inko", configuration.name)
    }

    fun testRunningTheConfigurationRunsTheProgram() {
        val configuration = configurationFor(leafAt("src/hello.inko", mainSource, "main {"))!!
        val environment = ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), configuration).build()
        val state = configuration.getState(environment.executor, environment) as InkoRunState
        val handler = state.execute(environment.executor, environment.runner).processHandler
        val output = ProcessOutput()
        handler.addProcessListener(CapturingProcessAdapter(output))
        handler.startNotify()
        assertTrue("inko run timed out", handler.waitFor(120_000))
        assertTrue(output.stdout + output.stderr, output.stdout.contains("hello from inko"))
    }

    private val testSource = "import std.test (Tests)\n\nfn pub tests(t: mut Tests) {\n  t.test('adds numbers', fn (t) { t.equal(1 + 1, 2) })\n}\n"

    fun testTestFunctionRunsTheFilesTests() {
        myFixture.addFileToProject("src/app.inko", "")
        val configuration = configurationFor(leafAt("test/test_app.inko", testSource, "tests("))!!
        assertEquals(InkoCommand.TEST, configuration.command)
        assertEquals(root.resolve("test/test_app.inko").toString(), configuration.options.target)
    }

    fun testEachTestHasItsOwnMarker() {
        myFixture.addFileToProject("src/app.inko", "")
        val leaf = leafAt("test/test_app.inko", testSource, "test('")
        assertNotNull(InkoRunTargets.forMarker(leaf))
        val configuration = configurationFor(leaf)!!
        assertEquals("adds numbers", configuration.options.target)
        assertEquals("Test 'adds numbers'", configuration.name)
    }

    fun testOrdinaryFilesHaveNoMarkers() {
        val leaf = leafAt("src/lib.inko", "fn pub main -> Int {\n  1\n}\n", "main")
        assertNull(InkoRunTargets.forMarker(leaf))
        assertNull(configurationFor(leaf))
    }

    fun testConsoleLinksPathsWithSpaces() {
        val dir = Files.createDirectories(root.resolve("with space/src"))
        val file = Files.writeString(dir.resolve("a.inko"), "\n\nfn a {}\n")
        val line = "  $file:3 in a\n"
        val result = InkoFileLinkFilter(project, root).applyFilter(line, line.length)!!
        val item = result.resultItems.single()
        assertEquals("$file:3", line.substring(item.highlightStartOffset, item.highlightEndOffset))
        val info = item.hyperlinkInfo as OpenFileHyperlinkInfo
        assertEquals(2, info.descriptor!!.offset) // Line 3 of "\n\nfn a {}" starts at offset 2.
    }

    fun testConsoleLinksRelativeCompilerErrors() {
        Files.createDirectories(root.resolve("src"))
        Files.writeString(root.resolve("src/b.inko"), "fn b {}\n")
        val line = "src/b.inko:1:4 error(invalid-symbol): nope\n"
        val item = InkoFileLinkFilter(project, root).applyFilter(line, line.length)!!.resultItems.single()
        assertEquals("src/b.inko:1:4", line.substring(item.highlightStartOffset, item.highlightEndOffset))
    }
}
