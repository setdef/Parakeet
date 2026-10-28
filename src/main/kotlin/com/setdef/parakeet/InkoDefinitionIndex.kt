package com.setdef.parakeet

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.EditorNotifications
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The definitions each Inko project can see, read from the code ([InkoSourceDefinitions]): its own `src/` and
 * `test/`, its dependencies in `dep/`, and the standard library, unsaved text in place of a file's. Each file is
 * read again only when it changes.
 */
@Service(Service.Level.PROJECT)
class InkoDefinitionIndex(private val project: Project) {
    private class Read(val stamp: Long, val definitions: List<InkoDefinition>)

    private val files = ConcurrentHashMap<Path, Read>()
    private val merged = ConcurrentHashMap<Path, Pair<List<Any>, InkoSymbols>>()
    private val generation = AtomicLong()
    /** The standard library, as the `inko` it was asked of names it. */
    @Volatile private var std: Pair<InkoCli, Path?>? = null

    /** The definitions for the project at [root]. */
    fun symbols(root: Path): InkoSymbols? {
        // What changed since: a file (VFS events), or unsaved text.
        val unsaved = unsaved()
        val key = listOf(generation.get(), unsaved.mapValues { it.value.modificationStamp }, System.identityHashCode(InkoCli.getInstance(project)))
        merged[root]?.takeIf { it.first == key }?.let { return it.second }
        val sources = sources(root).ifEmpty { return null }
        val definitions = sources.flatMap { (base, paths) -> paths.mapNotNull { read(base, it, unsaved[it]) } }.flatMap { it.definitions }
        return InkoSymbols(definitions).also { merged[root] = key to it }
    }

    /** Each directory modules are named from, with its Inko files: the project's, its dependencies', and std's. */
    private fun sources(root: Path): List<Pair<Path, List<Path>>> {
        val project = Inko.SOURCE_DIRECTORIES.map(root::resolve).filter(Files::isDirectory)
        if (project.isEmpty()) return emptyList()
        val dependencies = root.resolve("dep").takeIf(Files::isDirectory)?.let { dep ->
            Files.walk(dep, 3).use { paths -> paths.filter { it.fileName?.toString() == "src" && Files.isDirectory(it) }.toList() }
        }.orEmpty()
        return (project + dependencies + listOfNotNull(standardLibrary())).map { base ->
            // A project's test/ is named from test/, as its src/ is from src/.
            base to Files.walk(base).use { paths -> paths.filter { it.toString().endsWith(".${Inko.EXTENSION}") && Files.isRegularFile(it) }.toList() }
        }
    }

    private fun standardLibrary(): Path? {
        val cli = InkoCli.getInstance(project)
        std?.takeIf { it.first === cli }?.let { return it.second }
        val found = try {
            if (cli.isAvailable) InkoTestRuns.standardLibrary(cli) else null
        } catch (e: Exception) {
            if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
            LOG.warn("couldn't find the standard library", e)
            null
        }
        return found.also { std = cli to it }
    }

    /** The project's unsaved Inko documents, by path. */
    private fun unsaved(): Map<Path, com.intellij.openapi.editor.Document> =
        // Application.runReadAction isn't deprecated in either supported version (ReadAction.compute is in 2026.2).
        ApplicationManager.getApplication().runReadAction<Map<Path, com.intellij.openapi.editor.Document>> {
            FileDocumentManager.getInstance().unsavedDocuments.mapNotNull { document ->
                Inko.pathOf(FileDocumentManager.getInstance().getFile(document))?.let { it to document }
            }.toMap()
        }

    /** [path]'s definitions, as of its unsaved text if it has some. */
    private fun read(base: Path, path: Path, unsaved: com.intellij.openapi.editor.Document?): Read? {
        val stamp = unsaved?.let { -it.modificationStamp } ?: runCatching { Files.getLastModifiedTime(path).toMillis() * 31 + Files.size(path) }.getOrNull() ?: return null
        files[path]?.takeIf { it.stamp == stamp }?.let { return it }
        val text = unsaved?.immutableCharSequence ?: runCatching { Files.readString(path) }.getOrNull() ?: return null
        val module = base.relativize(path).joinToString(".").removeSuffix(".${Inko.EXTENSION}")
        return Read(stamp, InkoSourceDefinitions.of(path, module, text)).also { files[path] = it }
    }

    fun invalidate(path: Path) {
        files.remove(path)
        generation.incrementAndGet()
    }

    /** The toolchain changed: the standard library may be another one. */
    fun invalidateAll() {
        std = null
        files.clear()
        generation.incrementAndGet()
    }

    companion object {
        private val LOG = com.intellij.openapi.diagnostic.logger<InkoDefinitionIndex>()

        fun getInstance(project: Project): InkoDefinitionIndex = project.service()
    }
}

/** Reads the index again when Inko files or manifests change, and re-checks banners when a manifest does. */
class InkoDefinitionIndexFileListener(private val project: Project) : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        val index = InkoDefinitionIndex.getInstance(project)
        val paths = events.map { it.path }
        paths.filter { it.endsWith(".${Inko.EXTENSION}") || it.endsWith("/${Inko.MANIFEST}") || it.endsWith("/dep") }.forEach { index.invalidate(Path.of(it)) }
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

/** Reads a project's definitions as soon as one of its Inko files is opened. */
class InkoDefinitionIndexWarmer : FileEditorManagerListener {
    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        val path = Inko.pathOf(file) ?: return
        ApplicationManager.getApplication().executeOnPooledThread { InkoDefinitionIndex.getInstance(source.project).symbols(Inko.projectRoot(path)) }
    }
}
