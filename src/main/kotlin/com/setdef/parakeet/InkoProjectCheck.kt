package com.setdef.parakeet

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.problems.WolfTheProblemSolver
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Compiler errors across a whole project, not just the open files: after
 * saves (debounced) and at startup, `inko check` runs on every module of the
 * saved project, in a mirror. Files with errors are marked as problem files
 * (red in the Project view, listed in the Problems tool window), and Code |
 * Inspect Code reports every diagnostic through [InkoCompilerInspection].
 */
@Service(Service.Level.PROJECT)
class InkoProjectCheck(private val project: Project, private val scope: CoroutineScope) {
    /** A project check's diagnostics, by real file, and the VFS state it saw. */
    class Result(val diagnostics: Map<Path, List<InkoDiagnostic>>, val modificationCount: Long)

    private val results = ConcurrentHashMap<Path, Result>()
    private val pending = ConcurrentHashMap<Path, Job>()
    private val marked = ConcurrentHashMap<Path, Set<VirtualFile>>()

    /** Checks [root] in the background after [DELAY_MS] without another change (or right away when [immediately]). */
    fun schedule(root: Path, immediately: Boolean = false) {
        // Tests check directly, rather than leaving background work to outlive them.
        if (ApplicationManager.getApplication().isUnitTestMode) return
        launch(root, immediately)
    }

    /** [schedule]'s background check, which tests call themselves and wait for. */
    internal fun launch(root: Path, immediately: Boolean): Job {
        pending.remove(root)?.cancel()
        return scope.launch(Dispatchers.IO) {
            if (!immediately) delay(DELAY_MS)
            val result = check(root) ?: return@launch
            ensureActive()
            report(root, result)
        }.also { pending[root] = it }
    }

    /** The latest check's diagnostics for [file], checking [root] now if nothing has changed on disk since isn't known. */
    fun diagnostics(root: Path, file: Path): List<InkoDiagnostic> {
        val current = VirtualFileManager.getInstance().modificationCount
        val result = results[root]?.takeIf { it.modificationCount == current } ?: check(root) ?: return emptyList()
        return result.diagnostics[file].orEmpty()
    }

    /** Runs `inko check` on every module of [root] (sources and tests) and keeps the result. */
    fun check(root: Path): Result? {
        val cli = InkoCli.getInstance(project)
        if (!cli.isAvailable) return null
        val modificationCount = VirtualFileManager.getInstance().modificationCount
        return try {
            InkoMirror.create(root).use { mirror ->
                val output = cli.run(listOf("check", "--format", "json"), mirror.directory, timeoutMs = TIMEOUT_MS) ?: return null
                if (output.interrupted) return null
                val diagnostics = InkoCheck.parse(output.stderr)
                    .map { mirror.toReal(Path.of(it.file)) to it }
                    // The project's own modules; not its dependencies or the standard library.
                    .filter { (path, _) -> path.startsWith(root) && !path.startsWith(root.resolve("dep")) }
                    .groupBy({ it.first }, { it.second })
                Result(diagnostics, modificationCount).also { results[root] = it }
            }
        } catch (e: Exception) {
            LOG.warn("Couldn't check the Inko project at $root", e)
            null
        }
    }

    /** The files the last [report] for [root] marked as having errors. */
    fun problemFiles(root: Path): Set<VirtualFile> = marked[root].orEmpty()

    /**
     * Marks files with errors as problem files, and unmarks ones that no longer have any. Off the EDT: the
     * IDE's problem-file service asserts it (from 2025.3), and takes its own read lock.
     */
    fun report(root: Path, result: Result) {
        val wolf = WolfTheProblemSolver.getInstance(project)
        val files = LocalFileSystem.getInstance()
        val withErrors = result.diagnostics.filterValues { diagnostics -> diagnostics.any { !it.isWarning } }.keys
            .mapNotNull { files.refreshAndFindFileByNioFile(it) }.toSet()
        // One report at a time, as on the EDT before, so marks aren't left from an overlapping one.
        synchronized(marked) {
            for (file in marked[root].orEmpty() - withErrors) wolf.clearProblemsFromExternalSource(file, this)
            for (file in withErrors) wolf.reportProblemsFromExternalSource(file, this)
            marked[root] = withErrors
        }
    }

    companion object {
        private val LOG = logger<InkoProjectCheck>()
        private const val DELAY_MS = 1_000L
        private const val TIMEOUT_MS = 120_000

        fun getInstance(project: Project): InkoProjectCheck = project.service()
    }
}

/**
 * The compiler's errors and warnings for Code | Inspect Code, from a check of
 * the whole project. While editing, the as-you-type check already shows them,
 * so this only reports in batch runs.
 */
class InkoCompilerInspection : LocalInspectionTool() {
    override fun runForWholeFile(): Boolean = true

    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        if (isOnTheFly || file !is InkoFile) return null
        val path = Inko.pathOf(file.virtualFile) ?: return null
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return null
        val diagnostics = InkoProjectCheck.getInstance(file.project).diagnostics(Inko.projectRoot(path), path)
        return diagnostics.mapNotNull { diagnostic ->
            val start = Inko.offsetOf(document, diagnostic.lineStart, diagnostic.columnStart)
            val end = maxOf(Inko.offsetOf(document, diagnostic.lineEnd, diagnostic.columnEnd + 1), minOf(start + 1, document.textLength))
            val first = file.findElementAt(start) ?: return@mapNotNull null
            val last = file.findElementAt(maxOf(end - 1, start)) ?: first
            manager.createProblemDescriptor(
                first, last, diagnostic.message,
                if (diagnostic.isWarning) ProblemHighlightType.WARNING else ProblemHighlightType.GENERIC_ERROR,
                false,
            )
        }.toTypedArray()
    }
}

/** Starts a project-wide check of each Inko project when the IDE opens it. */
class InkoProjectCheckOnStartup : com.intellij.openapi.startup.ProjectActivity {
    override suspend fun execute(project: Project) {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        val roots = readAction { InkoProjectRoots.find(project) }
        roots.forEach { InkoProjectCheck.getInstance(project).schedule(it, immediately = true) }
    }
}
