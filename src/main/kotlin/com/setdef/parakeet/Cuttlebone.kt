package com.setdef.parakeet

import com.google.gson.JsonObject
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.OSProcessHandler
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
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
import com.intellij.platform.lsp.api.customization.LspFoldingRangeSupport
import com.intellij.platform.lsp.api.customization.LspFormattingCustomizer
import com.intellij.platform.lsp.api.customization.LspFormattingSupport
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
import com.intellij.util.io.BaseDataReader
import com.intellij.util.io.BaseOutputReader
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.GeneralClientCapabilities
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Cuttlebone, a language server for Inko (an executable the user installs),
 * answers with the compiler's own types and definitions. Parakeet runs one per
 * project through IntelliJ's LSP API, which owns the process and document
 * sync. Parakeet requires it: its features ask it questions, and cancel those
 * an edit leaves nobody to see ([CuttleboneQueries]); those that need its answers are unavailable
 * without it. Only where it can't check the code being edited do some fall
 * back to Parakeet's own reading of the code ([CuttleboneQueries.Answer.editing]).
 */
object Cuttlebone {
    private val LOG = logger<Cuttlebone>()

    /** Where a Cuttlebone came from, most preferred first: each one stands in for those after it. */
    enum class Source(val description: String) {
        SETTING("set in these settings"),
        PATH("on PATH"),
        CARGO("in Cargo's bin directory"),
        DOWNLOADED("downloaded by Parakeet"),
    }

    data class Located(val path: Path, val source: Source)

    /**
     * Which Cuttlebone to use: the [setting] (a path, or a name on PATH) if there is one, even when it's
     * missing; else [installed] (`cuttlebone` on PATH, or in Cargo's bin directory); else the newest one
     * Parakeet [downloaded] for the project's [inko]. The setting and PATH are overrides: a download never
     * takes their place.
     */
    fun locate(
        setting: String,
        inko: String?,
        installed: () -> Located?,
        downloaded: (String) -> Path?,
        onPath: (String) -> Path? = { PathEnvironmentVariableUtil.findInPath(it)?.toPath() },
    ): Located? {
        if (setting.isNotEmpty()) {
            val path = if (!setting.contains('/') && !setting.contains('\\')) onPath(setting) else Path.of(setting).takeIf(Files::isExecutable)
            return path?.let { Located(it, Source.SETTING) }
        }
        installed()?.let { return it }
        return inko?.let(downloaded)?.let { Located(it, Source.DOWNLOADED) }
    }

    /** [locate] for [project], with its Inko version when known (it's needed only for a download). */
    fun locate(project: Project, inko: String?): Located? = locate(
        InkoProjectSettings.getInstance(project).cuttlebone,
        inko,
        { CuttleboneLocations.getInstance().installed().firstOrNull() },
        { CuttleboneDownloads.installed(it)?.executable },
    )

    /** The Cuttlebone the precheck chose, else the one the settings or PATH give. */
    fun executable(project: Project): Path? = CuttleboneService.getInstance(project).located?.path ?: locate(project, null)?.path

    /**
     * Whether a feature that finds a declaration's uses (Find Usages, Rename, Safe Delete, Change Signature, …)
     * can serve [element]: always for locals and modules, whose uses follow from the code alone, and for
     * anything else only while Cuttlebone can answer. Without it they're unavailable, not half done.
     */
    fun canSearch(element: PsiElement): Boolean =
        element is InkoFile || (element is InkoDeclaration && element.isLocal) || !CuttleboneService.getInstance(element.project).unavailable

    /** Says [action] needs Cuttlebone, in the editor if there is one. */
    fun showNeeded(project: Project, editor: com.intellij.openapi.editor.Editor?, action: String) {
        val message = "$action needs Cuttlebone, which isn't running (see Settings | Languages & Frameworks | Inko)."
        if (editor != null) com.intellij.refactoring.util.CommonRefactoringUtil.showErrorHint(project, editor, message, action, null)
        else com.intellij.refactoring.util.CommonRefactoringUtil.showErrorMessage(action, message, null, project)
    }

    /** `cuttlebone 0.1.0 (Inko 0.21.1)` → `0.21.1`. */
    fun linkedInko(versionOutput: String): String? = Regex("""\(Inko ([^)\s]+)\)""").find(versionOutput)?.groupValues?.get(1)

    /** Where README.md says how to set Cuttlebone up. */
    const val SETUP_DOCS = "https://github.com/setdef/Parakeet#cuttlebone"

    /** What needs Cuttlebone, for messages saying it's missing. */
    const val NEEDED_FOR = "go to definition, completion after a dot, parameter info, hints, Find Usages, Rename and the other refactorings"

    /**
     * Why Cuttlebone isn't answering, or that it is: shown in the settings. A [setup] problem is also
     * shown once as a notification ([CuttleboneService]), since Parakeet needs Cuttlebone.
     */
    sealed class Status(val message: String, val setup: Boolean = false) {
        /** [inko] is the project's Inko version, which a download would be for. */
        class NotFound(val setting: String, val inko: String? = null, skipped: List<Skipped> = emptyList()) : Status(
            when {
                setting.isNotEmpty() -> "Cuttlebone wasn't found at $setting"
                skipped.isEmpty() -> "Cuttlebone isn't installed (it isn't set, on PATH, or downloaded)"
                else -> "Cuttlebone isn't installed (it isn't set or downloaded, and ${skipped.joinToString(" or ") { "${it.located.path} ${it.located.source.description}" }} isn't a Cuttlebone Parakeet can use)"
            } + ", so $NEEDED_FOR are unavailable.",
            setup = true,
        )
        data object NoInko : Status("Cuttlebone needs the inko executable, which isn't set up.")
        class Refused(reason: String) : Status("Cuttlebone refused this project: $reason Until it runs, $NEEDED_FOR are unavailable.", setup = true)
        class Stopped(reason: String) : Status("Cuttlebone stopped: $reason Until it runs, $NEEDED_FOR are unavailable.")
        data object Starting : Status("Cuttlebone is starting.")
        class Running(version: String) : Status("Using $version for types and definitions.")
    }

    /** A `cuttlebone` that isn't a working Cuttlebone, and why. */
    data class Skipped(val located: Located, val reason: String)

    /** What `--version` printed, or why it doesn't count as a Cuttlebone's answer. */
    private class Version(val output: com.intellij.execution.process.ProcessOutput?, val problem: String?)

    private val VERSION = Regex("""^cuttlebone (\d+\.\d+\S*)""")

    /** The oldest Cuttlebone Parakeet uses: what it asks for (documentation in hover, symbols, folding, ...) is there since. */
    const val MINIMUM = "0.13.0"

    /** Whether Cuttlebone [version] (`0.13.0`, `0.13.0-fake`) is [MINIMUM] or newer. */
    fun supported(version: String): Boolean = Ivm.isAtLeast(version.substringBefore('-'), MINIMUM)

    private fun version(executable: Path): Version {
        val output = try {
            CapturingProcessHandler(GeneralCommandLine(executable.toString(), "--version")).runProcess(10_000)
        } catch (e: Exception) {
            LOG.info("couldn't run $executable --version", e)
            return Version(null, "--version failed (${e.message})")
        }
        val said = (output.stderr.trim().lineSequence().firstOrNull { it.isNotBlank() } ?: output.stdout.trim().lineSequence().firstOrNull())?.take(200)
        return when {
            output.isTimeout -> Version(output, "--version didn't answer in 10 s")
            output.exitCode != 0 -> Version(output, "--version failed" + (said?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: " (exit code ${output.exitCode})"))
            !VERSION.containsMatchIn(output.stdout.trim()) -> Version(output, "--version printed \"${output.stdout.trim().lineSequence().firstOrNull().orEmpty().take(200)}\", not a Cuttlebone version")
            !supported(VERSION.find(output.stdout.trim())!!.groupValues[1]) ->
                Version(output, "it's Cuttlebone ${VERSION.find(output.stdout.trim())!!.groupValues[1]}, and Parakeet needs $MINIMUM or newer")
            else -> Version(output, null)
        }
    }

    /**
     * Whether Cuttlebone can serve [project], checked before starting it so a
     * missing executable or an Inko version it doesn't link never gets as far
     * as a process (or IntelliJ's own error reporting). Runs processes: not on the EDT.
     */
    fun precheck(project: Project): Status? {
        val service = CuttleboneService.getInstance(project)
        val cli = InkoCli.getInstance(project)
        val setting = InkoProjectSettings.getInstance(project).cuttlebone
        // The Inko version, asked only when it's needed: for a download, or to compare with the one Cuttlebone links.
        val inko by lazy {
            (if (cli.isAvailable) cli.version() else null).also { service.checkedInko = it; service.inkoChecked = true }
        }
        // A `cuttlebone` on PATH or in Cargo's bin directory that doesn't run, or doesn't say it's Cuttlebone (a
        // mise shim for a tool that isn't installed, say), isn't one: it's skipped, as if it weren't there.
        val versions = mutableMapOf<Path, Version>()
        val skipped = mutableListOf<Skipped>()
        val working = if (setting.isNotEmpty()) null else CuttleboneLocations.getInstance().installed().firstOrNull { candidate ->
            val version = version(candidate.path).also { versions[candidate.path] = it }
            (version.problem == null).also { works ->
                if (!works) skipped += Skipped(candidate, version.problem!!).also { LOG.info("skipped ${it.located.path}: ${it.reason}") }
            }
        }
        service.skipped = skipped
        val find = { forInko: String? -> locate(setting, forInko, { working }, { CuttleboneDownloads.installed(it)?.executable }) }
        val located = find(null) ?: if (setting.isEmpty()) find(inko) else null
        service.located = located
        if (located == null) return Status.NotFound(setting, if (setting.isEmpty()) inko else null, skipped)
        val executable = located.path
        if (!cli.isAvailable) return Status.NoInko
        val version = versions[executable] ?: version(executable)
        // The setting, or a download: what it names is the user's to fix, or Parakeet's to report.
        version.problem?.let { return Status.Refused("$executable: $it.") }
        val output = version.output!!
        service.versionOutput = output.stdout.trim()
        val linked = linkedInko(output.stdout) ?: return null
        val installed = inko ?: return null
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
        val executable = CuttleboneService.getInstance(project).located?.path ?: throw com.intellij.execution.ExecutionException("cuttlebone wasn't found")
        return GeneralCommandLine(executable.toString()).withCharset(Charsets.UTF_8)
    }

    // IntelliJ's own process handler, with what it writes to the server read on the way.
    override fun startServerProcess(): OSProcessHandler {
        val service = CuttleboneService.getInstance(project)
        val sent = service.sentVersions
        sent.clear()
        return object : OSProcessHandler(createCommandLine()) {
            private val input by lazy { CuttleboneInputTap(super.getProcessInput(), sent, service.sentMessages) }

            override fun getProcessInput(): OutputStream = input

            // As IntelliJ's default: the server's output isn't lines, and it's quiet between answers.
            override fun readerOptions(): BaseOutputReader.Options = object : BaseOutputReader.Options() {
                override fun policy(): BaseDataReader.SleepingPolicy = forMostlySilentProcess().policy()
                override fun splitToLines(): Boolean = false
            }
        }
    }

    override fun createInitializationOptions(): Any = JsonObject().apply {
        InkoCli.getInstance(project).commandLine(emptyList(), null)?.exePath?.let { addProperty("inko", it) }
        addProperty("statusNotifications", true)
        // Errors and warnings are Parakeet's, from `inko check` (CuttleboneCustomization), so Cuttlebone needn't check for them.
        addProperty("diagnostics", false)
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
    // Errors come from `inko check`: IntelliJ's would have no quick fixes, aren't in Inspect Code, don't
    // mark files that aren't open, and vanish without Cuttlebone. Cuttlebone's are off (createInitializationOptions).
    override val diagnosticsCustomizer: LspDiagnosticsCustomizer get() = LspDiagnosticsDisabled
    // Parakeet's hints ask Cuttlebone for the whole file, and have a switch for types and one for parameter
    // names (IntelliJ's have one for everything); parameter names fall back to the heuristic in broken code.
    override val inlayHintCustomizer: LspInlayHintCustomizer get() = LspInlayHintDisabled
    // Parakeet's parameter info shows Cuttlebone's signature, and falls back to `inko doc`'s where it has none.
    override val signatureHelpCustomizer: LspSignatureHelpCustomizer get() = LspSignatureHelpDisabled
    // Parakeet's usage highlighting asks Cuttlebone's documentHighlight, and falls back to its own in broken code.
    override val documentHighlightsCustomizer: LspDocumentHighlightsCustomizer get() = LspDocumentHighlightsDisabled
    // Parakeet's TextMate grammar highlights; the Structure view and Go to Symbol are Parakeet's.
    override val semanticTokensCustomizer: LspSemanticTokensCustomizer get() = LspSemanticTokensDisabled
    override val documentSymbolCustomizer: LspDocumentSymbolCustomizer get() = LspDocumentSymbolDisabled
    override val workspaceSymbolCustomizer: LspWorkspaceSymbolCustomizer get() = LspWorkspaceSymbolDisabled
    // Cuttlebone's folding ranges: blocks, comment runs and imports, from the tokens, so also in code that doesn't parse.
    override val foldingRangeCustomizer: LspFoldingRangeCustomizer get() = LspFoldingRangeSupport()
    // Only used for symbols, which are off.
    override val symbolKindCustomizer: LspSymbolKindCustomizer get() = LspSymbolKindCustomizer()
    // Parakeet's quick fixes and intentions.
    override val codeActionsCustomizer: LspCodeActionsCustomizer get() = LspCodeActionsDisabled
    override val commandsCustomizer: LspCommandsCustomizer get() = LspCommandsDisabled
    // Cuttlebone's formatting is exactly `inko fmt`'s, from the upstream formatter, on the unsaved text: it formats
    // Inko files, though IntelliJ's own formatter would take any file it isn't told to leave.
    override val formattingCustomizer: LspFormattingCustomizer get() = object : LspFormattingSupport() {
        override fun shouldFormatThisFileExclusivelyByServer(file: VirtualFile, ideCanFormatThisFileItself: Boolean, serverExplicitlyWantsToFormatThisFile: Boolean) = true
    }
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

/**
 * The server's stdin, as IntelliJ writes it: passes every byte on, and records
 * each document's version as `didOpen` and `didChange` carry it (and forgets
 * it at `didClose`), once its message has been written.
 */
class CuttleboneInputTap(
    private val delegate: OutputStream,
    private val sent: MutableMap<String, Int>,
    /** How many messages of each method were written: requests, notifications and `$/cancelRequest`. */
    private val counts: MutableMap<String, java.util.concurrent.atomic.AtomicLong>? = null,
) : OutputStream() {
    private val buffer = java.io.ByteArrayOutputStream()

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    @Synchronized
    override fun write(b: ByteArray, off: Int, len: Int) {
        delegate.write(b, off, len)
        buffer.write(b, off, len)
        while (true) {
            val bytes = buffer.toByteArray()
            val headerEnd = indexOf(bytes, HEADER_END)
            if (headerEnd < 0) return
            val header = String(bytes, 0, headerEnd, Charsets.US_ASCII)
            val length = CONTENT_LENGTH.find(header)?.groupValues?.get(1)?.toIntOrNull() ?: return reset(bytes, headerEnd + HEADER_END.size)
            val bodyStart = headerEnd + HEADER_END.size
            if (bytes.size < bodyStart + length) return
            record(String(bytes, bodyStart, length, Charsets.UTF_8))
            reset(bytes, bodyStart + length)
        }
    }

    override fun flush() = delegate.flush()

    override fun close() = delegate.close()

    private fun reset(bytes: ByteArray, from: Int) {
        buffer.reset()
        buffer.write(bytes, from, bytes.size - from)
    }

    private fun record(body: String) {
        // Only document sync is parsed; anything else (or anything malformed) is only counted.
        val method = METHOD.find(body.take(200))?.groupValues?.get(1) ?: return
        counts?.getOrPut(method) { java.util.concurrent.atomic.AtomicLong() }?.incrementAndGet()
        if (method != "textDocument/didOpen" && method != "textDocument/didChange" && method != "textDocument/didClose") return
        val document = runCatching { com.google.gson.JsonParser.parseString(body).asJsonObject.getAsJsonObject("params").getAsJsonObject("textDocument") }.getOrNull() ?: return
        val uri = document.get("uri")?.asString ?: return
        if (method == "textDocument/didClose") sent.remove(uri) else document.get("version")?.asInt?.let { sent[uri] = it }
    }

    private companion object {
        val HEADER_END = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
        val CONTENT_LENGTH = Regex("""(?i)Content-Length:\s*(\d+)""")
        val METHOD = Regex(""""method"\s*:\s*"([^"]+)"""")

        fun indexOf(bytes: ByteArray, part: ByteArray): Int {
            outer@ for (i in 0..bytes.size - part.size) {
                for (j in part.indices) if (bytes[i + j] != part[j]) continue@outer
                return i
            }
            return -1
        }
    }
}

/**
 * A `cuttlebone/status` notification: whether the response that follows it is a fresh answer, and if not, why.
 * Since milestone 5, a fresh answer may come from a [repaired] copy (the documents with some lines blank,
 * outside anything the answer depends on), and an answer may be [stale]: from the last version of the
 * documents that type checked, mapped to where its text is now. Builds before that never send either.
 */
data class CuttleboneStatus(
    val method: String,
    val uri: String,
    val status: String,
    val reason: String?,
    val elapsedMs: Double?,
    val repaired: Boolean = false,
    /** From a check Cuttlebone kept from an earlier question about the same text, rather than one run for it (milestone 6). */
    val cached: Boolean = false,
) {
    val fresh: Boolean get() = status == "fresh"
    val stale: Boolean get() = status == "stale"
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
        // Absent before milestone 5, and only ever true on a fresh answer.
        val repaired = params.get("repaired")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true
        // Absent before milestone 6.
        val cached = params.get("cached")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true
        statuses[method to uri] = CuttleboneStatus(method, uri, status, reason, elapsed, repaired, cached)
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

    /** How many messages of each method IntelliJ has written to the servers it started ([CuttleboneInputTap]), for tests. */
    val sentMessages = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()

    /** The document version IntelliJ last sent the running server, per URI ([CuttleboneInputTap]). */
    val sentVersions = ConcurrentHashMap<String, Int>()

    /**
     * Whether the running server has [file]'s current text. IntelliJ reports
     * `didOpen` only through internal API, so Parakeet reads what it writes to
     * the server: a document it sent is synced once the version it sent is the
     * document's current one, and a file it never sent is read from disk.
     */
    fun isSynced(server: LspServer, file: VirtualFile, document: com.intellij.openapi.editor.Document): Boolean {
        val sent = sentVersions[server.getDocumentIdentifier(file).uri]
        return if (sent != null) sent == server.getDocumentVersion(document) else !FileDocumentManager.getInstance().isFileModified(file)
    }

    /**
     * The project's unsaved Inko documents the running server doesn't have as they are. Cuttlebone answers from
     * the whole project (a search checks all of it, and a type depends on what's imported), and reads from disk
     * each file IntelliJ hasn't sent it: IntelliJ sends only the files open in an editor. So a document changed
     * with no editor open (by a refactoring, or by undoing one) is only on disk once it's saved.
     */
    fun unsynced(server: LspServer): List<com.intellij.openapi.editor.Document> = FileDocumentManager.getInstance().unsavedDocuments.filter { document ->
        val file = FileDocumentManager.getInstance().getFile(document) ?: return@filter false
        file.extension == Inko.EXTENSION && file.isInLocalFileSystem && !project.isDisposed &&
            com.intellij.openapi.roots.ProjectFileIndex.getInstance(project).isInContent(file) && !isSynced(server, file, document)
    }

    @Volatile var status: Cuttlebone.Status? = null
        private set

    /** The Cuttlebone the precheck chose, and where it came from (shown in the settings). */
    @Volatile var located: Cuttlebone.Located? = null
        internal set

    /** What the precheck skipped on PATH or in Cargo's bin directory, as no working Cuttlebone. */
    @Volatile var skipped: List<Cuttlebone.Skipped> = emptyList()
        internal set

    /** What the chosen Cuttlebone's `--version` printed. */
    @Volatile var versionOutput: String? = null
        internal set

    /** The project's Inko version when the precheck ran, to notice when it changes ([inkoMayHaveChanged]). */
    @Volatile var checkedInko: String? = null
        internal set

    /** Whether the precheck asked for [checkedInko]: it doesn't when nothing depends on it (a setting that's missing). */
    @Volatile var inkoChecked = false
        internal set

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
        located = null
        skipped = emptyList()
        versionOutput = null
        checkedInko = null
        inkoChecked = false
        // New settings: if Cuttlebone still can't run, say so again.
        notified = false
        everInitialized = false
        restarts = 0
        statuses.clear()
        sentVersions.clear()
        if (!project.isDefault) LspServerManager.getInstance(project).stopServers(CuttleboneSupportProvider::class.java)
    }

    fun initialized(result: InitializeResult) {
        everInitialized = true
        restarts = 0
        val info = result.serverInfo
        report(Cuttlebone.Status.Running(listOfNotNull(info?.name ?: "cuttlebone", info?.version).joinToString(" ")))
        // A download is Parakeet's to keep up to date (by asking): an override is the user's.
        val located = located
        if (located?.source == Cuttlebone.Source.DOWNLOADED && !project.isDisposed) {
            CuttleboneDownloads.all().firstOrNull { it.executable == located.path }?.let { CuttleboneDownloads.offerUpdate(project, it) }
        }
    }

    /**
     * The project's Inko may have changed (ivm switched versions, say): if it did since the precheck, check
     * again, which finds the Cuttlebone for the new one, or offers to download it. Runs `inko --version`
     * (cached until the executable changes) in the background.
     */
    fun inkoMayHaveChanged() {
        if (project.isDefault || checked == null || !inkoChecked) return
        val before = checkedInko
        AppExecutorUtil.getAppExecutorService().execute {
            if (project.isDisposed || checked == null) return@execute
            val cli = InkoCli.getInstance(project)
            val now = if (cli.isAvailable) cli.version() else null
            if (now != before && checkedInko == before) {
                LOG.info("the project's Inko changed from $before to $now: checking Cuttlebone again")
                settingsChanged()
            }
        }
    }

    fun stopped(normally: Boolean) {
        sentVersions.clear()
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

    /**
     * Whether Cuttlebone can't answer at all: it isn't installed, refused the project, or stopped for good.
     * Features that need it are then unavailable. While it starts or restarts it counts as available:
     * its questions just have no answer yet.
     */
    val unavailable: Boolean
        get() {
            if (project.isDefault) return true
            if (CuttleboneQueries.getInstance(project).stubbed) return false
            return when (val status = status) {
                null -> Cuttlebone.executable(project) == null && CuttleboneDownloads.all().isEmpty()
                is Cuttlebone.Status.NotFound, is Cuttlebone.Status.Refused, Cuttlebone.Status.NoInko -> true
                // Stopped for good, rather than restarting.
                is Cuttlebone.Status.Stopped -> checked?.getOrNull() != null
                else -> false
            }
        }

    @Volatile private var notified = false

    // In idea.log and the settings page (InkoConfigurable); a setup problem also once as a notification.
    private fun report(status: Cuttlebone.Status) {
        if (status.message != this.status?.message) LOG.info(status.message)
        this.status = status
        if (status.setup && !notified && !project.isDisposed) {
            notified = true
            notifySetup(status)
        }
    }

    /**
     * Says once that Parakeet needs Cuttlebone. With none at all (nothing set, on PATH, or downloaded), it first
     * looks for the build for the project's Inko, and offers to download it: one click, and nothing without it.
     */
    private fun notifySetup(status: Cuttlebone.Status) {
        val inko = (status as? Cuttlebone.Status.NotFound)?.takeIf { it.setting.isEmpty() }?.inko
        if (inko == null) return notifySetup(status.message, null)
        val generation = generation
        CuttleboneDownloads.lookUp(inko) { lookup ->
            // The settings changed meanwhile: the new check says what it finds.
            if (generation != this.generation || project.isDisposed) return@lookUp
            notifySetup("${status.message} ${lookup.message}", (lookup as? CuttleboneLookup.Found)?.build)
        }
    }

    private fun notifySetup(message: String, download: CuttleboneBuild?) {
        val notification = com.intellij.notification.NotificationGroupManager.getInstance().getNotificationGroup("Parakeet for Inko")
            .createNotification("Parakeet needs Cuttlebone", message, com.intellij.notification.NotificationType.WARNING)
        if (download != null) notification.addAction(CuttleboneDownloads.downloadAction(project, download))
        notification.addAction(com.intellij.notification.NotificationAction.createSimple("How to set up Cuttlebone") {
            com.intellij.ide.BrowserUtil.browse(Cuttlebone.SETUP_DOCS)
        })
        notification.addAction(com.intellij.notification.NotificationAction.createSimple("Settings…") {
            com.intellij.openapi.options.ShowSettingsUtil.getInstance().showSettingsDialog(project, InkoConfigurable::class.java)
        })
        notification.notify(project)
    }

    /** Which Cuttlebone the project uses, and where it came from, once it's been checked: the settings' "In use" line. */
    fun inUse(): String? {
        fun code(path: Path) = "<code>${StringUtil.escapeXmlEntities(path.toString())}</code>"
        val located = located
        val skipped = skipped.joinToString(" ") {
            "Skipped ${code(it.located.path)} ${it.located.source.description}, which Parakeet can't use: ${StringUtil.escapeXmlEntities(it.reason)}."
        }
        if (located == null) return skipped.ifEmpty { null }?.let { "None. $it" }
        val version = versionOutput?.let { "${StringUtil.escapeXmlEntities(it)}, " }.orEmpty()
        return "$version${located.source.description}: ${code(located.path)}" + skipped.ifEmpty { null }?.let { ". $it" }.orEmpty()
    }

    /** The running server, if any (started lazily, when an Inko file opens). */
    fun server(): LspServer? = if (project.isDefault) null else LspServerManager.getInstance(project)
        .getServersForProvider(CuttleboneSupportProvider::class.java).firstOrNull { it.state == LspServerState.Running }

    override fun dispose() {}

    companion object {
        private val LOG = logger<CuttleboneService>()
        const val MAX_RESTARTS = 5
        const val BACKOFF_MS = 1000L

        fun getInstance(project: Project): CuttleboneService = project.service()
    }
}
