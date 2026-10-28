package com.setdef.parakeet

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Condition
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.lsp.api.LspServer
import com.intellij.problems.WolfTheProblemSolver
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.PreviousResultId
import org.eclipse.lsp4j.WorkspaceDiagnosticParams
import org.eclipse.lsp4j.WorkspaceDiagnosticReport
import java.util.concurrent.ConcurrentHashMap

/**
 * Compiler errors across the whole project, not just the open files: Cuttlebone's workspace pull
 * (`workspace/diagnostic`), the same as `inko check` for every module, files not open included. Cuttlebone holds
 * a pull until a check changes a file's report (a long poll), so it's asked again as soon as it answers. Files
 * with errors are marked as problem files (red in the Project view, listed in the Problems tool window).
 */
@Service(Service.Level.PROJECT)
class InkoProjectCheck(private val project: Project, private val scope: CoroutineScope) {
    /** Each file's latest report, by URI: its result id, and whether it has an error. */
    private val reports = ConcurrentHashMap<String, Pair<String?, Boolean>>()
    private val marked: MutableSet<VirtualFile> = ConcurrentHashMap.newKeySet()
    @Volatile private var pulling: Job? = null

    /** Pulls from the running Cuttlebone until the project closes ([CuttleboneService.initialized] starts it). */
    fun start() {
        if (ApplicationManager.getApplication().isUnitTestMode || pulling?.isActive == true) return
        pulling = scope.launch(Dispatchers.IO) {
            while (true) {
                val server = CuttleboneService.getInstance(project).server()
                try {
                    if (server == null) delay(RETRY_MS) else pull(server)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A check that didn't finish, or a server stopping: ask again in a moment.
                    LOG.debug("workspace diagnostics", e)
                    delay(RETRY_MS)
                }
            }
        }
    }

    /** One pull, with the result ids of the last, so it's answered once a report changes. */
    internal suspend fun pull(server: LspServer) {
        val previous = reports.mapNotNull { (uri, report) -> report.first?.let { PreviousResultId(uri, it) } }
        update(server.sendRequest { it.workspaceService.diagnostic(WorkspaceDiagnosticParams(previous)) } ?: return)
    }

    internal fun update(report: WorkspaceDiagnosticReport) {
        for (item in report.items) {
            if (!item.isLeft) continue // Unchanged.
            reports[item.left.uri] = item.left.resultId to item.left.items.any { it.severity == DiagnosticSeverity.Error }
        }
        mark()
    }

    /** The files the last report marked as having errors. */
    fun problemFiles(): Set<VirtualFile> = marked.toSet()

    /**
     * Marks files with errors as problem files, and unmarks ones that no longer have any. Off the EDT: the
     * IDE's problem-file service asserts it (from 2025.3), and takes its own read lock.
     */
    private fun mark() {
        val wolf = WolfTheProblemSolver.getInstance(project)
        val files = VirtualFileManager.getInstance()
        val withErrors = reports.filterValues { it.second }.keys.mapNotNull { files.refreshAndFindFileByUrl(it) }.toSet()
        // One report at a time, so marks aren't left from an overlapping one.
        synchronized(marked) {
            for (file in marked - withErrors) wolf.clearProblemsFromExternalSource(file, this)
            for (file in withErrors) wolf.reportProblemsFromExternalSource(file, this)
            marked.retainAll(withErrors)
            marked.addAll(withErrors)
        }
    }

    companion object {
        private val LOG = logger<InkoProjectCheck>()
        private const val RETRY_MS = 1_000L

        fun getInstance(project: Project): InkoProjectCheck = project.service()
    }
}

/** The compiler's errors and warnings for Code | Inspect Code, from Cuttlebone's pull; the editor shows them as you type. */
class InkoCompilerInspection : LocalInspectionTool() {
    override fun runForWholeFile(): Boolean = true

    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        if (isOnTheFly || file !is InkoFile) return null
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return null
        return InkoDiagnostic.of(file, document).orEmpty().mapNotNull { diagnostic ->
            val first = file.findElementAt(diagnostic.range.startOffset) ?: return@mapNotNull null
            val last = file.findElementAt(maxOf(diagnostic.range.endOffset - 1, diagnostic.range.startOffset)) ?: first
            manager.createProblemDescriptor(
                first, last, diagnostic.message,
                if (diagnostic.isWarning) ProblemHighlightType.WARNING else ProblemHighlightType.GENERIC_ERROR,
                false,
            )
        }.toTypedArray()
    }
}

/**
 * Which files the IDE's problem-file service tracks: it ignores the rest, so without this no Inko file is
 * marked. The project's own Inko files (not its excluded `dep` and `build`, nor libraries).
 */
class InkoProblemFileFilter(private val project: Project) : Condition<VirtualFile> {
    override fun value(file: VirtualFile): Boolean =
        file.fileType == InkoFileType.INSTANCE && ProjectFileIndex.getInstance(project).isInContent(file)
}
