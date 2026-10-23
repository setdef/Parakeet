package com.setdef.parakeet

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.AbstractTestProxy
import com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.execution.ParametersListUtil
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** Test modules as `inko test` finds them: `test/**/test_*.inko`, named relative to `test/`. */
object InkoTestModules {
    fun find(root: Path): List<String> {
        val tests = root.resolve("test").takeIf(Files::isDirectory) ?: return emptyList()
        return Files.walk(tests).use { paths ->
            paths.filter { it.isRegularFile() && it.extension == Inko.EXTENSION && it.name.startsWith("test_") }
                .map { tests.relativize(it).joinToString(".").removeSuffix(".${Inko.EXTENSION}") }
                .sorted()
                .toList()
        }
    }
}

/**
 * The Inko program Parakeet runs tests with: like the one `inko test`
 * generates, but reporting each test to the IDE (see TestRunner.inko).
 */
object InkoTestProgram {
    private val TEMPLATE: String by lazy {
        InkoTestProgram::class.java.getResource("/parakeet/TestRunner.inko")!!.readText()
    }

    // Newer standard libraries add `skipped` to the Reporter trait; older ones reject it.
    private const val SKIPPED = """
  fn pub mut skipped(test: Test) {
    let id = started(test)

    message("testIgnored nodeId='${'$'}{id}'")
    message("testFinished nodeId='${'$'}{id}'")
  }
"""

    fun source(modules: List<String>, mirror: Path, root: Path, reportsSkipped: Boolean): String = TEMPLATE
        .replace("{{IMPORTS}}", modules.withIndex().joinToString("\n") { (i, module) -> "import $module (self as tests$i)" })
        .replace("{{CALLS}}", modules.indices.joinToString("\n") { i -> "    tests$i.tests(tests)" } + "\n")
        .replace("{{SKIPPED}}", if (reportsSkipped) SKIPPED else "")
        .replace("{{MIRROR}}", inkoString(mirror.toString()))
        .replace("{{ROOT}}", inkoString(root.toString()))

    /** Escapes text for inside an Inko single-quoted string. */
    private fun inkoString(text: String) = text.replace("\\", "\\\\").replace("'", "\\'").replace("$", "\\$")

    /** Whether the standard library at [std] (the directory holding `std/`) has `skipped` in its Reporter trait. */
    fun reportsSkipped(std: Path?): Boolean {
        val test = std?.resolve("std/test.inko")?.takeIf(Files::isRegularFile) ?: return false
        val reporter = Regex("""trait pub Reporter \{.*?\n}""", RegexOption.DOT_MATCHES_ALL).find(Files.readString(test))?.value ?: return false
        return reporter.contains("fn pub mut skipped(")
    }
}

/** Where a project's test runs build, in the IDE's system directory (never the project). */
object InkoTestRuns {
    /**
     * The compiled test program, relative to the mirror: `build/debug/tests`,
     * `build/release/tests` with `--release`, and under the target's name for
     * a target other than the host's (as the compiler's BuildDirectories does).
     */
    fun program(configuration: InkoRunConfiguration, cli: InkoCli): String {
        val options = configuration.options
        val optimization = if (options.release) "release" else "debug"
        val target = options.compileTarget?.takeIf { it.isNotBlank() }
        val native = target == null || target == InkoTargets.host(cli)
        return if (native) "build/$optimization/tests" else "build/$target/$optimization/tests"
    }

    fun directoryFor(root: Path): Path =
        PathManager.getSystemDir().resolve("parakeet").resolve("tests").resolve("${root.name}-${Integer.toHexString(root.toString().hashCode())}")

    /** The standard library directory: from `inko print std`, or (for older versions) beside the executable. */
    fun standardLibrary(cli: InkoCli): Path? {
        cli.run(listOf("print", "std"), null, timeoutMs = 10_000)?.takeIf { it.succeeded }?.stdout?.trim()
            ?.takeIf { it.isNotEmpty() }?.let { return Path.of(it) }
        val executable = cli.commandLine(emptyList(), null)?.exePath?.let(Path::of)?.takeIf(Files::exists) ?: return null
        return executable.toRealPath().parent?.parent?.resolve("lib/inko/std")?.takeIf(Files::isDirectory)
    }
}

/** A test to rerun: its name and the (project) file it's in. */
data class InkoFailedTest(val name: String, val path: String)

/**
 * Runs a project's tests and shows them in the IDE's test runner: builds the
 * test program in a persistent mirror (so nothing is written to the project),
 * then runs it from the project root, where tests expect to run. With
 * [rerun], runs just those tests: the program takes one name filter, so it's
 * built once and run once per name, with their files' suites announced around
 * the runs so the tree shows each file once.
 */
class InkoTestRunState(
    environment: ExecutionEnvironment,
    private val configuration: InkoRunConfiguration,
    private val root: Path,
    private val rerun: List<InkoFailedTest>? = null,
) : CommandLineState(environment) {

    fun commandLine(): GeneralCommandLine {
        val project = environment.project
        val cli = InkoCli.getInstance(project)
        val inko = cli.commandLine(emptyList(), null)?.exePath
            ?: throw ExecutionException("No inko executable found. Choose one in Settings | Languages & Frameworks | Inko.")
        val modules = InkoTestModules.find(root)
        if (modules.isEmpty()) throw ExecutionException("No test files (test/**/test_*.inko) in $root.")

        val mirror = InkoMirror.persistent(root, InkoTestRuns.directoryFor(root))
        val program = mirror.directory.resolve(".parakeet").resolve("tests.inko")
        Files.createDirectories(program.parent)
        Files.writeString(program, InkoTestProgram.source(modules, mirror.directory, root, InkoTestProgram.reportsSkipped(InkoTestRuns.standardLibrary(cli))))

        val options = configuration.options
        // A test file filter must name the file as the compiled tests know it: in the mirror.
        val filter = options.target?.takeIf { it.isNotBlank() }?.let { target ->
            Path.of(target).takeIf { it.isAbsolute && it.startsWith(root) && Files.isRegularFile(it) }?.let { mirror.toMirror(it).toString() } ?: target
        }
        val arguments = ParametersListUtil.parse(options.arguments.orEmpty())
        val command = if (rerun != null) {
            val script = mirror.directory.resolve(".parakeet").resolve("rerun.sh")
            Files.writeString(script, InkoTestReruns.script(rerun, root, configuration.buildFlags(), InkoTestRuns.program(configuration, cli)))
            listOf("/bin/sh", script.toString(), inko, mirror.directory.toString(), root.toString()) + arguments
        } else {
            val build = (listOf("build") + configuration.buildFlags() + listOf("--include", "test", ".parakeet/tests.inko")).joinToString(" ") { InkoTestReruns.sh(it) }
        val program = InkoTestRuns.program(configuration, cli)
        val script = """mirror="${'$'}1"; root="${'$'}2"; shift 2; cd "${'$'}mirror" && "${'$'}0" $build && cd "${'$'}root" && exec "${'$'}mirror/$program" "${'$'}@""""
            listOf("/bin/sh", "-c", script, inko, mirror.directory.toString(), root.toString()) + listOfNotNull(filter) + arguments
        }
        return GeneralCommandLine(command)
            .withWorkDirectory(root.toFile())
            .withEnvironment(options.environment)
            .withParentEnvironmentType(
                if (options.passParentEnvironment) GeneralCommandLine.ParentEnvironmentType.CONSOLE
                else GeneralCommandLine.ParentEnvironmentType.NONE,
            )
    }

    override fun startProcess(): ProcessHandler {
        val handler = KillableColoredProcessHandler(commandLine())
        ProcessTerminatedListener.attach(handler)
        return handler
    }

    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val handler = startProcess()
        val console = SMTestRunnerConnectionUtil.createAndAttachConsole("Inko", handler, InkoTestConsoleProperties(configuration, executor)) as ConsoleView
        console.addMessageFilter(InkoFileLinkFilter(environment.project, root))
        return DefaultExecutionResult(console, handler)
    }
}

class InkoTestConsoleProperties(configuration: InkoRunConfiguration, executor: Executor) :
    SMTRunnerConsoleProperties(configuration, "Inko", executor) {
    init {
        isIdBasedTestTree = true
    }

    override fun getTestLocator(): SMTestLocator = InkoTestLocator

    override fun createRerunFailedTestsAction(consoleView: ConsoleView): AbstractRerunFailedTestsAction =
        InkoRerunFailedTestsAction(consoleView, this)
}

/** The script that reruns failed tests: build once, then one run per test name. */
object InkoTestReruns {
    fun script(tests: List<InkoFailedTest>, root: Path, buildFlags: List<String> = emptyList(), program: String = "build/debug/tests"): String {
        val names = tests.map { it.name }.distinct()
        val suites = tests.map { it.path }.distinct()
        fun message(text: String) = "printf '%s\\n' ${sh("##teamcity[$text]")}"
        return buildString {
            appendLine("""inko="${'$'}1"; mirror="${'$'}2"; root="${'$'}3"; shift 3""")
            val build = (listOf("build") + buildFlags + listOf("--include", "test", ".parakeet/tests.inko")).joinToString(" ") { sh(it) }
            appendLine("""cd "${'$'}mirror" && "${'$'}inko" $build || exit ${'$'}?""")
            appendLine("""cd "${'$'}root" || exit ${'$'}?""")
            for (path in suites) {
                val name = path.removePrefix("$root/test/")
                appendLine(message("testSuiteStarted nodeId='${teamcity("s:$path")}' parentNodeId='0' name='${teamcity(name)}' locationHint='inko://${teamcity(path)}'"))
            }
            appendLine("status=0")
            for ((index, name) in names.withIndex()) {
                append("PARAKEET_ANNOUNCED_SUITES=${sh(suites.joinToString("\n"))} PARAKEET_RUN=${sh("r$index-")} ")
                appendLine(""""${'$'}mirror/$program" ${sh(name)} "${'$'}@" || status=1""")
            }
            for (path in suites) appendLine(message("testSuiteFinished nodeId='${teamcity("s:$path")}'"))
            appendLine("""exit ${'$'}status""")
        }
    }

    /** Quotes [text] for a POSIX shell. */
    fun sh(text: String) = "'" + text.replace("'", "'\\''") + "'"

    /** Escapes a TeamCity service message value. */
    private fun teamcity(text: String) = text.replace("|", "||").replace("'", "|'").replace("\n", "|n").replace("\r", "|r")
        .replace("[", "|[").replace("]", "|]")
}

/** Rerun Failed Tests: runs the failed tests again, by name. */
class InkoRerunFailedTestsAction(consoleView: ConsoleView, properties: InkoTestConsoleProperties) : AbstractRerunFailedTestsAction(consoleView) {
    init {
        init(properties)
    }

    override fun getRunProfile(environment: ExecutionEnvironment): MyRunProfile? {
        val configuration = myConsoleProperties.configuration as? InkoRunConfiguration ?: return null
        val root = configuration.projectRoot() ?: return null
        val failed = failedTests(getFailedTests(configuration.project))
        if (failed.isEmpty()) return null
        return object : MyRunProfile(configuration) {
            override fun getModules(): Array<Module> = Module.EMPTY_ARRAY

            override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
                InkoTestRunState(environment, configuration, root, failed)
        }
    }

    companion object {
        /** Each failed test's name, and its file (from its `inko://path:line` location). */
        fun failedTests(proxies: List<AbstractTestProxy>): List<InkoFailedTest> = proxies.filter { it.isLeaf }.mapNotNull { test ->
            val path = test.locationUrl?.removePrefix("inko://")?.substringBeforeLast(':') ?: return@mapNotNull null
            InkoFailedTest(test.name, path)
        }
    }
}

/** Finds the source of `inko://path` (a test file) and `inko://path:line` (a test) locations. */
object InkoTestLocator : SMTestLocator {
    private val WITH_LINE = Regex("""^(.*):(\d+)$""")

    override fun getLocation(protocol: String, path: String, project: Project, scope: GlobalSearchScope): List<Location<*>> {
        if (protocol != "inko") return emptyList()
        val match = WITH_LINE.find(path)
        val file = LocalFileSystem.getInstance().findFileByPath(match?.groupValues?.get(1) ?: path) ?: return emptyList()
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return emptyList()
        val line = match?.groupValues?.get(2)?.toInt() ?: return listOf(PsiLocation(psiFile))
        val document = psiFile.viewProvider.document ?: return listOf(PsiLocation(psiFile))
        val lineIndex = (line - 1).coerceIn(0, maxOf(document.lineCount - 1, 0))
        val start = document.getLineStartOffset(lineIndex)
        val end = document.getLineEndOffset(lineIndex)
        // The `test` in `t.test('…')`, so running a test from the tree runs just that test.
        val leaf = (start until end).asSequence().mapNotNull(psiFile::findElementAt)
            .firstOrNull { InkoRunTargets.forMarker(it) != null }
            ?: psiFile.findElementAt(start)
            ?: psiFile
        return listOf(PsiLocation(leaf))
    }
}

/** The targets `inko` can compile for (`inko targets`), and the host's (`inko print target`). */
object InkoTargets {
    fun list(cli: InkoCli): List<String> = cli.run(listOf("targets"), null, timeoutMs = 10_000)?.takeIf { it.succeeded }?.stdout
        ?.lines()?.map { it.removeSuffix("(native)").trim() }?.filter { it.isNotEmpty() }.orEmpty()

    fun host(cli: InkoCli): String? = cli.run(listOf("print", "target"), null, timeoutMs = 10_000)?.takeIf { it.succeeded }?.stdout?.trim()
}
