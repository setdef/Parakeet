package com.setdef.parakeet

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.TempDirTestFixture
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import com.intellij.testFramework.replaceService
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/** Stands in for the `inko` command, answering each call with [respond]. */
class FakeInkoCli(private val available: Boolean = true, private val respond: (Call) -> InkoCliResult = { ok() }) : InkoCli {
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
    }

    override fun createTempDirTestFixture(): TempDirTestFixture = if (onDisk) TempDirTestFixtureImpl() else super.createTempDirTestFixture()

    protected fun useFakeInko(fake: FakeInkoCli): FakeInkoCli {
        project.replaceService(InkoCli::class.java, fake, testRootDisposable)
        return fake
    }
}
