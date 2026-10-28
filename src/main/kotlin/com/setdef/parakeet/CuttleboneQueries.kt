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
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
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
 * Asks Cuttlebone, through IntelliJ's connection to it, for the compiler's
 * answers. Every question has a time budget; when it runs out, or there's no
 * server, there's no answer (null), and the feature has nothing to show: no
 * guess stands in for Cuttlebone. The one exception is code Cuttlebone can't
 * check because it's being edited ([Answer.editing]), where some features fall
 * back to Parakeet's own reading of the code. A `stale` answer is returned only
 * to the questions that take one (`stale = true`): those whose answers are shown
 * and change no code. Answers are cached until the project's PSI changes, so
 * IntelliJ asking again (resolve is called constantly) costs nothing.
 *
 * Highlighting passes never wait, unless the question says so: they use answers
 * already cached. Nothing waits on the EDT or in a write action either.
 */
@Service(Service.Level.PROJECT)
class CuttleboneQueries(private val project: Project) : com.intellij.openapi.Disposable {
    /**
     * Cuttlebone's answer: `fresh` (perhaps [repaired]), or [stale] where the feature asked for it may be;
     * [value] may still be null (nothing there).
     *
     * Or, when [editing], no answer because the code as it is can't be type checked there: it doesn't parse,
     * a declaration has an error, or the expression has one (`noHir`, `declarationErrors`, `untyped`), or only
     * a stale answer exists where the question doesn't take one. Such an answer has no [value]. It's the only
     * state in which a feature may use Parakeet's own reading of the code instead: mid-edit, where the user
     * would otherwise lose something they need.
     */
    data class Answer<T>(val value: T?, val status: CuttleboneStatus?, val editing: Boolean = false) {
        val repaired: Boolean get() = status?.repaired == true
        val stale: Boolean get() = status?.stale == true
    }

    /** One question, to one server process (a restarted one is asked again); [variant] tells apart what a position doesn't. */
    private data class Key(val server: Int, val method: String, val uri: String, val offset: Int, val variant: String = "")

    private val cache = ConcurrentHashMap<Key, Pending>()
    private val cachedAt = AtomicLong(-1)

    /**
     * How many times the text of the project's Inko documents has changed. Since milestone 7, Cuttlebone answers
     * each request for the text it was asked about, even after an edit, at a check each: so a request sent
     * before an edit, that nothing is waiting for, is cancelled ([cancelOutdated]).
     */
    private val edits = AtomicLong()

    /** When the last of [edits] was made ([System.nanoTime]). */
    @Volatile private var editedAt = 0L

    /** The requests sent and not yet answered: the cache may have let go of them (any PSI change clears it). */
    private val unanswered: MutableSet<Pending> = ConcurrentHashMap.newKeySet()

    init {
        com.intellij.openapi.editor.EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : com.intellij.openapi.editor.event.DocumentListener {
            override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                if (file.extension != Inko.EXTENSION || project.isDisposed) return
                if (!com.intellij.openapi.roots.ProjectFileIndex.getInstance(project).isInContent(file)) return
                editedAt = System.nanoTime()
                edits.incrementAndGet()
                cancelOutdated()
                if (!com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).isFileOpen(file)) saveUnsynced()
            }
        }, this)
    }

    private val saving = java.util.concurrent.atomic.AtomicBoolean()

    /**
     * Saves, once the change is done, the project's Inko documents that Cuttlebone doesn't have and that have no
     * editor: IntelliJ sends Cuttlebone the text of the files open in an editor, and it reads the rest from
     * disk ([CuttleboneService.unsynced]). Those open in an editor are left to IntelliJ.
     */
    private fun saveUnsynced() {
        if (!saving.compareAndSet(false, true)) return
        ApplicationManager.getApplication().invokeLater({
            saving.set(false)
            val server = CuttleboneService.getInstance(project).server() ?: return@invokeLater
            val editors = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
            for (document in CuttleboneService.getInstance(project).unsynced(server)) {
                val file = FileDocumentManager.getInstance().getFile(document) ?: continue
                if (!editors.isFileOpen(file)) FileDocumentManager.getInstance().saveDocument(document)
            }
        }, com.intellij.openapi.application.ModalityState.nonModal(), project.disposed)
    }

    override fun dispose() {}

    /**
     * Waits until the project's Inko text has gone the daemon's reparse delay without an edit, as IntelliJ's
     * highlighting does before it starts: true once it has, false if [document] changed meanwhile, so that what
     * waited has nothing to show for the text it was given. For what runs on every keystroke (usage highlighting
     * runs each time the caret moves): in code being typed, each question about new text costs Cuttlebone a
     * check, up to three where it doesn't parse, about a name that's only half typed. Doesn't wait where nothing
     * may wait (the EDT, a write action): an action invoked there asks at once.
     */
    fun awaitStill(document: Document): Boolean {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread || application.isWriteAccessAllowed) return true
        val stamp = document.modificationStamp
        val still = TimeUnit.MILLISECONDS.toNanos(com.intellij.codeInsight.daemon.DaemonCodeAnalyzerSettings.getInstance().autoReparseDelay.toLong())
        while (true) {
            ProgressManager.checkCanceled()
            if (document.modificationStamp != stamp) return false
            val left = editedAt + still - System.nanoTime()
            if (left <= 0) return true
            Thread.sleep(minOf(TimeUnit.NANOSECONDS.toMillis(left) + 1, POLL_MS))
        }
    }

    /**
     * Cancels ($/cancelRequest) every request about text that has changed since, unless something is still
     * waiting for its answer: a search, completion (whose items are filtered as the person types on), or an
     * action. What waits in highlighting or a read action stops waiting when the text changes, and cancels its
     * request then ([await]). Answers already in are kept for as long as the cache is.
     */
    private fun cancelOutdated() {
        for (pending in unanswered) if (pending.waiters.get() == 0) cancelIfOutdated(pending)
    }

    /**
     * Cancels [pending] if its text has changed, the one Cuttlebone is checking included: that kills Cuttlebone's
     * worker, and the checks it kept, so the next check starts from nothing (about 40 ms more for std's json.inko),
     * which the typing replay measured costing about what finishing the outdated checks would.
     */
    private fun cancelIfOutdated(pending: Pending) {
        if (pending.outdated(edits.get())) pending.cancel()
    }

    /** How many requests were sent, for tests. */
    val sent = AtomicLong()

    /** What happens to each request sent to a server, for tests that measure it (the typing replay). */
    interface Trace {
        /** [request] (numbered from 1) was sent about [document] as it is now. */
        fun sent(request: Long, method: String, document: Document)
        fun answered(request: Long, status: CuttleboneStatus?)
        fun failed(request: Long, error: Throwable)
    }

    @Volatile var trace: Trace? = null

    /**
     * Fresh answers only, unless [stale]: resolve is built on it, and Rename, the refactorings, quick fixes
     * and imports change code from what it says, so only navigation and what only shows takes a stale one.
     * The highlighting passes wait for it (inspections and intentions resolve): after a file's first check,
     * Cuttlebone answers it from the check it kept.
     */
    fun definition(file: PsiFile, offset: Int, budgetMs: Long = RESOLVE_BUDGET_MS, stale: Boolean = false): Answer<List<Location>>? =
        query(file, offset, "textDocument/definition", budgetMs, ::locations, waitInHighlighting = true, stale = stale) { server, document, position ->
            server.textDocumentService.definition(DefinitionParams(document, position))
        }

    /**
     * Asks for the definition at [offset] without waiting, so that what resolves the name at the caret on the
     * EDT (where nothing waits: Change Signature or Inline from a call, an intention's action) has it already.
     */
    fun prefetchDefinition(file: PsiFile, offset: Int) {
        start(file, offset, "textDocument/definition", ::locations) { server, document, position ->
            server.textDocumentService.definition(DefinitionParams(document, position))
        }
    }

    /**
     * Every use of the symbol at [offset] in the project's `src/` and `test/`, and its declaration if it's
     * there, by the compiler's ids, from one check of the whole project (kept, like any other). Fresh only:
     * Rename changes code from it, and Cuttlebone never gives another kind. The usage counts' highlighting pass
     * waits for it too.
     */
    fun references(file: PsiFile, offset: Int, budgetMs: Long = SEARCH_BUDGET_MS): Answer<List<Location>>? =
        query(file, offset, "textDocument/references", budgetMs, { result: List<Location>? -> result?.toList() }, waitInHighlighting = true) { server, document, position ->
            @Suppress("UNCHECKED_CAST")
            (server.textDocumentService.references(ReferenceParams(document, position, ReferenceContext(true))) as CompletableFuture<List<Location>>)
        }

    /**
     * Where [file] folds, from its tokens: Cuttlebone answers at once, without a check, also in code that doesn't
     * parse. The folding pass waits for it, as the hints pass does. Folds only show, so a stale answer counts.
     */
    fun foldingRanges(file: PsiFile, budgetMs: Long = HINTS_BUDGET_MS): Answer<List<org.eclipse.lsp4j.FoldingRange>>? =
        query(file, 0, "textDocument/foldingRange", budgetMs, { ranges: List<org.eclipse.lsp4j.FoldingRange>? -> ranges }, waitInHighlighting = true, stale = true) { server, document, _ ->
            server.textDocumentService.foldingRange(org.eclipse.lsp4j.FoldingRangeRequestParams(document))
        }

    /**
     * The ranges around [offset], innermost first, from [file]'s tokens and declarations: Cuttlebone answers at
     * once, without a check, also in code that doesn't parse. Extend Selection asks on the EDT, so this one waits there.
     */
    fun selectionRanges(file: PsiFile, offset: Int, budgetMs: Long = SELECTION_BUDGET_MS): Answer<List<Range>>? =
        query(file, offset, "textDocument/selectionRange", budgetMs, { ranges: List<org.eclipse.lsp4j.SelectionRange>? ->
            ranges?.firstOrNull()?.let { innermost -> generateSequence(innermost) { it.parent }.map { it.range }.toList() }
        }, onEdt = true) { server, document, position ->
            server.textDocumentService.selectionRange(org.eclipse.lsp4j.SelectionRangeParams(document, listOf(position)))
        }

    /**
     * The methods calling the method whose name is at [offset], as their names: every call by the compiler's ids,
     * from one check of the whole project, complete or none. Only Call Hierarchy shows it.
     */
    fun callers(file: PsiFile, offset: Int, budgetMs: Long = SEARCH_BUDGET_MS): Answer<List<Location>>? =
        query(file, offset, "callHierarchy/incomingCalls", budgetMs, { calls: List<org.eclipse.lsp4j.CallHierarchyIncomingCall>? ->
            calls?.map { Location(it.from.uri, it.from.selectionRange) }
        }, stale = true) { server, document, position ->
            server.textDocumentService.callHierarchyIncomingCalls(org.eclipse.lsp4j.CallHierarchyIncomingCallsParams(item(document, position)))
        }

    /** The methods the method whose name is at [offset] calls, as their declarations' names. Only Call Hierarchy shows it. */
    fun callees(file: PsiFile, offset: Int, budgetMs: Long = SEARCH_BUDGET_MS): Answer<List<Location>>? =
        query(file, offset, "callHierarchy/outgoingCalls", budgetMs, { calls: List<org.eclipse.lsp4j.CallHierarchyOutgoingCall>? ->
            calls?.map { Location(it.to.uri, it.to.selectionRange) }
        }, stale = true) { server, document, position ->
            server.textDocumentService.callHierarchyOutgoingCalls(org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams(item(document, position)))
        }

    /**
     * What implements the trait, or the trait's method, whose name is at [offset], as their names: every type, or
     * every type's method, the type database records, from one check of the whole project, complete or none (fresh
     * only). The gutter icons' highlighting pass waits for it, and Rename changes code from it.
     */
    fun implementations(file: PsiFile, offset: Int, budgetMs: Long = SEARCH_BUDGET_MS): Answer<List<Location>>? =
        query(file, offset, "textDocument/implementation", budgetMs, ::locations, waitInHighlighting = true) { server, document, position ->
            server.textDocumentService.implementation(org.eclipse.lsp4j.ImplementationParams(document, position))
        }

    /**
     * Where what's at [offset] is declared: for a type's method implementing a trait's, the trait's method; for
     * anything else, its definition. A stale answer only where [stale] (Rename changes code from it). The gutter
     * icons' highlighting pass waits for it.
     */
    fun declaration(file: PsiFile, offset: Int, budgetMs: Long = SEARCH_BUDGET_MS, stale: Boolean = false): Answer<List<Location>>? =
        query(file, offset, "textDocument/declaration", budgetMs, ::locations, waitInHighlighting = true, stale = stale) { server, document, position ->
            server.textDocumentService.declaration(org.eclipse.lsp4j.DeclarationParams(document, position))
        }

    /**
     * The traits the type whose name is at [offset] implements, or the traits the trait there requires, as their
     * names: from the type database, checking the whole project, complete or none. Type Hierarchy, the gutter icons
     * (whose highlighting pass waits for it) and Generate show it.
     */
    fun supertypes(file: PsiFile, offset: Int, budgetMs: Long = SEARCH_BUDGET_MS): Answer<List<Location>>? =
        query(file, offset, "typeHierarchy/supertypes", budgetMs, { items: List<org.eclipse.lsp4j.TypeHierarchyItem>? ->
            items?.map { Location(it.uri, it.selectionRange) }
        }, waitInHighlighting = true, stale = true) { server, document, position ->
            server.textDocumentService.typeHierarchySupertypes(org.eclipse.lsp4j.TypeHierarchySupertypesParams(typeItem(document, position)))
        }

    /** The traits requiring the trait whose name is at [offset], then the types implementing it, as [supertypes] has them. */
    fun subtypes(file: PsiFile, offset: Int, budgetMs: Long = SEARCH_BUDGET_MS): Answer<List<Location>>? =
        query(file, offset, "typeHierarchy/subtypes", budgetMs, { items: List<org.eclipse.lsp4j.TypeHierarchyItem>? ->
            items?.map { Location(it.uri, it.selectionRange) }
        }, stale = true) { server, document, position ->
            server.textDocumentService.typeHierarchySubtypes(org.eclipse.lsp4j.TypeHierarchySubtypesParams(typeItem(document, position)))
        }

    /**
     * The edits that keep the imports of the modules in [from] (a file, or every module in a directory) working once
     * it's at [to]: each import's path, and each use by the module's own name, by URI. Asked before the move, from one
     * check of the whole project, complete or none (fresh only). [file] is an Inko file that moves. Cuttlebone answers
     * only where a module's name changes.
     */
    fun fileRenames(file: PsiFile, from: java.nio.file.Path, to: java.nio.file.Path, budgetMs: Long = SEARCH_BUDGET_MS): Answer<Map<String, List<org.eclipse.lsp4j.TextEdit>>>? {
        val rename = org.eclipse.lsp4j.FileRename(from.toUri().toString(), to.toUri().toString())
        return query(file, 0, FILE_RENAMES, budgetMs, { edit: org.eclipse.lsp4j.WorkspaceEdit? -> edit?.changes.orEmpty() }, variant = rename.toString()) { server, _, _ ->
            server.workspaceService.willRenameFiles(org.eclipse.lsp4j.RenameFilesParams(listOf(rename)))
        }
    }

    /**
     * The compiler's errors and warnings in [file], the same as `inko check` reports, from a full check of its project
     * with the documents exactly as they are (a document pull), never an older one. The highlighting pass waits for it.
     */
    fun diagnostics(file: PsiFile, budgetMs: Long = SEARCH_BUDGET_MS): Answer<List<org.eclipse.lsp4j.Diagnostic>>? =
        query(file, 0, "textDocument/diagnostic", budgetMs, { report: org.eclipse.lsp4j.DocumentDiagnosticReport? ->
            report?.let { if (it.isLeft) it.left.items else null }
        }, waitInHighlighting = true, pull = true) { server, document, _ ->
            server.textDocumentService.diagnostic(org.eclipse.lsp4j.DocumentDiagnosticParams(document))
        }

    fun typeDefinition(file: PsiFile, offset: Int, budgetMs: Long = INTERACTIVE_BUDGET_MS): Answer<List<Location>>? =
        query(file, offset, "textDocument/typeDefinition", budgetMs, ::locations) { server, document, position ->
            server.textDocumentService.typeDefinition(TypeDefinitionParams(document, position))
        }

    /** Shown in documentation and Expression Type, never written into code, so a stale answer counts: callers mark it. */
    fun hover(file: PsiFile, offset: Int, budgetMs: Long = INTERACTIVE_BUDGET_MS): Answer<Hover>? =
        query(file, offset, "textDocument/hover", budgetMs, { it }, stale = true) { server, document, position ->
            server.textDocumentService.hover(HoverParams(document, position))
        }

    /**
     * Members after `.` at [offset], in the real buffer: Cuttlebone inserts its own placeholder. Fresh
     * answers only, since choosing one inserts it (Cuttlebone never sends a stale one).
     */
    fun completion(file: PsiFile, offset: Int, budgetMs: Long = INTERACTIVE_BUDGET_MS): Answer<List<CompletionItem>>? =
        query(file, offset, "textDocument/completion", budgetMs, { result -> result?.let { if (it.isLeft) it.left else it.right.items } }) { server, document, position ->
            server.textDocumentService.completion(CompletionParams(document, position))
        }

    /**
     * Every inlay hint in [file] (inferred types, and parameter names at calls), in
     * one request. Unlike other questions, a highlighting pass waits for it, as
     * the hints pass is one. Hints are only shown, so a stale answer counts: they're marked.
     */
    fun inlayHints(file: PsiFile, budgetMs: Long = HINTS_BUDGET_MS): Answer<List<InlayHint>>? {
        val document = file.originalFile.virtualFile?.let { FileDocumentManager.getInstance().getDocument(it) } ?: return null
        val end = position(document, document.textLength)
        return query(file, 0, "textDocument/inlayHint", budgetMs, { hints: List<InlayHint>? -> hints }, waitInHighlighting = true, stale = true) { server, identifier, start ->
            server.textDocumentService.inlayHint(InlayHintParams(identifier, Range(start, end)))
        }
    }

    /**
     * The signature of the call whose parentheses contain [offset], and which of its parameters [offset] is in.
     * Only parameter info shows it, so a stale answer counts: it's marked.
     */
    fun signatureHelp(file: PsiFile, offset: Int, budgetMs: Long = INTERACTIVE_BUDGET_MS): Answer<SignatureHelp>? {
        return query(file, offset, "textDocument/signatureHelp", budgetMs, { help: SignatureHelp? -> help }, stale = true) { server, document, position ->
            server.textDocumentService.signatureHelp(SignatureHelpParams(document, position))
        }
    }

    /**
     * The names in [file] that mean what the one at [offset] means, by the compiler's ids. The
     * usage highlighting pass waits for it, until the caret moves (the request carries on, for the next pass).
     * Highlights change nothing, and Cuttlebone gives stale ones only when the list is complete, so they count.
     */
    fun documentHighlights(file: PsiFile, offset: Int, budgetMs: Long = HIGHLIGHT_BUDGET_MS): Answer<List<DocumentHighlight>>? {
        return query(file, offset, "textDocument/documentHighlight", budgetMs, { highlights: List<DocumentHighlight>? -> highlights }, waitInHighlighting = true, stale = true) { server, document, position ->
            server.textDocumentService.documentHighlight(DocumentHighlightParams(document, position))
        }
    }

    /**
     * For tests: answers each question (method, file, offset) in place of a server, as Cuttlebone's answer
     * and its status would arrive. A null answer is a question the server never answered.
     */
    @Volatile private var stub: ((String, VirtualFile, Int) -> Answer<*>?)? = null

    val stubbed: Boolean get() = stub != null

    @org.jetbrains.annotations.TestOnly
    fun stub(disposable: com.intellij.openapi.Disposable, answer: (method: String, file: VirtualFile, offset: Int) -> Answer<*>?) {
        stub = answer
        cache.clear()
        com.intellij.openapi.util.Disposer.register(disposable) { stub = null; cache.clear() }
    }

    private fun <R, T> query(
        file: PsiFile,
        offset: Int,
        method: String,
        budgetMs: Long,
        convert: (R?) -> T?,
        waitInHighlighting: Boolean = false,
        stale: Boolean = false,
        onEdt: Boolean = false,
        variant: String = "",
        /** A pull of diagnostics, which has no status: it's answered from a check of the documents as they are, or fails. */
        pull: Boolean = false,
        send: (LanguageServer, TextDocumentIdentifier, Position) -> CompletableFuture<R>,
    ): Answer<T>? {
        val pending = start(file, offset, method, convert, variant, send) ?: return null
        @Suppress("UNCHECKED_CAST")
        val answer = await(pending, budgetMs, wait = mayWait(waitInHighlighting, onEdt)) as Answer<T>?
        if (pull) return answer
        // Parakeet opts in to status notifications: without a `fresh` one (or a `stale` one, where it counts), there's
        // no usable answer. Any other status, including ones added later, is none.
        if (answer?.editing == true) return answer
        val status = answer?.status ?: return null
        if (status.fresh || (stale && status.stale)) return answer
        return if (status.stale || (status.status == "unavailable" && status.reason in EDITING)) Answer(null, status, editing = true) else null
    }

    /** Sends [send]'s request. It must return lsp4j's own future, which IntelliJ cancels; [convert] reads the result. */
    private fun <R, T> start(
        file: PsiFile,
        offset: Int,
        method: String,
        convert: (R?) -> T?,
        variant: String = "",
        send: (LanguageServer, TextDocumentIdentifier, Position) -> CompletableFuture<R>,
    ): Pending? {
        val virtualFile = file.originalFile.virtualFile ?: return null
        if (!virtualFile.isInLocalFileSystem || virtualFile.extension != Inko.EXTENSION) return null
        stub?.let { answer ->
            sent.incrementAndGet()
            return Pending(CompletableFuture.completedFuture(answer(method, virtualFile, offset)), EmptyProgressIndicator())
        }
        val service = CuttleboneService.getInstance(project)
        val server = service.server() ?: return null
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return null
        if (!service.isSynced(server, virtualFile, document)) return null
        // Nor while another document's text isn't Cuttlebone's: it would answer from what's on disk. One with no
        // editor is saved (as IntelliJ would before long), so that what asks again has an answer.
        if (service.unsynced(server).isNotEmpty()) {
            saveUnsynced()
            return null
        }
        if (offset < 0 || offset > document.textLength) return null
        awaitWrites()
        val identifier = server.getDocumentIdentifier(virtualFile)
        val position = position(document, offset)

        // Answers hold until any PSI in the project changes (an edit here, or in a module this one imports).
        val stamp = PsiModificationTracker.getInstance(project).modificationCount
        if (cachedAt.getAndSet(stamp) != stamp) cache.clear()
        val key = Key(System.identityHashCode(server), method, identifier.uri, offset, variant)
        cache[key]?.let { if (!it.answer.isCancelled && !it.indicator.isCanceled) return it }
        val pending = Pending(CompletableFuture(), EmptyProgressIndicator(), edits.get())
        cache.putIfAbsent(key, pending)?.let { if (!it.indicator.isCanceled) return it else cache[key] = pending }
        val request = sent.incrementAndGet()
        unanswered += pending
        val trace = trace
        trace?.sent(request, method, document)
        // IntelliJ's sendRequestSync turns a cancelled progress indicator into $/cancelRequest, so each
        // request waits on a pooled thread under an indicator of its own: cancelling what waits for it doesn't
        // cancel the request (see await), and an edit does (cancelOutdated).
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                var status: CompletableFuture<CuttleboneStatus?>? = null
                val result = ProgressManager.getInstance().runProcess(Computable {
                    server.sendRequestSync(REQUEST_TIMEOUT_MS) { lsp ->
                        send(lsp, identifier, position).also { future ->
                            // Runs on lsp4j's reader thread as the response arrives, right after its status.
                            // A side chain: the request's own future must reach IntelliJ, which cancels it.
                            status = future.thenApply {
                                service.statuses.remove(method to identifier.uri)
                                    // A file rename's names the first module that moves, in Cuttlebone's order.
                                    ?: service.statuses.keys.firstOrNull { it.first == method && method == FILE_RENAMES }?.let(service.statuses::remove)
                            }
                        }
                    }
                }, pending.indicator)
                unanswered -= pending
                // Every answer is kept, whatever its status: query decides which it may use.
                val answer = Answer(convert(result), status?.let { runCatching { it.get(1, TimeUnit.SECONDS) }.getOrNull() })
                trace?.answered(request, answer.status)
                pending.answer.complete(answer)
            } catch (e: Throwable) {
                unanswered -= pending
                trace?.failed(request, e)
                cache.remove(key, pending)
                pending.answer.completeExceptionally(e)
                if (e !is ProcessCanceledException) LOG.debug("Cuttlebone request failed", e)
            }
        }
        return pending
    }

    /**
     * Waits until what IntelliJ saved is on disk, where Cuttlebone reads the files no editor has. IntelliJ 2026.2
     * returns from a save before the disk has it (`vfs.async-content-write`), so a refactoring's edits to such files,
     * saved, could otherwise be asked about with the text before them on disk. 2025.3 writes before it returns, and
     * has no such method: hence reflection, which also keeps the Plugin Verifier from finding an experimental API.
     */
    private fun awaitWrites() {
        val flush = FLUSH ?: return
        try {
            flush.invoke(com.intellij.openapi.vfs.newvfs.ManagingFS.getInstance())
        } catch (e: java.lang.reflect.InvocationTargetException) {
            LOG.warn("couldn't wait for IntelliJ's writes to disk", e.targetException)
        }
    }

    /** A request: its answer, the indicator it's sent under, and how many [edits] there had been when it was sent. */
    class Pending(val answer: CompletableFuture<Answer<*>?>, val indicator: ProgressIndicator, private val edits: Long = 0) {
        /** How many are waiting for the answer right now ([await]). */
        val waiters = java.util.concurrent.atomic.AtomicInteger()

        fun outdated(edits: Long): Boolean = edits != this.edits && !answer.isDone

        /** IntelliJ's sendRequestSync turns the cancelled indicator into $/cancelRequest. */
        fun cancel() = indicator.cancel()
    }

    private fun await(pending: Pending, budgetMs: Long, wait: Boolean): Answer<*>? {
        val future = pending.answer
        if (future.isDone) return runCatching { future.getNow(null) }.getOrNull()
        if (!wait || budgetMs <= 0) return null
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs)
        pending.waiters.incrementAndGet()
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
        } finally {
            // IntelliJ cancelled whatever was waiting, or its budget ran out. If the caret moved, the request isn't
            // cancelled: that would kill Cuttlebone's worker and every check it kept, so the next question would
            // pay a whole check again, and its answer is kept for next time. If the text changed (an edit cancels
            // highlighting), nobody will see the answer, so it's cancelled, unless something else still waits for it.
            if (pending.waiters.decrementAndGet() == 0) cancelIfOutdated(pending)
        }
    }

    /** Where [location] points, as Parakeet's own element: the declaration's name, or a module's file. */
    fun target(location: Location): PsiElement? {
        // Not the server's descriptor: 2026.1 changed LspServer.getDescriptor's type, so no call to it works in both.
        // Cuttlebone sends file: URIs; a stub in tests may name an in-memory file by its URL.
        val path = runCatching { java.nio.file.Path.of(java.net.URI(location.uri)) }.getOrNull()
        val file = path?.let { com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByNioFile(it) }
            ?: (if (stub != null) com.intellij.openapi.vfs.VirtualFileManager.getInstance().findFileByUrl(location.uri) else null)
            ?: return null
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

        private val FLUSH: java.lang.reflect.Method? =
            runCatching { com.intellij.openapi.vfs.newvfs.ManagingFS::class.java.getMethod("flushPendingUpdates") }.getOrNull()
        const val FILE_RENAMES = "workspace/willRenameFiles"

        /** The reasons Cuttlebone gives for no answer in code being edited ([Answer.editing]). */
        val EDITING = setOf("noHir", "declarationErrors", "untyped")

        /** How a stale answer is marked where it's shown: it's from the last version of the code that type checked. */
        const val OUTDATED = "possibly outdated"

        /** How long resolve (go to definition, and everything built on references) waits. */
        const val RESOLVE_BUDGET_MS = 400L
        /** How long completion, documentation and Expression Type wait. */
        const val INTERACTIVE_BUDGET_MS = 1500L
        /** How long a search waits for one answer: it runs in the background, and can be cancelled. */
        const val SEARCH_BUDGET_MS = 10_000L
        /**
         * How long the usage highlighting pass waits. Usually Cuttlebone answers from a kept check at once, but a
         * file's first question pays its whole check, which takes longer the busier the machine is.
         */
        const val HIGHLIGHT_BUDGET_MS = 2000L
        /** How long the inlay hints pass waits for a file's hints. */
        const val HINTS_BUDGET_MS = 2000L
        /** How long Extend Selection waits, on the EDT: Cuttlebone answers from the tokens, in tens of milliseconds. */
        const val SELECTION_BUDGET_MS = 500L
        private const val POLL_MS = 10L
        /** Cuttlebone stops a check after 5 s (`checkTimeoutMs`); this only guards against a server that stopped answering. */
        private const val REQUEST_TIMEOUT_MS = 30_000

        fun getInstance(project: Project): CuttleboneQueries = project.service()

        /** [compute], under a modal progress titled [title] when it's asked on the EDT, where answers can't be waited for. */
        fun <T> offTheEdt(project: Project, title: String, compute: () -> T): T {
            if (!ApplicationManager.getApplication().isDispatchThread) return compute()
            return ProgressManager.getInstance().runProcessWithProgressSynchronously<T, RuntimeException>(
                { com.intellij.openapi.application.ReadAction.compute<T, RuntimeException>(compute) }, title, true, project,
            )
        }

        private fun locations(result: Either<List<Location>, List<LocationLink>>?): List<Location>? = result?.let {
            if (it.isLeft) it.left else it.right.map { link -> Location(link.targetUri, link.targetSelectionRange) }
        }

        /**
         * A hierarchy item for the declaration whose name is at [position]: Cuttlebone finds it again there (its
         * `selectionRange.start`), and reads nothing else of it.
         */
        private fun item(document: TextDocumentIdentifier, position: Position) =
            org.eclipse.lsp4j.CallHierarchyItem("", org.eclipse.lsp4j.SymbolKind.Method, document.uri, Range(position, position), Range(position, position))

        private fun typeItem(document: TextDocumentIdentifier, position: Position) =
            org.eclipse.lsp4j.TypeHierarchyItem("", org.eclipse.lsp4j.SymbolKind.Class, document.uri, Range(position, position), Range(position, position))

        fun position(document: Document, offset: Int): Position {
            val line = document.getLineNumber(offset)
            return Position(line, offset - document.getLineStartOffset(line))
        }

        fun offset(document: Document, position: Position): Int? {
            if (position.line < 0 || position.line >= document.lineCount) return null
            val start = document.getLineStartOffset(position.line)
            return (start + position.character).coerceAtMost(document.getLineEndOffset(position.line))
        }

        /**
         * Highlighting passes (unless [inHighlighting]), the EDT (unless [onEdt]: a question Cuttlebone answers
         * without a check), and write actions use cached answers only.
         */
        private fun mayWait(inHighlighting: Boolean = false, onEdt: Boolean = false): Boolean {
            val application = ApplicationManager.getApplication()
            if (application.isDispatchThread) return onEdt
            if (application.isWriteAccessAllowed) return false
            if (inHighlighting) return true
            val indicator = ProgressManager.getGlobalProgressIndicator()?.let(ProgressWrapper::unwrapAll)
            return indicator !is DaemonProgressIndicator
        }
    }
}
