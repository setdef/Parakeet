package com.setdef.parakeet

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

data class InkoCliResult(val exitCode: Int, val stdout: String, val stderr: String, val interrupted: Boolean) {
    val succeeded: Boolean get() = exitCode == 0 && !interrupted
}

/**
 * The `inko` command, as the plugin uses it. Every feature talks to Inko
 * through this, so tests can substitute a fake for the real compiler.
 */
interface InkoCli {
    /** A command line for `inko args…`, or null when no `inko` executable is available. */
    fun commandLine(args: List<String>, workingDirectory: Path?): GeneralCommandLine?

    /**
     * Runs `inko args…` to completion, feeding it [stdin], and stops it on
     * timeout or when [indicator] (by default, the current one) is cancelled.
     * Returns null when no `inko` executable is available.
     */
    fun run(
        args: List<String>,
        workingDirectory: Path?,
        stdin: String? = null,
        timeoutMs: Int = 30_000,
        indicator: ProgressIndicator? = null,
    ): InkoCliResult?

    val isAvailable: Boolean get() = commandLine(emptyList(), null) != null

    /** The version `inko --version` reports (e.g. `0.21.1`), or null when it can't be run. */
    fun version(): String? =
        run(listOf("--version"), null, timeoutMs = 10_000)?.takeIf { it.succeeded }?.stdout?.trim()?.removePrefix("inko ")?.takeIf { it.isNotEmpty() }

    companion object {
        fun getInstance(project: Project): InkoCli = project.service()
    }
}

/** Runs a real `inko` executable as a separate process. */
open class InkoProcessCli(private val executable: () -> Path?) : InkoCli {
    override fun commandLine(args: List<String>, workingDirectory: Path?): GeneralCommandLine? {
        val inko = executable() ?: return null
        return GeneralCommandLine(listOf(inko.toString()) + args)
            .withWorkDirectory(workingDirectory?.toFile())
            .withCharset(StandardCharsets.UTF_8)
    }

    override fun run(
        args: List<String>,
        workingDirectory: Path?,
        stdin: String?,
        timeoutMs: Int,
        indicator: ProgressIndicator?,
    ): InkoCliResult? {
        val handler = CapturingProcessHandler(commandLine(args, workingDirectory) ?: return null)
        if (stdin != null) {
            // Written on another thread so a large input can't deadlock against unread output.
            AppExecutorUtil.getAppExecutorService().execute {
                try {
                    handler.processInput.use { it.write(stdin.toByteArray(StandardCharsets.UTF_8)) }
                } catch (e: IOException) {
                    LOG.debug("inko stopped reading its input", e)
                }
            }
        }
        val progress = indicator ?: ProgressManager.getInstance().progressIndicator ?: EmptyProgressIndicator()
        val output = handler.runProcessWithProgressIndicator(progress, timeoutMs, true)
        return InkoCliResult(output.exitCode, output.stdout, output.stderr, output.isTimeout || output.isCancelled)
    }

    /** Cached per executable (and its modification time), since banners ask often. */
    override fun version(): String? {
        val inko = executable() ?: return null
        val key = inko to Files.getLastModifiedTime(inko).toMillis()
        VERSIONS[key]?.let { return it }
        return super.version()?.also { VERSIONS[key] = it }
    }

    private companion object {
        val LOG = logger<InkoProcessCli>()
        val VERSIONS = ConcurrentHashMap<Pair<Path, Long>, String>()
    }
}

/** The project's `inko`, as chosen in Settings | Languages & Frameworks | Inko. */
class InkoProjectCli(project: Project) : InkoProcessCli({ InkoProjectSettings.getInstance(project).toolchain.executable() })
