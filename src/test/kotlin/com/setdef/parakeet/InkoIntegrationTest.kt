package com.setdef.parakeet

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.RunConfigurationProducer
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.CapturingProcessAdapter
import com.intellij.execution.process.ProcessOutput
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.search.GlobalSearchScope
import java.nio.file.Files
import java.nio.file.Path

/**
 * Each feature end to end against the real `inko` (on PATH or via ivm).
 * Skipped by `./gradlew test -PunitOnly`.
 */
class InkoIntegrationTest : InkoTestCase(onDisk = true, realInko = true) {
    override fun setUp() {
        super.setUp()
        val cli = InkoCli.getInstance(project)
        assertTrue("inko must be installed to run integration tests", cli.isAvailable)
        println("Testing against ${cli.run(listOf("--version"), null)?.stdout?.trim()} at ${cli.commandLine(emptyList(), null)?.exePath}")
    }

    private fun open(path: String, text: String) {
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject(path, text).virtualFile)
    }

    private fun problems(): List<HighlightInfo> =
        myFixture.doHighlighting().filter { it.severity >= HighlightSeverity.WEAK_WARNING && it.description != null }

    fun testChecksUnsavedTextInAModuleOthersImport() {
        myFixture.addFileToProject("src/main.inko", "import app.util (helper)\n\ntype async Main {\n  fn async main {\n    helper\n  }\n}\n")
        open("src/app/util.inko", "import std.stdio (Stdout)\n\nfn pub helper -> Int {\n  42\n}\n")
        assertEquals("the symbol 'Stdout' is unused", problems().single { it.severity == HighlightSeverity.WARNING }.description)

        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("42"))
        myFixture.type("unsaved_only + ")
        assertEquals("the symbol 'unsaved_only' is undefined", problems().single { it.severity == HighlightSeverity.ERROR }.description)
    }

    /** Saves every file, then checks the whole project with the real compiler. */
    private fun assertCompiles() {
        FileDocumentManager.getInstance().saveAllDocuments()
        val result = InkoCli.getInstance(project).run(listOf("check"), Path.of(myFixture.tempDirPath))!!
        assertTrue(result.stderr, result.succeeded)
    }

    private fun declaration(path: String, name: String): InkoDeclaration {
        val file = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir(path))!!
        return PsiTreeUtil.findChildrenOfType(file, InkoDeclaration::class.java).first { it.name == name }
    }

    fun testChangedSignaturesStillCompile() {
        putTempDirInProjectContent()
        myFixture.addFileToProject("src/app/util.inko", "fn pub area(width: Int, height: Int) -> Int {\n  width * height\n}\n\nfn pub square(side: Int) -> Int {\n  area(side, height: side)\n}\n")
        myFixture.addFileToProject("src/main.inko", "import app.util (area)\n\nfn total -> Int {\n  area(2, 3) + area(width: 4, height: area(1, 1))\n}\n")
        InkoChangeSignatureProcessor(project, declaration("src/app/util.inko", "area"), InkoSignatureChange(
            "rectangle",
            listOf(InkoParameterChange(1, "tall", "Int"), InkoParameterChange(0, "wide", "Int"), InkoParameterChange(-1, "scale", "Int", "1")),
            "Int",
        )).run()
        assertCompiles()
        assertTrue(PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("src/main.inko"))!!.text.contains("rectangle(3, 2, 1) + rectangle(tall: rectangle(1, 1, 1), wide: 4, scale: 1)"))
    }

    fun testIntroducedParametersStillCompile() {
        putTempDirInProjectContent()
        val util = "let pub LIMIT = 10\n\nfn pub clamp(value: Int) -> Int {\n  let doubled = LIMIT * 2\n  if value > doubled { LIMIT * 2 } else { value }\n}\n"
        myFixture.addFileToProject("src/app/util.inko", util)
        myFixture.addFileToProject("src/main.inko", "import app.util (clamp)\n\nfn total -> Int {\n  clamp(5) + clamp(value: 7)\n}\n")
        val file = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("src/app/util.inko"))!!
        val start = util.indexOf("LIMIT * 2")
        val plan = InkoIntroduceParameter.plan(file, com.intellij.openapi.util.TextRange(start, start + "LIMIT * 2".length)).getOrThrow()
        InkoIntroduceParameter.processor(project, plan, "limit", "Int", replaceAll = true).run()
        assertCompiles()
    }

    fun testIntroducedConstantsStillCompile() {
        val text = "import std.stdio (Stdout)\n\nfn pub seconds(hours: Int) -> Int {\n  hours * 60 * 60\n}\n\nfn pub minutes -> Int {\n  60 * 60 / 60\n}\n"
        open("src/app/time.inko", text)
        val stream = InkoTokenStream(text)
        val start = text.indexOf("60 * 60")
        val expression = com.intellij.openapi.util.TextRange(start, start + "60 * 60".length)
        val plan = InkoIntroduceConstant.plan(stream, expression, InkoIntroduceVariable.occurrences(stream, expression, fileWide = true)).getOrThrow()
        InkoIntroduceConstant.apply(project, myFixture.editor, plan)
        assertCompiles()
    }

    /** Extracts the text between `<selection>` markers in [path]'s [text], with the real compiler checking the result. */
    /** Applies the intention titled [title] at `<caret>` in a fresh module, and checks the project still compiles. */
    private fun intentionCompiles(text: String, title: String) {
        putTempDirInProjectContent()
        open("src/app/rewrite.inko", text.replace("<caret>", ""))
        myFixture.editor.caretModel.moveToOffset(text.indexOf("<caret>"))
        val intention = myFixture.availableIntentions.firstOrNull { it.text == title } ?: error("'$title' wasn't offered")
        myFixture.launchAction(intention)
        assertCompiles()
    }

    private val describe = "fn pub describe(x: Int) -> String {\n  <caret>if x == 1 or x == 2 {\n    'small'\n  } else if x == 3 {\n    let y = 'three'\n    y\n  } else {\n    'big'\n  }\n}\n"

    fun testIfToMatchAndBackStillCompile() {
        intentionCompiles(describe, "Replace 'if' with 'match'")
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("match"))
        myFixture.launchAction(myFixture.availableIntentions.first { it.text == "Replace 'match' with 'if'" })
        assertCompiles()
    }

    fun testInvertedIfStillCompiles() = intentionCompiles(describe.replace("} else if x == 3 {\n    let y = 'three'\n    y\n  ", ""), "Invert 'if' condition")

    fun testFlippedComparisonStillCompiles() =
        intentionCompiles("fn pub check(a: Int, b: Int) -> Bool {\n  a + 1 <caret>< b and b > 2\n}\n", "Flip '<' to '>'")

    fun testNamedArgumentsStillCompile() =
        intentionCompiles("fn pub area(width: Int, height: Int) -> Int {\n  width * height\n}\n\nfn pub twice -> Int {\n  area(<caret>2, 3)\n}\n", "Add argument names")

    fun testSpecifiedTypeStillCompiles() =
        intentionCompiles("fn pub area(width: Int, height: Int) -> Int {\n  let <caret>total = width * height\n  total\n}\n", "Specify type explicitly")

    fun testGeneratedConstructorAndTraitImplCompile() {
        putTempDirInProjectContent()
        val text = "type pub Point {\n  let @x: Int\n  let pub @label: String\n}\n"
        open("src/app/point.inko", text)
        myFixture.editor.caretModel.moveToOffset(text.indexOf("let @x"))
        myFixture.performEditorAction("Parakeet.GenerateConstructor")
        InkoGenerateTraitImplAction.chooser = { traits -> traits.first { it.name == "Equal" && it.module == "std.cmp" } }
        try {
            myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("let @x"))
            myFixture.performEditorAction("Parakeet.GenerateTraitImpl")
        } finally {
            InkoGenerateTraitImplAction.chooser = null
        }
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains("impl Equal for Point"))
        assertCompiles()
    }

    private fun extractAndCompile(path: String, text: String) {
        val start = text.indexOf("<selection>")
        val end = text.indexOf("</selection>") - "<selection>".length
        val clean = text.replace("<selection>", "").replace("</selection>", "")
        open(path, clean)
        val plan = InkoExtractMethod.plan(clean, com.intellij.openapi.util.TextRange(start, end)).getOrThrow()
        assertTrue("Placeholder types won't compile: ${plan.method}", plan.placeholders.isEmpty())
        InkoExtractMethod.apply(project, myFixture.editor, plan)
        assertCompiles()
    }

    fun testExtractedMethodsStillCompile() {
        extractAndCompile("src/app/area.inko", "fn pub area(width: Int, height: Int) -> Int {\n  <selection>let w = width * 2\n  w * height</selection>\n}\n")
        extractAndCompile(
            "src/app/counter.inko",
            "type pub Counter {\n  let mut @count: Int\n\n  fn pub mut bump(by: Int) {\n    <selection>@count = @count + by\n    @count = @count * 2</selection>\n  }\n}\n",
        )
        extractAndCompile(
            "src/app/totals.inko",
            "fn pub total(items: Array[Int]) -> Int {\n  let mut sum: Int = 0\n  <selection>sum = sum + items.size\n  sum = sum * 2</selection>\n  items.size + sum\n}\n",
        )
    }

    fun testInlinedMethodsStillCompile() {
        putTempDirInProjectContent()
        myFixture.addFileToProject(
            "src/app/util.inko",
            "let pub SCALE = 3\n\nfn pub double(value: Int) -> Int {\n  value * 2\n}\n\nfn pub area(width: Int, height: Int) -> Int {\n  let w = width * SCALE\n  w * height\n}\n\nfn pub repeat(value: Int) -> Int {\n  value + value\n}\n",
        )
        myFixture.addFileToProject(
            "src/main.inko",
            "import app.util (area, double, repeat)\n\nfn run -> Int {\n  let w = 10\n  let a = area(w, 2)\n  let r = repeat(w + 1)\n  double(w + 1) * 3 + a + r\n}\n",
        )
        for (name in listOf("area", "double", "repeat")) {
            InkoInlineMethodProcessor(project, declaration("src/app/util.inko", name), null, keepMethod = false).run()
        }
        assertCompiles()
    }

    fun testQuickFixesUseTheCompilersDiagnostics() {
        open("src/app/util.inko", "import std.stdio (Stdout, Stderr)\n\nfn pub helper {\n  Stdout.new\n}\n")
        myFixture.launchAction(myFixture.getAllQuickFixes().single { it.text == "Remove unused import 'Stderr'" })
        myFixture.checkResult("import std.stdio (Stdout)\n\nfn pub helper {\n  Stdout.new\n}\n")
    }

    fun testQuickFixesForMissingFieldsAndUnreachableCode() {
        open("src/app/util.inko", "type pub Point {\n  let @x: Int\n  let @y: Int\n}\n\nfn pub point -> Point {\n  Point(x: 1)\n}\n")
        myFixture.launchAction(myFixture.getAllQuickFixes().single { it.text == "Add field 'y'" })
        myFixture.checkResult("type pub Point {\n  let @x: Int\n  let @y: Int\n}\n\nfn pub point -> Point {\n  Point(x: 1, y: <caret>)\n}\n")

        open("src/app/early.inko", "fn pub early -> Int {\n  return 1\n  let a = 2\n  a + 3\n}\n")
        myFixture.launchAction(myFixture.getAllQuickFixes().single { it.text == "Remove unreachable code" })
        myFixture.checkResult("fn pub early -> Int {\n  return 1\n}\n")
    }

    fun testImplementsTheCompilersMissingMethods() {
        open("src/app/shape.inko", "trait pub Shape {\n  fn pub area -> Int\n}\n\ntype pub Box {}\n\nimpl Shape for Box {}\n")
        myFixture.launchAction(myFixture.getAllQuickFixes().first { it.text == "Implement missing methods" })
        assertTrue(myFixture.editor.document.text, "  fn pub area -> Int {\n    panic('not implemented')\n  }" in myFixture.editor.document.text)
    }

    fun testOptimizeImportsRemovesUnusedAndSorts() {
        open("src/app/util.inko", "import std.stdio (Stdout, Stderr)\nimport std.fmt (Format)\nimport std.array (Array)\n\nfn pub helper -> Array[Int] {\n  Stdout.new\n  []\n}\n")
        val runnable = InkoImportOptimizer().processFile(myFixture.file)
        WriteCommandAction.runWriteCommandAction(project, runnable)
        myFixture.checkResult("import std.array (Array)\nimport std.stdio (Stdout)\n\nfn pub helper -> Array[Int] {\n  Stdout.new\n  []\n}\n")
    }

    fun testFormatsWithInkoFmt() {
        open("src/main.inko", "fn   main   {\nlet a=1\n}\n")
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
        assertEquals("fn main {\n  let a = 1\n}\n", myFixture.editor.document.text)
    }

    fun testNavigatesWithInkoDoc() {
        myFixture.addFileToProject("src/app/util.inko", "type pub Helper {}\n")
        val text = "import app.util (Helper)\n\nfn a(h: Helper) -> String {\n  ''\n}\n"
        open("src/main.inko", text)

        fun targetAt(marker: String): String {
            myFixture.editor.caretModel.moveToOffset(text.indexOf(marker))
            val target = (myFixture.getReferenceAtCaretPositionWithAssertion() as InkoReference).multiResolve(false).single().element!!
            return "${target.containingFile.name}:${target.text}"
        }
        assertEquals("util.inko:Helper", targetAt("Helper)"))
        assertEquals("string.inko:String", targetAt("String"))

        val original = myFixture.file.findElementAt(text.indexOf("String"))!!
        val target = InkoResolver.resolve(original.parent as InkoName).single()
        val html = (InkoDocumentationTargetProvider().documentationTarget(target, original) as InkoDocumentationTarget).html()
        assertTrue(html, html.contains("String</code>") && html.contains("std.string") && html.contains("UTF-8"))
    }

    fun testRunsAProgram() {
        val text = "import std.stdio (Stdout)\n\ntype async Main {\n  fn async main {\n    Stdout.new.print('hello from inko')\n  }\n}\n"
        open("src/hello.inko", text)
        val leaf: PsiElement = myFixture.file.findElementAt(text.indexOf("main {"))!!
        @Suppress("UNCHECKED_CAST")
        val context = ConfigurationContext.createEmptyContextForLocation(PsiLocation(leaf) as Location<PsiElement>)
        val configuration = RunConfigurationProducer.getInstance(InkoRunConfigurationProducer::class.java)
            .createConfigurationFromContext(context)!!.configuration as InkoRunConfiguration
        val environment = ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), configuration).build()
        val handler = (configuration.getState(environment.executor, environment) as InkoRunState).execute(environment.executor, environment.runner).processHandler
        val output = ProcessOutput()
        handler.addProcessListener(CapturingProcessAdapter(output))
        handler.startNotify()
        assertTrue("inko run timed out", handler.waitFor(120_000))
        assertTrue(output.stdout + output.stderr, output.stdout.contains("hello from inko"))
    }

    fun testRunsTestsInTheTestRunner() {
        myFixture.addFileToProject("src/math.inko", "fn pub add(a: Int, b: Int) -> Int {\n  a + b\n}\n")
        val source = "import math (add)\nimport std.test (Tests)\n\nfn pub tests(t: mut Tests) {\n" +
            "  t.test('adds numbers', fn (t) { t.equal(add(1, 1), 2) })\n" +
            "  t.test(\"it's [odd]\", fn (t) { t.equal(add(2, 2), 5) })\n}\n"
        val file = myFixture.addFileToProject("test/test_math.inko", source)
        val before = Files.walk(Path.of(myFixture.tempDirPath)).use { it.toList().toSet() }

        val configuration = InkoRunConfigurationType.getInstance().configurationFactories[0].createTemplateConfiguration(project) as InkoRunConfiguration
        configuration.options.command = InkoCommand.TEST.cli
        configuration.options.workingDirectory = myFixture.tempDirPath
        val environment = ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), configuration).build()
        val state = configuration.getState(environment.executor, environment) as InkoTestRunState
        val result = state.execute(environment.executor, environment.runner)
        val console = result.executionConsole as com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
        try {
            result.processHandler!!.startNotify()
            assertTrue("tests timed out", result.processHandler!!.waitFor(180_000))
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()

            val tests = console.resultsViewer.testsRootNode.allTests.filter { it.isLeaf }
            assertEquals(setOf("adds numbers", "it's [odd]"), tests.map { it.name }.toSet())
            assertTrue(tests.single { it.name == "adds numbers" }.isPassed)
            val failed = tests.single { it.name == "it's [odd]" }
            assertTrue(failed.isDefect)
            assertTrue(failed.errorMessage, failed.errorMessage.orEmpty().startsWith(file.virtualFile.path + ":6"))
            // The tree links to the real test file.
            assertEquals("test_math.inko", console.resultsViewer.testsRootNode.children.single().name)
            assertEquals(file.virtualFile, failed.getLocation(project, GlobalSearchScope.allScope(project))!!.virtualFile)
        } finally {
            com.intellij.openapi.util.Disposer.dispose(console)
        }
        // The project itself gained no files.
        assertEquals(before, Files.walk(Path.of(myFixture.tempDirPath)).use { it.toList().toSet() })
    }

    fun testRerunsOnlyTheFailedTests() {
        myFixture.addFileToProject("src/math.inko", "fn pub add(a: Int, b: Int) -> Int {\n  a + b\n}\n")
        myFixture.addFileToProject("test/test_a.inko", "import math (add)\nimport std.test (Tests)\n\nfn pub tests(t: mut Tests) {\n" +
            "  t.test('a passes', fn (t) { t.equal(add(1, 1), 2) })\n  t.test('a fails', fn (t) { t.equal(add(1, 1), 3) })\n}\n")
        myFixture.addFileToProject("test/test_b.inko", "import math (add)\nimport std.test (Tests)\n\nfn pub tests(t: mut Tests) {\n" +
            "  t.test(\"b's failure\", fn (t) { t.equal(add(2, 2), 5) })\n}\n")
        val configuration = InkoRunConfigurationType.getInstance().configurationFactories[0].createTemplateConfiguration(project) as InkoRunConfiguration
        configuration.options.command = InkoCommand.TEST.cli
        configuration.options.workingDirectory = myFixture.tempDirPath

        /** Runs [state] and returns the test tree's leaves, as "file > name: passed|failed". */
        fun run(state: (com.intellij.execution.runners.ExecutionEnvironment) -> InkoTestRunState): Pair<List<String>, List<com.intellij.execution.testframework.AbstractTestProxy>> {
            val environment = ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), configuration).build()
            val result = state(environment).execute(environment.executor, environment.runner)
            val console = result.executionConsole as com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
            try {
                result.processHandler!!.startNotify()
                assertTrue("tests timed out", result.processHandler!!.waitFor(180_000))
                com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
                val root = console.resultsViewer.testsRootNode
                val leaves = root.children.flatMap { suite -> suite.children.map { "${suite.name} > ${it.name}: ${if (it.isDefect) "failed" else "passed"}" } }
                return leaves.sorted() to root.allTests.filter { it.isLeaf && it.isDefect }
            } finally {
                com.intellij.openapi.util.Disposer.dispose(console)
            }
        }

        val (first, failed) = run { configuration.getState(it.executor, it) as InkoTestRunState }
        assertEquals(listOf("test_a.inko > a fails: failed", "test_a.inko > a passes: passed", "test_b.inko > b's failure: failed"), first)

        val rerun = InkoRerunFailedTestsAction.failedTests(failed)
        val (second, _) = run { InkoTestRunState(it, configuration, Path.of(myFixture.tempDirPath), rerun) }
        // Only the failed tests, each file once.
        assertEquals(listOf("test_a.inko > a fails: failed", "test_b.inko > b's failure: failed"), second)
    }

    fun testRunsTestsInReleaseModeAndListsTargets() {
        myFixture.addFileToProject("src/math.inko", "fn pub add(a: Int, b: Int) -> Int {\n  a + b\n}\n")
        myFixture.addFileToProject("test/test_math.inko", "import math (add)\nimport std.test (Tests)\n\nfn pub tests(t: mut Tests) {\n  t.test('adds', fn (t) { t.equal(add(1, 1), 2) })\n}\n")
        val configuration = InkoRunConfigurationType.getInstance().configurationFactories[0].createTemplateConfiguration(project) as InkoRunConfiguration
        configuration.options.command = InkoCommand.TEST.cli
        configuration.options.workingDirectory = myFixture.tempDirPath
        configuration.options.release = true
        val environment = ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), configuration).build()
        val result = (configuration.getState(environment.executor, environment) as InkoTestRunState).execute(environment.executor, environment.runner)
        val console = result.executionConsole as com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
        try {
            result.processHandler!!.startNotify()
            assertTrue("tests timed out", result.processHandler!!.waitFor(300_000))
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            assertTrue(console.resultsViewer.testsRootNode.allTests.single { it.isLeaf }.isPassed)
        } finally {
            com.intellij.openapi.util.Disposer.dispose(console)
        }
        val cli = InkoCli.getInstance(project)
        assertTrue(InkoTargets.list(cli).contains(InkoTargets.host(cli)))
    }

    fun testCreatesAProjectWithInkoInit() {
        val directory = Files.createDirectories(Path.of(myFixture.tempDirPath, "From IntelliJ"))
        InkoProjectGenerator.generate(project, directory, InkoNewProjectSettings(github = true, moduleName = "from_intellij"))
        assertTrue(Files.isRegularFile(directory.resolve("inko.pkg")))
        assertTrue(Files.readString(directory.resolve("src/from_intellij.inko")).contains("type async Main"))
        assertTrue(Files.isRegularFile(directory.resolve(".github/workflows/push.yml")))
    }

    fun testEachIvmVersionRunsThatVersion() {
        for (version in Ivm.installedVersions()) {
            val result = InkoProcessCli { Ivm.executableOf(version) }.run(listOf("--version"), null)!!
            assertEquals("inko $version", result.stdout.trim())
        }
    }
}
