package com.setdef.parakeet

import com.intellij.codeInsight.daemon.impl.DaemonProgressIndicator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressWrapper
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.DocumentHighlightParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.InlayHintParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureHelpParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TypeDefinitionParams
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageServer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * Asks Cuttlebone, through IntelliJ's connection to it, for what Parakeet would
 * otherwise guess. Every question has a time budget; when it runs out, or there's
 * no server, or Cuttlebone's status isn't `fresh`, the answer is null and callers
 * use their heuristic. Answers are cached until the project's PSI changes, so
 * IntelliJ asking again (resolve is called constantly) costs nothing.
 *
 * Highlighting passes never wait: they use answers already cached, so typing never
 * waits on the server. Nothing waits on the EDT or in a write action either.
 */
@Service(Service.Level.PROJECT)
class CuttleboneQueries(private val project: Project) {
    /** Cuttlebone's answer. Only `fresh` answers are returned; [value] may still be null (nothing there). */
    data class Answer<T>(val value: T?, val status: CuttleboneStatus?)

    /** One question, to one server process (a restarted one is asked again). */
    private data class Key(val server: Int, val method: String, val uri: String, val offset: Int)

    private val cache = ConcurrentHashMap<Key, Pending>()
    private val cachedAt = AtomicLong(-1)

    /** How many requests were sent, for tests. */
    val sent = AtomicLong()

    fun definition(file: PsiFile, offset: Int, budgetMs: Long = RESOLVE_BUDGET_MS): Answer<List<Location>>? =
        query(file, offset, "textDocument/definition", budgetMs, ::locations) { server, document, position ->
            server.textDocumentService.definition(DefinitionParams(document, position))
        }

    fun typeDefinition(file: PsiFile, offset: Int, budgetMs: Long = INTERACTIVE_BUDGET_MS): Answer<List<Location>>? =
        query(file, offset, "textDocument/typeDefinition", budgetMs, ::locations) { server, document, position ->
            server.textDocumentService.typeDefinition(TypeDefinitionParams(document, position))
        }

    fun hover(file: PsiFile, offset: Int, budgetMs: Long = INTERACTIVE_BUDGET_MS): Answer<Hover>? =
        query(file, offset, "textDocument/hover", budgetMs, { it }) { server, document, position ->
            server.textDocumentService.hover(HoverParams(document, position))
        }

    /** Members after `.` at [offset], in the real buffer: Cuttlebone inserts its own placeholder. */
    fun completion(file: PsiFile, offset: Int, budgetMs: Long = INTERACTIVE_BUDGET_MS): Answer<List<CompletionItem>>? =
        query(file, offset, "textDocument/completion", budgetMs, { result -> result?.let { if (it.isLeft) it.left else it.right.items } }) { server, document, position ->
            server.textDocumentService.completion(CompletionParams(document, position))
        }

    /**
     * Every inlay hint in [file] (inferred types, and parameter names at calls), in
     * one request. Unlike other questions, a highlighting pass waits for it, as
     * the hints pass is one.
     */
    fun inlayHints(file: PsiFile, budgetMs: Long = HINTS_BUDGET_MS): Answer<List<InlayHint>>? {
        // Cuttlebone before milestone 4 has none.
        if (capabilities()?.inlayHintProvider?.let { it.isRight || it.left == true } != true) return null
        val document = file.originalFile.virtualFile?.let { FileDocumentManager.getInstance().getDocument(it) } ?: return null
        val end = position(document, document.textLength)
        return query(file, 0, "textDocument/inlayHint", budgetMs, { hints: List<InlayHint>? -> hints }, waitInHighlighting = true) { server, identifier, start ->
            server.textDocumentService.inlayHint(InlayHintParams(identifier, Range(start, end)))
        }
    }

    /** The signature of the call whose parentheses contain [offset], and which of its parameters [offset] is in. */
    fun signatureHelp(file: PsiFile, offset: Int, budgetMs: Long = INTERACTIVE_BUDGET_MS): Answer<SignatureHelp>? {
        if (capabilities()?.signatureHelpProvider == null) return null
        return query(file, offset, "textDocument/signatureHelp", budgetMs, { help: SignatureHelp? -> help }) { server, document, position ->
            server.textDocumentService.signatureHelp(SignatureHelpParams(document, position))
        }
    }

    /**
     * The names in [file] that mean what the one at [offset] means, by the compiler's ids. The
     * usage highlighting pass waits for it, cancelled (and the request with it) when the caret moves.
     */
    fun documentHighlights(file: PsiFile, offset: Int, budgetMs: Long = RESOLVE_BUDGET_MS): Answer<List<DocumentHighlight>>? {
        if (capabilities()?.documentHighlightProvider?.let { it.isRight || it.left == true } != true) return null
        return query(file, offset, "textDocument/documentHighlight", budgetMs, { highlights: List<DocumentHighlight>? -> highlights }, waitInHighlighting = true) { server, document, position ->
            server.textDocumentService.documentHighlight(DocumentHighlightParams(document, position))
        }
    }

    private fun capabilities() = CuttleboneService.getInstance(project).server()?.initializeResult?.capabilities

    private fun <R, T> query(
        file: PsiFile,
        offset: Int,
        method: String,
        budgetMs: Long,
        convert: (R?) -> T?,
        waitInHighlighting: Boolean = false,
        send: (LanguageServer, TextDocumentIdentifier, Position) -> CompletableFuture<R>,
    ): Answer<T>? {
        val pending = start(file, offset, method, convert, send) ?: return null
        @Suppress("UNCHECKED_CAST")
        return await(pending, budgetMs, wait = mayWait(waitInHighlighting)) as Answer<T>?
    }

    /** Sends [send]'s request. It must return lsp4j's own future, which IntelliJ cancels; [convert] reads the result. */
    private fun <R, T> start(
        file: PsiFile,
        offset: Int,
        method: String,
        convert: (R?) -> T?,
        send: (LanguageServer, TextDocumentIdentifier, Position) -> CompletableFuture<R>,
    ): Pending? {
        val virtualFile = file.originalFile.virtualFile ?: return null
        if (!virtualFile.isInLocalFileSystem || virtualFile.extension != Inko.EXTENSION) return null
        val service = CuttleboneService.getInstance(project)
        val server = service.server() ?: return null
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return null
        if (!service.isSynced(server, virtualFile, document)) return null
        if (offset < 0 || offset > document.textLength) return null
        val identifier = server.getDocumentIdentifier(virtualFile)
        val position = position(document, offset)

        // Answers hold until any PSI in the project changes (an edit here, or in a module this one imports).
        val stamp = PsiModificationTracker.getInstance(project).modificationCount
        if (cachedAt.getAndSet(stamp) != stamp) cache.clear()
        val key = Key(System.identityHashCode(server), method, identifier.uri, offset)
        cache[key]?.let { if (!it.answer.isCancelled) return it }
        val pending = Pending(CompletableFuture(), EmptyProgressIndicator())
        cache.putIfAbsent(key, pending)?.let { return it }
        sent.incrementAndGet()
        // IntelliJ's sendRequestSync turns a cancelled progress indicator into $/cancelRequest, so each
        // request waits on a pooled thread under an indicator of its own, cancelled with the request.
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                var status: CompletableFuture<CuttleboneStatus?>? = null
                val result = ProgressManager.getInstance().runProcess(Computable {
                    server.sendRequestSync(REQUEST_TIMEOUT_MS) { lsp ->
                        send(lsp, identifier, position).also { future ->
                            // Runs on lsp4j's reader thread as the response arrives, right after its status.
                            // A side chain: the request's own future must reach IntelliJ, which cancels it.
                            status = future.thenApply { service.statuses.remove(method to identifier.uri) }
                        }
                    }
                }, pending.indicator)
                val answer = Answer(convert(result), status?.let { runCatching { it.get(1, TimeUnit.SECONDS) }.getOrNull() })
                // Parakeet opts in to status notifications: without a `fresh` one, there's no usable answer.
                pending.answer.complete(answer.takeIf { it.status?.fresh == true })
            } catch (e: Throwable) {
                cache.remove(key, pending)
                pending.answer.completeExceptionally(e)
                if (e !is ProcessCanceledException) LOG.debug("Cuttlebone request failed", e)
            }
        }
        return pending
    }

    /** A request: its answer, and the indicator that cancels it. */
    class Pending(val answer: CompletableFuture<Answer<*>?>, val indicator: ProgressIndicator) {
        fun cancel() {
            indicator.cancel()
            answer.cancel(false)
        }
    }

    private fun await(pending: Pending, budgetMs: Long, wait: Boolean): Answer<*>? {
        val future = pending.answer
        if (future.isDone) return runCatching { future.getNow(null) }.getOrNull()
        if (!wait || budgetMs <= 0) return null
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs)
        try {
            while (true) {
                ProgressManager.checkCanceled()
                val left = deadline - System.nanoTime()
                if (left <= 0) return null // Out of budget: the request carries on, and its answer is cached for next time.
                try {
                    return future.get(minOf(left, TimeUnit.MILLISECONDS.toNanos(POLL_MS)), TimeUnit.NANOSECONDS)
                } catch (_: TimeoutException) {
                } catch (_: ExecutionException) {
                    return null
                } catch (_: java.util.concurrent.CancellationException) {
                    return null
                }
            }
        } catch (e: ProcessCanceledException) {
            // IntelliJ cancelled whatever was waiting: cancel the request too ($/cancelRequest).
            pending.cancel()
            throw e
        }
    }

    /** Where [location] points, as Parakeet's own element: the declaration's name, or a module's file. */
    fun target(location: Location): PsiElement? {
        // Not the server's descriptor: 2026.1 changed LspServer.getDescriptor's type, so no call to it works in both.
        val path = runCatching { java.nio.file.Path.of(java.net.URI(location.uri)) }.getOrNull() ?: return null
        val file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByNioFile(path) ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(file) as? InkoFile ?: return null
        val range = location.range
        // A module's location is empty, at the start of its file.
        if (range.start == range.end && range.start.line == 0 && range.start.character == 0) return psiFile
        val document = psiFile.viewProvider.document ?: return null
        val offset = offset(document, range.start) ?: return null
        return PsiTreeUtil.getParentOfType(psiFile.findElementAt(offset), InkoName::class.java, false)
    }

    companion object {
        private val LOG = logger<CuttleboneQueries>()

        /** How long resolve (go to definition, and everything built on references) waits. */
        const val RESOLVE_BUDGET_MS = 400L
        /** How long completion, documentation and Expression Type wait. */
        const val INTERACTIVE_BUDGET_MS = 1500L
        /** How long the inlay hints pass waits for a file's hints. */
        const val HINTS_BUDGET_MS = 2000L
        private const val POLL_MS = 10L
        /** Cuttlebone stops a check after 5 s (`checkTimeoutMs`); this only guards against a server that stopped answering. */
        private const val REQUEST_TIMEOUT_MS = 30_000

        fun getInstance(project: Project): CuttleboneQueries = project.service()

        private fun locations(result: Either<List<Location>, List<LocationLink>>?): List<Location>? = result?.let {
            if (it.isLeft) it.left else it.right.map { link -> Location(link.targetUri, link.targetSelectionRange) }
        }

        fun position(document: Document, offset: Int): Position {
            val line = document.getLineNumber(offset)
            return Position(line, offset - document.getLineStartOffset(line))
        }

        fun offset(document: Document, position: Position): Int? {
            if (position.line < 0 || position.line >= document.lineCount) return null
            val start = document.getLineStartOffset(position.line)
            return (start + position.character).coerceAtMost(document.getLineEndOffset(position.line))
        }

        /** Highlighting passes (unless [inHighlighting]), the EDT, and write actions use cached answers only. */
        private fun mayWait(inHighlighting: Boolean = false): Boolean {
            val application = ApplicationManager.getApplication()
            if (application.isDispatchThread || application.isWriteAccessAllowed) return false
            if (inHighlighting) return true
            val indicator = ProgressManager.getGlobalProgressIndicator()?.let(ProgressWrapper::unwrapAll)
            return indicator !is DaemonProgressIndicator
        }
    }
}
