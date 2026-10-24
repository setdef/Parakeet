package com.setdef.parakeet

import com.google.gson.JsonObject
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.Lsp4jClient
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerListener
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerNotificationsHandler
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor
import com.intellij.platform.lsp.api.customization.LspCallHierarchyCustomizer
import com.intellij.platform.lsp.api.customization.LspCallHierarchyDisabled
import com.intellij.platform.lsp.api.customization.LspCodeActionsCustomizer
import com.intellij.platform.lsp.api.customization.LspCodeActionsDisabled
import com.intellij.platform.lsp.api.customization.LspCodeLensCustomizer
import com.intellij.platform.lsp.api.customization.LspCodeLensDisabled
import com.intellij.platform.lsp.api.customization.LspCommandsCustomizer
import com.intellij.platform.lsp.api.customization.LspCommandsDisabled
import com.intellij.platform.lsp.api.customization.LspCompletionCustomizer
import com.intellij.platform.lsp.api.customization.LspCompletionDisabled
import com.intellij.platform.lsp.api.customization.LspCustomization
import com.intellij.platform.lsp.api.customization.LspDiagnosticsCustomizer
import com.intellij.platform.lsp.api.customization.LspDiagnosticsDisabled
import com.intellij.platform.lsp.api.customization.LspDocumentColorCustomizer
import com.intellij.platform.lsp.api.customization.LspDocumentColorDisabled
import com.intellij.platform.lsp.api.customization.LspDocumentHighlightsCustomizer
import com.intellij.platform.lsp.api.customization.LspDocumentHighlightsDisabled
import com.intellij.platform.lsp.api.customization.LspDocumentLinkCustomizer
import com.intellij.platform.lsp.api.customization.LspDocumentLinkDisabled
import com.intellij.platform.lsp.api.customization.LspDocumentSymbolCustomizer
import com.intellij.platform.lsp.api.customization.LspDocumentSymbolDisabled
import com.intellij.platform.lsp.api.customization.LspFindReferencesCustomizer
import com.intellij.platform.lsp.api.customization.LspFindReferencesDisabled
import com.intellij.platform.lsp.api.customization.LspFoldingRangeCustomizer
import com.intellij.platform.lsp.api.customization.LspFoldingRangeDisabled
import com.intellij.platform.lsp.api.customization.LspFormattingCustomizer
import com.intellij.platform.lsp.api.customization.LspFormattingDisabled
import com.intellij.platform.lsp.api.customization.LspGoToDefinitionCustomizer
import com.intellij.platform.lsp.api.customization.LspGoToDefinitionDisabled
import com.intellij.platform.lsp.api.customization.LspGoToTypeDefinitionCustomizer
import com.intellij.platform.lsp.api.customization.LspGoToTypeDefinitionSupport
import com.intellij.platform.lsp.api.customization.LspHoverCustomizer
import com.intellij.platform.lsp.api.customization.LspHoverDisabled
import com.intellij.platform.lsp.api.customization.LspInlayHintCustomizer
import com.intellij.platform.lsp.api.customization.LspInlayHintDisabled
import com.intellij.platform.lsp.api.customization.LspOnTypeFormattingCustomizer
import com.intellij.platform.lsp.api.customization.LspOnTypeFormattingDisabled
import com.intellij.platform.lsp.api.customization.LspOptimizeImportsCustomizer
import com.intellij.platform.lsp.api.customization.LspOptimizeImportsDisabled
import com.intellij.platform.lsp.api.customization.LspRenameCustomizer
import com.intellij.platform.lsp.api.customization.LspRenameDisabled
import com.intellij.platform.lsp.api.customization.LspSelectionRangeCustomizer
import com.intellij.platform.lsp.api.customization.LspSelectionRangeDisabled
import com.intellij.platform.lsp.api.customization.LspSemanticTokensCustomizer
import com.intellij.platform.lsp.api.customization.LspSemanticTokensDisabled
import com.intellij.platform.lsp.api.customization.LspSignatureHelpCustomizer
import com.intellij.platform.lsp.api.customization.LspSignatureHelpDisabled
import com.intellij.platform.lsp.api.customization.LspSymbolKindCustomizer
import com.intellij.platform.lsp.api.customization.LspTypeHierarchyCustomizer
import com.intellij.platform.lsp.api.customization.LspTypeHierarchyDisabled
import com.intellij.platform.lsp.api.customization.LspWorkspaceSymbolCustomizer
import com.intellij.platform.lsp.api.customization.LspWorkspaceSymbolDisabled
import com.intellij.util.concurrency.AppExecutorUtil
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.GeneralClientCapabilities
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Cuttlebone, a language server for Inko (an executable the user installs),
 * answers with the compiler's own types and definitions. Parakeet runs one per
 * project through IntelliJ's LSP API, which owns the process, document sync
 * and cancellation. Parakeet's own features ask it questions
 * ([CuttleboneQueries]) and fall back to their heuristics when it has no answer.
 */
object Cuttlebone {
    private val LOG = logger<Cuttlebone>()

    /** The executable from the settings, or `cuttlebone` on PATH or where `cargo install` puts it. */
    fun executable(project: Project): Path? {
        val setting = InkoProjectSettings.getInstance(project).cuttlebone
        if (setting.isNotEmpty()) {
            if (!setting.contains('/') && !setting.contains('\\')) return PathEnvironmentVariableUtil.findInPath(setting)?.toPath()
            return Path.of(setting).takeIf(Files::isExecutable)
        }
        return PathEnvironmentVariableUtil.findInPath("cuttlebone")?.toPath()
            ?: System.getProperty("user.home")?.let { Path.of(it, ".cargo", "bin", "cuttlebone") }?.takeIf(Files::isExecutable)
    }

    /** `cuttlebone 0.1.0 (Inko 0.21.1)` → `0.21.1`. */
    fun linkedInko(versionOutput: String): String? = Regex("""\(Inko ([^)\s]+)\)""").find(versionOutput)?.groupValues?.get(1)

    /** Why Cuttlebone isn't answering, or that it is: shown in the settings, never as a popup. */
    sealed class Status(val message: String) {
        class NotFound(setting: String) :
            Status(if (setting.isEmpty()) "Cuttlebone isn't installed (no cuttlebone on PATH), so types are guessed." else "Cuttlebone wasn't found at $setting, so types are guessed.")
        data object NoInko : Status("Cuttlebone needs the inko executable, which isn't set up.")
        class Refused(reason: String) : Status("Cuttlebone refused this project: $reason Types are guessed.")
        class Stopped(reason: String) : Status("Cuttlebone stopped: $reason Types are guessed until it restarts.")
        data object Starting : Status("Cuttlebone is starting.")
        class Running(version: String) : Status("Using $version for types and definitions.")
    }

    /**
     * Whether Cuttlebone can serve [project], checked before starting it so a
     * missing executable or an Inko version it doesn't link never gets as far
     * as a process (or IntelliJ's own error reporting). Runs processes: not on the EDT.
     */
    fun precheck(project: Project): Status? {
        val executable = executable(project) ?: return Status.NotFound(InkoProjectSettings.getInstance(project).cuttlebone)
        val cli = InkoCli.getInstance(project)
        if (!cli.isAvailable) return Status.NoInko
        val output = try {
            CapturingProcessHandler(GeneralCommandLine(executable.toString(), "--version")).runProcess(10_000)
        } catch (e: Exception) {
            LOG.info("couldn't run $executable --version", e)
            return Status.Refused("$executable --version failed (${e.message}).")
        }
        if (output.exitCode != 0 || output.isTimeout) return Status.Refused("$executable --version failed.")
        val linked = linkedInko(output.stdout) ?: return null
        val installed = cli.version() ?: return null
        return if (linked != installed) Status.Refused("${output.stdout.trim()} works with Inko $linked only, but the project uses Inko $installed.") else null
    }
}

/**
 * Starts Cuttlebone when an Inko file opens, once [CuttleboneService] has checked it can run.
 * Without compatibility stubs: Kotlin would otherwise add overrides of 2026.1's new
 * default methods that call methods 2025.3 doesn't have.
 */
@JvmDefaultWithoutCompatibility
class CuttleboneSupportProvider : LspServerSupportProvider {
    override fun fileOpened(project: Project, file: VirtualFile, serverStarter: LspServerSupportProvider.LspServerStarter) {
        if (file.extension != Inko.EXTENSION || !file.isInLocalFileSystem) return
        val service = CuttleboneService.getInstance(project)
        if (service.canStart()) serverStarter.ensureServerStarted(CuttleboneDescriptor(project))
    }
}

class CuttleboneDescriptor(project: Project) : ProjectWideLspServerDescriptor(project, "Cuttlebone") {
    override fun isSupportedFile(file: VirtualFile): Boolean = file.extension == Inko.EXTENSION

    override fun createCommandLine(): GeneralCommandLine {
        val executable = Cuttlebone.executable(project) ?: throw com.intellij.execution.ExecutionException("cuttlebone wasn't found")
        return GeneralCommandLine(executable.toString()).withCharset(Charsets.UTF_8)
    }

    override fun createInitializationOptions(): Any = JsonObject().apply {
        InkoCli.getInstance(project).commandLine(emptyList(), null)?.exePath?.let { addProperty("inko", it) }
        addProperty("statusNotifications", true)
    }

    // UTF-16 positions, which are IntelliJ's offsets.
    override val clientCapabilities: ClientCapabilities
        get() = super.clientCapabilities.apply {
            val general = general ?: GeneralClientCapabilities().also { general = it }
            general.positionEncodings = listOf("utf-16")
        }

    override fun createLsp4jClient(handler: LspServerNotificationsHandler): Lsp4jClient =
        CuttleboneClient(handler, CuttleboneService.getInstance(project).statuses)

    override val lspServerListener: LspServerListener = object : LspServerListener {
        override fun serverInitialized(params: InitializeResult) = CuttleboneService.getInstance(project).initialized(params)

        override fun serverStopped(shutdownNormally: Boolean) = CuttleboneService.getInstance(project).stopped(shutdownNormally)
    }

    override val lspCustomization: LspCustomization = CuttleboneCustomization.create()
}

/**
 * Which of IntelliJ's LSP features run. IntelliJ turns on every feature the
 * server advertises unless this turns it off, so each one is set here, and
 * CuttleboneOwnershipTest fails when an IDE adds one that isn't. Each feature
 * has one owner: Parakeet keeps the ones that have a heuristic to fall back on
 * when Cuttlebone has no answer (and asks Cuttlebone itself), and IntelliJ
 * gets the ones where it can't make things worse.
 */
abstract class CuttleboneCustomization : LspCustomization() {
    // Parakeet's references resolve through Cuttlebone's definition (InkoResolver).
    override val goToDefinitionCustomizer: LspGoToDefinitionCustomizer get() = LspGoToDefinitionDisabled
    // Parakeet has no Go to Type Declaration of its own.
    override val goToTypeDefinitionCustomizer: LspGoToTypeDefinitionCustomizer get() = LspGoToTypeDefinitionSupport()
    // Parakeet's documentation shows the signature and doc comment, and adds hover's type.
    override val hoverCustomizer: LspHoverCustomizer get() = LspHoverDisabled
    // Parakeet's completion uses Cuttlebone's members after `.`, and its own names elsewhere.
    override val completionCustomizer: LspCompletionCustomizer get() = LspCompletionDisabled
    // Find Usages is Parakeet's, on IntelliJ's PSI.
    override val findReferencesCustomizer: LspFindReferencesCustomizer get() = LspFindReferencesDisabled
    // Errors come from `inko check`.
    override val diagnosticsCustomizer: LspDiagnosticsCustomizer get() = LspDiagnosticsDisabled
    // Parakeet's parameter-name hints.
    override val inlayHintCustomizer: LspInlayHintCustomizer get() = LspInlayHintDisabled
    // Parakeet's parameter info.
    override val signatureHelpCustomizer: LspSignatureHelpCustomizer get() = LspSignatureHelpDisabled
    // Parakeet's usage highlighting.
    override val documentHighlightsCustomizer: LspDocumentHighlightsCustomizer get() = LspDocumentHighlightsDisabled
    // Parakeet's TextMate grammar highlights; the Structure view, folding and Go to Symbol are Parakeet's.
    override val semanticTokensCustomizer: LspSemanticTokensCustomizer get() = LspSemanticTokensDisabled
    override val documentSymbolCustomizer: LspDocumentSymbolCustomizer get() = LspDocumentSymbolDisabled
    override val workspaceSymbolCustomizer: LspWorkspaceSymbolCustomizer get() = LspWorkspaceSymbolDisabled
    override val foldingRangeCustomizer: LspFoldingRangeCustomizer get() = LspFoldingRangeDisabled
    // Only used for symbols, which are off.
    override val symbolKindCustomizer: LspSymbolKindCustomizer get() = LspSymbolKindCustomizer()
    // Parakeet's quick fixes and intentions; `inko fmt` formats.
    override val codeActionsCustomizer: LspCodeActionsCustomizer get() = LspCodeActionsDisabled
    override val commandsCustomizer: LspCommandsCustomizer get() = LspCommandsDisabled
    override val formattingCustomizer: LspFormattingCustomizer get() = LspFormattingDisabled
    // Inko has no colors or links to show.
    override val documentColorCustomizer: LspDocumentColorCustomizer get() = LspDocumentColorDisabled
    override val documentLinkCustomizer: LspDocumentLinkCustomizer get() = LspDocumentLinkDisabled

    companion object {
        /** The customization for this IDE: 2026.1 added features (rename, for one) that 2025.3 has no classes for. */
        fun create(): CuttleboneCustomization {
            val loader = LspCustomization::class.java.classLoader
            val newer = runCatching { Class.forName("com.intellij.platform.lsp.api.customization.LspRenameCustomizer", false, loader) }.isSuccess
            // Anonymous, as LspCustomization is override-only: the Plugin Verifier rejects constructing a named subclass.
            return if (newer) object : CuttleboneCustomization261() {} else object : CuttleboneCustomization() {}
        }
    }
}

/**
 * The features IntelliJ 2026.1 added. Only loaded there and later ([CuttleboneCustomization.create]):
 * in 2025.3 these classes don't exist, and neither do the features.
 */
abstract class CuttleboneCustomization261 : CuttleboneCustomization() {
    // Parakeet's Rename, Call and Type Hierarchy, Extend Selection and Optimize Imports.
    override val renameCustomizer: LspRenameCustomizer get() = LspRenameDisabled
    override val callHierarchyCustomizer: LspCallHierarchyCustomizer get() = LspCallHierarchyDisabled
    override val typeHierarchyCustomizer: LspTypeHierarchyCustomizer get() = LspTypeHierarchyDisabled
    override val selectionRangeCustomizer: LspSelectionRangeCustomizer get() = LspSelectionRangeDisabled
    override val optimizeImportsCustomizer: LspOptimizeImportsCustomizer get() = LspOptimizeImportsDisabled
    // Parakeet's usage counts; Parakeet's typing aids.
    override val codeLensCustomizer: LspCodeLensCustomizer get() = LspCodeLensDisabled
    override val onTypeFormattingCustomizer: LspOnTypeFormattingCustomizer get() = LspOnTypeFormattingDisabled
}

/** A `cuttlebone/status` notification: whether the response that follows it is a fresh answer, and if not, why. */
data class CuttleboneStatus(val method: String, val uri: String, val status: String, val reason: String?, val elapsedMs: Double?) {
    val fresh: Boolean get() = status == "fresh"
}

/**
 * Receives `cuttlebone/status`. Cuttlebone sends it just before each query's
 * response; lsp4j handles both on its reader thread, in order, so the latest
 * status for a method and document belongs to the next response for them.
 */
class CuttleboneClient(handler: LspServerNotificationsHandler, private val statuses: ConcurrentHashMap<Pair<String, String>, CuttleboneStatus>) : Lsp4jClient(handler) {
    @JsonNotification("cuttlebone/status")
    fun status(params: JsonObject) {
        val method = params.get("method")?.takeIf { it.isJsonPrimitive }?.asString ?: return
        val uri = params.getAsJsonObject("textDocument")?.get("uri")?.asString ?: return
        val status = params.get("status")?.takeIf { it.isJsonPrimitive }?.asString ?: return
        // `reason` is absent, or null, for a fresh answer.
        val reason = params.get("reason")?.takeIf { it.isJsonObject }?.asJsonObject?.get("code")?.takeIf { it.isJsonPrimitive }?.asString
        val elapsed = params.get("elapsedMs")?.takeIf { it.isJsonPrimitive }?.asDouble
        statuses[method to uri] = CuttleboneStatus(method, uri, status, reason, elapsed)
    }
}

/**
 * The project's Cuttlebone: whether it can start, its status, and restarts
 * after a crash (with backoff, up to [MAX_RESTARTS] in a row).
 */
@Service(Service.Level.PROJECT)
class CuttleboneService(private val project: Project) : com.intellij.openapi.Disposable {
    /** Latest `cuttlebone/status` per (method, URI), taken by the response that follows it. */
    val statuses = ConcurrentHashMap<Pair<String, String>, CuttleboneStatus>()

    /** When the running server initialized (System.nanoTime), or 0. */
    @Volatile private var initializedAt = 0L

    /**
     * Whether IntelliJ has sent the server the text of the files open in editors.
     * It does so right after `initialize`, and then as files open, but reports
     * that only through internal API, so this allows it [SYNC_GRACE_MS].
     */
    fun editorsSynced(): Boolean =
        initializedAt != 0L && System.nanoTime() - initializedAt > TimeUnit.MILLISECONDS.toNanos(SYNC_GRACE_MS)

    @Volatile var status: Cuttlebone.Status? = null
        private set

    /** The precheck's result, once it ran; null means it may start. */
    @Volatile private var checked: Result<Cuttlebone.Status?>? = null
    @Volatile private var checking = false
    @Volatile private var generation = 0
    @Volatile private var everInitialized = false
    @Volatile private var restarts = 0

    /** Whether to start the server now. The first call runs the precheck in the background and starts it afterwards. */
    fun canStart(): Boolean {
        if (project.isDefault) return false
        checked?.let { return it.getOrNull() == null }
        synchronized(this) {
            if (checking) return false
            checking = true
        }
        val generation = generation
        AppExecutorUtil.getAppExecutorService().execute {
            val result = runCatching { Cuttlebone.precheck(project) }
            synchronized(this) {
                checking = false
                // Reset meanwhile (settings changed): this result is for the old settings.
                if (generation != this.generation) return@execute
                checked = result
            }
            val problem = result.getOrElse { Cuttlebone.Status.Refused(it.message ?: it.toString()) }
            if (problem != null) report(problem) else {
                report(Cuttlebone.Status.Starting)
                if (!project.isDisposed) LspServerManager.getInstance(project).startServersIfNeeded(CuttleboneSupportProvider::class.java)
            }
        }
        return false
    }

    /** The settings changed (the executable or the toolchain): check again, and start it if it can. */
    fun settingsChanged() {
        reset()
        canStart()
    }

    /** Stops the server, and forgets the precheck and restarts. */
    fun reset() {
        synchronized(this) {
            generation++
            checking = false
        }
        checked = null
        status = null
        everInitialized = false
        restarts = 0
        statuses.clear()
        initializedAt = 0
        if (!project.isDefault) LspServerManager.getInstance(project).stopServers(CuttleboneSupportProvider::class.java)
    }

    fun initialized(result: InitializeResult) {
        initializedAt = System.nanoTime()
        everInitialized = true
        restarts = 0
        val info = result.serverInfo
        report(Cuttlebone.Status.Running(listOfNotNull(info?.name ?: "cuttlebone", info?.version).joinToString(" ")))
    }

    fun stopped(normally: Boolean) {
        initializedAt = 0
        if (normally) return
        statuses.clear()
        // A server that never initialized refused the session (or couldn't start): retrying won't help.
        if (!everInitialized) {
            report(Cuttlebone.Status.Refused("it stopped before answering (see idea.log)."))
            checked = Result.success(status)
            return
        }
        if (restarts >= MAX_RESTARTS) {
            report(Cuttlebone.Status.Stopped("it crashed $MAX_RESTARTS times in a row, so it won't restart until the settings change or the project reopens."))
            checked = Result.success(status)
            return
        }
        val delay = BACKOFF_MS shl restarts
        restarts++
        report(Cuttlebone.Status.Stopped("it crashed; restarting in ${delay / 1000.0} s."))
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            // A crashed server stays in the manager; only a restart replaces it.
            if (!project.isDisposed) LspServerManager.getInstance(project).stopAndRestartIfNeeded(CuttleboneSupportProvider::class.java)
        }, delay, TimeUnit.MILLISECONDS)
    }

    // Said once, in idea.log and the settings page (InkoConfigurable), never as a popup.
    private fun report(status: Cuttlebone.Status) {
        if (status.message != this.status?.message) LOG.info(status.message)
        this.status = status
    }

    /** The running server, if any (started lazily, when an Inko file opens). */
    fun server(): LspServer? = if (project.isDefault) null else LspServerManager.getInstance(project)
        .getServersForProvider(CuttleboneSupportProvider::class.java).firstOrNull { it.state == LspServerState.Running }

    override fun dispose() {}

    companion object {
        private val LOG = logger<CuttleboneService>()
        const val MAX_RESTARTS = 5
        const val BACKOFF_MS = 1000L
        const val SYNC_GRACE_MS = 1000L

        fun getInstance(project: Project): CuttleboneService = project.service()
    }
}
