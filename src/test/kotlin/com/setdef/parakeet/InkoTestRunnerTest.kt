package com.setdef.parakeet

import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.RunConfigurationProducer
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import java.nio.file.Files
import java.nio.file.Path

class InkoTestRunnerTest : InkoTestCase(onDisk = true) {
    private val root: Path get() = Path.of(myFixture.tempDirPath)

    private val testSource = "import std.test (Tests)\n\nfn pub tests(t: mut Tests) {\n  t.test('adds numbers', fn (t) { t.equal(1 + 1, 2) })\n}\n"

    private fun files(): Set<String> = Files.walk(root).use { paths ->
        paths.filter(Files::isRegularFile).map { root.relativize(it).toString() }.toList().toSet()
    }

    fun testFindsTestModulesLikeInkoTest() {
        myFixture.addFileToProject("src/app.inko", "")
        myFixture.addFileToProject("test/test_app.inko", "")
        myFixture.addFileToProject("test/nested/test_more.inko", "")
        myFixture.addFileToProject("test/helpers.inko", "")
        assertEquals(listOf("nested.test_more", "test_app"), InkoTestModules.find(root))
    }

    fun testProgramImportsEachModuleAndMapsPaths() {
        val source = InkoTestProgram.source(listOf("a.test_b", "test_c"), Path.of("/sys/mirror"), Path.of("/work/it's"), reportsSkipped = false)
        assertTrue(source.contains("import a.test_b (self as tests0)\nimport test_c (self as tests1)"))
        assertTrue(source.contains("    tests0.tests(tests)\n    tests1.tests(tests)\n"))
        assertTrue(source.contains("path.strip_prefix('/sys/mirror/')"))
        assertTrue(source.contains("'/work/it\\'s/\${rest}'"))
        assertFalse(source.contains("fn pub mut skipped"))
        assertFalse(source.contains("{{"))
        assertTrue(InkoTestProgram.source(emptyList(), Path.of("/m"), Path.of("/r"), reportsSkipped = true).contains("fn pub mut skipped(test: Test)"))
    }

    fun testDetectsWhetherTheStandardLibraryReportsSkippedTests() {
        val std = Files.createDirectories(root.resolve("fake-std/std"))
        Files.writeString(std.resolve("test.inko"), "trait pub Reporter {\n  fn pub mut passed(test: Test)\n}\n\nfn pub mut skipped(x: Int) {}\n")
        assertFalse(InkoTestProgram.reportsSkipped(std.parent))
        Files.writeString(std.resolve("test.inko"), "trait pub Reporter {\n  fn pub mut passed(test: Test)\n  fn pub mut skipped(test: Test)\n}\n")
        assertTrue(InkoTestProgram.reportsSkipped(std.parent))
        assertFalse(InkoTestProgram.reportsSkipped(null))
    }

    fun testLocatesTestsAndFiles() {
        myFixture.addFileToProject("src/app.inko", "")
        val file = myFixture.addFileToProject("test/test_app.inko", testSource)
        val path = file.virtualFile.path
        val test = InkoTestLocator.getLocation("inko", "$path:4", project, GlobalSearchScope.allScope(project)).single().psiElement
        assertEquals("test", test.text)
        assertEquals(3, file.viewProvider.document!!.getLineNumber(test.textOffset))
        assertTrue(InkoTestLocator.getLocation("inko", path, project, GlobalSearchScope.allScope(project)).single().psiElement is PsiFile)
        assertEmpty(InkoTestLocator.getLocation("file", path, project, GlobalSearchScope.allScope(project)))
    }

    private fun testState(target: String? = null): InkoTestRunState {
        myFixture.addFileToProject("src/app.inko", "")
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("test/test_app.inko", testSource).virtualFile)
        val leaf: PsiElement = myFixture.file.findElementAt(testSource.indexOf("tests("))!!
        @Suppress("UNCHECKED_CAST")
        val context = ConfigurationContext.createEmptyContextForLocation(PsiLocation(leaf) as Location<PsiElement>)
        val configuration = RunConfigurationProducer.getInstance(InkoRunConfigurationProducer::class.java)
            .createConfigurationFromContext(context)!!.configuration as InkoRunConfiguration
        if (target != null) configuration.options.target = target
        val environment = ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), configuration).build()
        return configuration.getState(environment.executor, environment) as InkoTestRunState
    }

    fun testBuildsInAMirrorAndRunsFromTheProject() {
        useFakeInko(FakeInkoCli())
        val state = testState()
        val before = files()
        val command = state.commandLine()
        val mirror = InkoTestRuns.directoryFor(root)
        val args = command.parametersList.list

        assertEquals("/bin/sh", command.exePath)
        assertEquals(listOf("fake-inko", mirror.toRealPath().toString(), root.toString()), args.subList(2, 5))
        // The file filter names the test file as the compiled tests know it: in the mirror.
        assertEquals(mirror.toRealPath().resolve("test/test_app.inko").toString(), args[5])
        assertEquals(root.toFile(), command.workDirectory)
        assertTrue(Files.readString(mirror.resolve(".parakeet/tests.inko")).contains("import test_app (self as tests0)"))
        // Nothing was written to the project.
        assertEquals(before, files())
    }

    fun testTestNameFiltersPassThrough() {
        useFakeInko(FakeInkoCli())
        assertEquals("adds numbers", testState("adds numbers").commandLine().parametersList.list[5])
    }

    // The rerun script, run for real against a fake `inko` whose "tests" program reports how it was run.
    fun testRerunsBuildOnceAndRunEachFailedTestByName() {
        val dir = Files.createTempDirectory("rerun")
        val mirror = Files.createDirectories(dir.resolve("mirror"))
        val root = Files.createDirectories(dir.resolve("project"))
        val inko = dir.resolve("inko")
        Files.writeString(inko, "#!/bin/sh\necho built >> \"\$PWD/../builds\"\nmkdir -p build/debug\nprintf '#!/bin/sh\\necho \"run [\$PARAKEET_RUN] [\$1] [\$2]\"\\nexit 1\\n' > build/debug/tests\nchmod +x build/debug/tests\n")
        inko.toFile().setExecutable(true)
        val tests = listOf(
            InkoFailedTest("it's odd", "$root/test/test_a.inko"),
            InkoFailedTest("adds", "$root/test/test_b.inko"),
            InkoFailedTest("adds", "$root/test/test_b.inko"),
        )
        val script = Files.writeString(dir.resolve("rerun.sh"), InkoTestReruns.script(tests, root))
        val process = ProcessBuilder("/bin/sh", script.toString(), inko.toString(), mirror.toString(), root.toString(), "--extra")
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals("a failed test fails the rerun", 1, process.waitFor())
        assertEquals("built\n", Files.readString(dir.resolve("builds")))
        assertEquals(
            listOf(
                "##teamcity[testSuiteStarted nodeId='s:$root/test/test_a.inko' parentNodeId='0' name='test_a.inko' locationHint='inko://$root/test/test_a.inko']",
                "##teamcity[testSuiteStarted nodeId='s:$root/test/test_b.inko' parentNodeId='0' name='test_b.inko' locationHint='inko://$root/test/test_b.inko']",
                "run [r0-] [it's odd] [--extra]",
                "run [r1-] [adds] [--extra]",
                "##teamcity[testSuiteFinished nodeId='s:$root/test/test_a.inko']",
                "##teamcity[testSuiteFinished nodeId='s:$root/test/test_b.inko']",
            ),
            output.lines().filter { it.isNotBlank() },
        )
    }

    fun testTheProgramSkipsSuitesAlreadyAnnounced() {
        val source = InkoTestProgram.source(listOf("test_a"), Path.of("/m"), Path.of("/r"), reportsSkipped = false)
        assertTrue(source.contains("env.get('PARAKEET_ANNOUNCED_SUITES')"))
        assertTrue(source.contains("env.get('PARAKEET_RUN')"))
    }
}
