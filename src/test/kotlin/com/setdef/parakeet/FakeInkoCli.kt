package com.setdef.parakeet

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.TempDirTestFixture
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import com.intellij.testFramework.replaceService
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/** Stands in for the `inko` (or `ivm`) command, answering each call with [respond]. */
class FakeInkoCli(private val available: Boolean = true, private val respond: (Call) -> InkoCliResult = { ok() }) : IvmCli {
    data class Call(val args: List<String>, val workingDirectory: Path?, val stdin: String?)

    val calls: MutableList<Call> = CopyOnWriteArrayList()

    override fun commandLine(args: List<String>, workingDirectory: Path?): GeneralCommandLine? =
        if (available) GeneralCommandLine(listOf("fake-inko") + args).withWorkDirectory(workingDirectory?.toFile()) else null

    override fun run(args: List<String>, workingDirectory: Path?, stdin: String?, timeoutMs: Int, indicator: ProgressIndicator?): InkoCliResult? {
        if (!available) return null
        val call = Call(args, workingDirectory, stdin)
        calls += call
        return respond(call)
    }

    companion object {
        fun ok(stdout: String = "", stderr: String = "") = InkoCliResult(0, stdout, stderr, false)

        fun failed(stderr: String, exitCode: Int = 1) = InkoCliResult(exitCode, "", stderr, false)
    }
}

/**
 * A platform test that never runs the real `inko` (unless [realInko]): by
 * default Inko is "unavailable", and tests swap in a [FakeInkoCli] as needed.
 * Files can live on the real disk ([onDisk]), as mirroring for check and doc needs.
 */
abstract class InkoTestCase(private val onDisk: Boolean = false, private val realInko: Boolean = false) : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        if (!realInko) useFakeInko(FakeInkoCli(available = false))
        // No Cuttlebone unless a test sets one up (a fake, or stubCuttlebone), so what needs it is unavailable.
        useCuttlebone(NO_CUTTLEBONE)
    }

    override fun tearDown() {
        try {
            useCuttlebone(NO_CUTTLEBONE)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** Uses the `cuttlebone` at [executable] (as the setting would), stopping any server already running. */
    protected fun useCuttlebone(executable: String) {
        InkoProjectSettings.getInstance(project).cuttlebone = executable
        CuttleboneService.getInstance(project).reset()
    }

    /**
     * Cuttlebone, in-process: each question (method, file, offset) gets [answer]'s answer, as a server's
     * would arrive with its status. For tests of features that need Cuttlebone, without starting one.
     */
    protected fun stubCuttlebone(answer: (method: String, file: com.intellij.openapi.vfs.VirtualFile, offset: Int) -> CuttleboneQueries.Answer<*>?) =
        CuttleboneQueries.getInstance(project).stub(testRootDisposable, answer)

    /**
     * Cuttlebone running, but unable to check the code because it's being edited (here, a declaration
     * has an error). That's the only state in which Parakeet reads the code itself, so the tests of that
     * reading run in it.
     */
    protected fun cuttleboneCantCheckTheCode(reason: String = "declarationErrors") = stubCuttlebone { method, file, _ ->
        CuttleboneQueries.Answer<Any>(null, CuttleboneStatus(method, file.url, "unavailable", reason, 1.0))
    }

    /**
     * A place in this test's code: the [nth] [marker]'s position (plus [shift]) in the project file [path],
     * whose text is [text]. What's there runs to the marker's end.
     */
    class At(val path: String, val text: String, val marker: String, val shift: Int = 0, val nth: Int = 0) {
        val offset: Int
            get() {
                var at = -1
                repeat(nth + 1) { at = text.indexOf(marker, at + 1).also { check(it >= 0) { "no $marker in $path" } } }
                return at + shift
            }
        val line: Int get() = text.substring(0, offset).count { it == '\n' }
        val character: Int get() = offset - (text.lastIndexOf('\n', offset - 1) + 1)
    }

    /**
     * Cuttlebone, in-process, as a compiler that knows this test's code: [resolves] says where each use's
     * definition is, [hover] what type is at a place, and [editing] where it can't check the code (it's being
     * edited). Every other question is answered: nothing there, or with [otherwiseEditing], can't check it.
     * `references` lists a declaration and every use resolving to it, as one check of the whole project does,
     * so it has no answer while any of the code can't be checked.
     */
    inner class Compiler {
        private val definitions = mutableListOf<Pair<At, At>>()
        private val hovers = mutableListOf<Triple<At, String, Int>>()
        private val editing = mutableListOf<At>()
        private var otherwiseEditing = false

        fun resolves(use: At, declaration: At) = apply { definitions += use to declaration }

        /** Every use of [marker] in [path] (whose text is [text]) resolves to [declaration]. */
        fun resolvesEvery(path: String, text: String, marker: String, declaration: At) = apply {
            for (nth in 0 until Regex(Regex.escape(marker)).findAll(text).count()) resolves(At(path, text, marker, nth = nth), declaration)
        }

        fun otherwiseEditing() = apply { otherwiseEditing = true }
        fun hover(at: At, type: String, length: Int = at.marker.length - at.shift) = apply { hovers += Triple(at, type, length) }
        fun editing(at: At) = apply { editing += at }

        private fun matches(at: At, file: com.intellij.openapi.vfs.VirtualFile, offset: Int) = file.path.endsWith("/${at.path}") && at.offset == offset

        private fun range(at: At, length: Int) = org.eclipse.lsp4j.Range(
            org.eclipse.lsp4j.Position(at.line, at.character), org.eclipse.lsp4j.Position(at.line, at.character + length))

        fun install() = stubCuttlebone { method, file, offset ->
            fun status(status: String, reason: String? = null) = CuttleboneStatus(method, file.url, status, reason, 1.0)
            val known = definitions.any { matches(it.first, file, offset) } || hovers.any { matches(it.first, file, offset) }
            if (editing.any { matches(it, file, offset) } || (otherwiseEditing && !known)) return@stubCuttlebone CuttleboneQueries.Answer<Any>(null, status("unavailable", "declarationErrors"))
            val value: Any? = when (method) {
                "textDocument/definition" -> definitions.firstOrNull { matches(it.first, file, offset) }?.second?.let { declaration ->
                    val target = myFixture.findFileInTempDir(declaration.path) ?: error("no ${declaration.path}")
                    listOf(org.eclipse.lsp4j.Location(target.url, range(declaration, declaration.marker.length - declaration.shift)))
                }
                "textDocument/references" -> {
                    if (editing.isNotEmpty() || otherwiseEditing) return@stubCuttlebone CuttleboneQueries.Answer<Any>(null, status("unavailable", "declarationErrors"))
                    val declaration = definitions.firstOrNull { matches(it.first, file, offset) }?.second
                        ?: definitions.map { it.second }.firstOrNull { matches(it, file, offset) }
                    declaration?.let { target ->
                        (listOf(target) + definitions.filter { it.second.path == target.path && it.second.offset == target.offset }.map { it.first }).map { at ->
                            org.eclipse.lsp4j.Location(myFixture.findFileInTempDir(at.path)!!.url, range(at, at.marker.length - at.shift))
                        }
                    }
                }
                "textDocument/hover" -> hovers.firstOrNull { matches(it.first, file, offset) }?.let { (at, type, length) ->
                    org.eclipse.lsp4j.Hover(org.eclipse.lsp4j.MarkupContent("markdown", "```inko\n$type\n```"), range(at, length))
                }
                else -> null
            }
            CuttleboneQueries.Answer(value, status("fresh"))
        }
    }

    override fun createTempDirTestFixture(): TempDirTestFixture = if (onDisk) TempDirTestFixtureImpl() else super.createTempDirTestFixture()

    /** Makes the (on-disk) test directory part of the project's content, as a real project's files are. */
    protected fun putTempDirInProjectContent() {
        val root = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath(myFixture.tempDirPath)!!
        com.intellij.testFramework.PsiTestUtil.addContentRoot(module, root)
        com.intellij.openapi.util.Disposer.register(testRootDisposable) { com.intellij.testFramework.PsiTestUtil.removeContentEntry(module, root) }
    }

    protected fun useFakeInko(fake: FakeInkoCli): FakeInkoCli {
        project.replaceService(InkoCli::class.java, fake, testRootDisposable)
        return fake
    }

    protected fun useFakeIvm(fake: FakeInkoCli): FakeInkoCli {
        ApplicationManager.getApplication().replaceService(IvmCli::class.java, fake, testRootDisposable)
        return fake
    }
}

/** A setting naming no executable, so no Cuttlebone starts. */
const val NO_CUTTLEBONE = "/nonexistent/cuttlebone"
