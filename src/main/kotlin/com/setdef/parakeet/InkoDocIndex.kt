package com.setdef.parakeet

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.EditorNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.listDirectoryEntries

/**
 * Definitions for each Inko project, from `inko doc --private --dependencies`
 * run in a mirror of the project, rebuilt in the background when the project's
 * Inko files change. (In tests, rebuilds happen synchronously.)
 *
 * `inko doc` only works on a project that compiles, and only sees saved files,
 * so lookups fill the gaps from source: files changed since the last
 * successful run, and unsaved ones, use [InkoSourceDefinitions]. When the
 * project doesn't compile at all, the standard library and dependencies are
 * still documented (through a stand-in module that imports them), and every
 * project file is read from source.
 */
@Service(Service.Level.PROJECT)
class InkoDocIndex(private val project: Project, private val scope: CoroutineScope) {
    private class Entry {
        /** The last good definitions, and when the run that produced them started. */
        @Volatile var base: List<InkoDefinition>? = null
        @Volatile var stale = true
        val running = AtomicBoolean()

        /** Files changed since [base] was built, with when they changed. */
        val changed = ConcurrentHashMap<Path, Long>()

        /** The last merge of [base] with the files read from source, and what it was made from. */
        @Volatile var merged: Pair<Any, InkoSymbols>? = null
    }

    private val entries = ConcurrentHashMap<Path, Entry>()
    private val synchronous get() = ApplicationManager.getApplication().isUnitTestMode

    /** The definitions for the project at [root], scheduling a rebuild if they're missing or stale. */
    fun symbols(root: Path): InkoSymbols? {
        val entry = entries.computeIfAbsent(root) { Entry() }
        if (entry.stale) refresh(root, entry)
        val base = entry.base
        val sources = sourceOverrides(root, entry)
        if (base == null && sources.isEmpty()) return null

        val key = listOf(System.identityHashCode(base), sources.map { it.path to it.stamp })
        entry.merged?.takeIf { it.first == key }?.let { return it.second }
        val overridden = sources.map { it.path }.toSet()
        // Trait relations come from inko doc only, so keep the last known ones for definitions read from source.
        val related = base.orEmpty().filter { it.related.isNotEmpty() }.associate { (it.kind to it.module to it.name) to it.related }
        val fromSource = sources.flatMap { source ->
            val module = Inko.moduleName(source.path) ?: return@flatMap emptyList()
            InkoSourceDefinitions.of(source.path, module, source.text()).map { definition ->
                related[definition.kind to definition.module to definition.name]?.let { definition.copy(related = it) } ?: definition
            }
        }
        val symbols = InkoSymbols(base.orEmpty().filter { it.file !in overridden } + fromSource)
        entry.merged = key to symbols
        return symbols
    }

    private class SourceOverride(val path: Path, val stamp: Long, val text: () -> CharSequence)

    /** Unsaved Inko documents in the project, and files changed since the last `inko doc` run. */
    private fun sourceOverrides(root: Path, entry: Entry): List<SourceOverride> {
        // Application.runReadAction isn't deprecated in either supported version (ReadAction.compute is in 2026.2).
        val unsaved = ApplicationManager.getApplication().runReadAction<List<SourceOverride>> {
            FileDocumentManager.getInstance().unsavedDocuments.mapNotNull { document ->
                val path = Inko.pathOf(FileDocumentManager.getInstance().getFile(document))?.takeIf { it.startsWith(root) } ?: return@mapNotNull null
                // A snapshot, so it can be read later outside the read action.
                val text = document.immutableCharSequence
                SourceOverride(path, document.modificationStamp) { text }
            }
        }
        val unsavedPaths = unsaved.map { it.path }.toSet()
        val changed = entry.changed.entries
            .filter { (path, _) -> path !in unsavedPaths && Files.isRegularFile(path) }
            .map { (path, stamp) -> SourceOverride(path, stamp) { Files.readString(path) } }
        return (unsaved + changed).sortedBy { it.path }
    }

    fun invalidate(path: Path) {
        for ((root, entry) in entries) {
            if (!path.startsWith(root)) continue
            if (path.toString().endsWith(".${Inko.EXTENSION}")) entry.changed[path] = System.nanoTime()
            entry.stale = true
            if (!synchronous) refresh(root, entry)
        }
    }

    fun invalidateAll() {
        entries.values.forEach { it.stale = true }
    }

    private fun refresh(root: Path, entry: Entry) {
        if (!entry.running.compareAndSet(false, true)) return
        entry.stale = false
        val started = System.nanoTime()
        val rebuild = {
            try {
                build(root)?.let { definitions ->
                    entry.base = definitions
                    entry.changed.entries.removeIf { it.value < started }
                }
            } finally {
                entry.running.set(false)
            }
        }
        if (synchronous) rebuild() else scope.launch(Dispatchers.IO) { rebuild() }
    }

    private fun build(root: Path): List<InkoDefinition>? {
        val cli = InkoCli.getInstance(project)
        if (!cli.isAvailable) return null
        return try {
            document(root, cli, sources = true) ?: (document(root, cli, sources = false).orEmpty() + projectFromSource(root))
        } catch (e: Exception) {
            LOG.warn("Failed to index Inko definitions for $root", e)
            null
        }
    }

    /**
     * Runs `inko doc` in a mirror of [root]. Without [sources], the mirror's
     * only module is a stand-in importing every dependency module, so the
     * standard library and dependencies are documented even when the project
     * doesn't compile.
     */
    private fun document(root: Path, cli: InkoCli, sources: Boolean): List<InkoDefinition>? =
        InkoMirror.create(root, sources = sources).use { mirror ->
            if (!sources) Files.writeString(mirror.directory.resolve("src/$STAND_IN.${Inko.EXTENSION}"), dependencyImports(mirror.directory))
            val result = cli.run(listOf("doc", "--private", "--dependencies"), mirror.directory, timeoutMs = 60_000)
            if (result?.succeeded != true) return null
            val docs = mirror.directory.resolve("build").resolve("docs").takeIf(Files::isDirectory) ?: return null
            docs.listDirectoryEntries("*.json")
                .flatMap { InkoDocParser.parse(Files.readString(it), mirror::toReal) }
                .filter { it.module != STAND_IN }
        }

    /**
     * `import` lines for every module of the project's direct dependencies.
     * Dependency directories are named by a hash of their URL, so they're
     * matched by import name instead: a URL's last segment, minus any `inko-`
     * prefix (as the compiler does).
     */
    private fun dependencyImports(mirror: Path): String {
        val manifest = mirror.resolve(Inko.MANIFEST).takeIf(Files::exists)?.let(Files::readAllLines).orEmpty()
        val names = manifest.mapNotNull { Regex("""^\s*require\s+(\S+/\S+)\s""").find(it)?.groupValues?.get(1) }
            .map { it.trimEnd('/').substringAfterLast('/').removeSuffix(".git").removePrefix("inko-") }
            .toSet()
        val dep = mirror.resolve("dep").takeIf(Files::isDirectory) ?: return ""
        // dep is a symlink in the mirror, so walking it must follow links.
        val modules = Files.walk(dep, FileVisitOption.FOLLOW_LINKS).use { paths ->
            paths.filter { it.toString().endsWith(".${Inko.EXTENSION}") }.toList()
        }.mapNotNull { file ->
            val src = generateSequence(file.parent) { it.parent }.firstOrNull { it.fileName?.toString() == "src" } ?: return@mapNotNull null
            src.relativize(file).joinToString(".").removeSuffix(".${Inko.EXTENSION}").takeIf { it.substringBefore('.') in names }
        }
        return modules.toSortedSet().joinToString("") { "import $it\n" }
    }

    /** Every project source file, read from source. */
    private fun projectFromSource(root: Path): List<InkoDefinition> =
        Inko.SOURCE_DIRECTORIES.map(root::resolve).filter(Files::isDirectory).flatMap { dir ->
            Files.walk(dir).use { paths -> paths.filter { it.toString().endsWith(".${Inko.EXTENSION}") }.toList() }
        }.flatMap { file ->
            val module = Inko.moduleName(file) ?: return@flatMap emptyList()
            InkoSourceDefinitions.of(file, module, Files.readString(file))
        }

    companion object {
        private val LOG = logger<InkoDocIndex>()

        /** The stand-in module's name, unlikely to clash with a real one. */
        private const val STAND_IN = "parakeet_dependency_index"

        fun getInstance(project: Project): InkoDocIndex = project.service()
    }
}

/** Rebuilds the index when Inko files or manifests change, and re-checks banners when a manifest does. */
class InkoDocIndexFileListener(private val project: Project) : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        val index = InkoDocIndex.getInstance(project)
        val paths = events.map { it.path }
        val changed = paths.filter { it.endsWith(".${Inko.EXTENSION}") || it.endsWith("/${Inko.MANIFEST}") }
        changed.forEach { index.invalidate(Path.of(it)) }
        // Saved changes in the project can break (or fix) other modules: check the whole project again.
        val fileIndex = ProjectFileIndex.getInstance(project)
        events.filter { event -> event.file?.let { it.extension == Inko.EXTENSION && fileIndex.isInContent(it) } == true }
            .map { Inko.projectRoot(Path.of(it.path)) }.distinct()
            .forEach { InkoProjectCheck.getInstance(project).schedule(it) }
        if (paths.any { it.endsWith("/${Inko.MANIFEST}") }) EditorNotifications.getInstance(project).updateAllNotifications()
        // A new project, or dependencies synced or removed: libraries (and exclusions) may change.
        if (paths.any { it.endsWith("/${Inko.MANIFEST}") || it.endsWith("/dep") }) {
            ApplicationManager.getApplication().invokeLater({ InkoLibraries.getInstance(project).refresh() }, project.disposed)
        }
    }
}

/** Starts indexing a project as soon as one of its Inko files is opened. */
class InkoDocIndexWarmer : FileEditorManagerListener {
    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        val path = Inko.pathOf(file) ?: return
        InkoDocIndex.getInstance(source.project).symbols(Inko.projectRoot(path))
    }
}
