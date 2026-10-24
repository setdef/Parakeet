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
