package com.setdef.parakeet

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.listDirectoryEntries

/**
 * Definitions for each Inko project, from `inko doc --private --dependencies`
 * run in a mirror of the project. Rebuilt in the background when the project's
 * Inko files change; lookups use the last good result. (In tests, rebuilds
 * happen synchronously.)
 */
@Service(Service.Level.PROJECT)
class InkoDocIndex(private val project: Project, private val scope: CoroutineScope) {
    private class Entry {
        @Volatile var symbols: InkoSymbols? = null
        @Volatile var stale = true
        val running = AtomicBoolean()
    }

    private val entries = ConcurrentHashMap<Path, Entry>()
    private val synchronous get() = ApplicationManager.getApplication().isUnitTestMode

    /** The definitions for the project at [root], scheduling a rebuild if they're missing or stale. */
    fun symbols(root: Path): InkoSymbols? {
        val entry = entries.computeIfAbsent(root) { Entry() }
        if (entry.stale) refresh(root, entry)
        return entry.symbols
    }

    fun invalidate(path: Path) {
        for ((root, entry) in entries) {
            if (!path.startsWith(root)) continue
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
        val rebuild = {
            try {
                build(root)?.let { entry.symbols = it }
            } finally {
                entry.running.set(false)
            }
        }
        if (synchronous) rebuild() else scope.launch(Dispatchers.IO) { rebuild() }
    }

    private fun build(root: Path): InkoSymbols? {
        val cli = InkoCli.getInstance(project)
        if (!cli.isAvailable) return null
        return try {
            InkoMirror.create(root).use { mirror ->
                val result = cli.run(listOf("doc", "--private", "--dependencies"), mirror.directory, timeoutMs = 60_000)
                if (result?.succeeded != true) return null
                val docs = mirror.directory.resolve("build").resolve("docs").takeIf(Files::isDirectory) ?: return null
                InkoSymbols(docs.listDirectoryEntries("*.json").flatMap { InkoDocParser.parse(Files.readString(it), mirror::toReal) })
            }
        } catch (e: Exception) {
            LOG.warn("Failed to index Inko definitions for $root", e)
            null
        }
    }

    companion object {
        private val LOG = logger<InkoDocIndex>()

        fun getInstance(project: Project): InkoDocIndex = project.service()
    }
}

/** Rebuilds the index when Inko files or manifests change. */
class InkoDocIndexFileListener(private val project: Project) : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        val index = InkoDocIndex.getInstance(project)
        events.map { it.path }
            .filter { it.endsWith(".${Inko.EXTENSION}") || it.endsWith("/${Inko.MANIFEST}") }
            .forEach { index.invalidate(Path.of(it)) }
    }
}

/** Starts indexing a project as soon as one of its Inko files is opened. */
class InkoDocIndexWarmer : FileEditorManagerListener {
    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        val path = Inko.pathOf(file) ?: return
        InkoDocIndex.getInstance(source.project).symbols(Inko.projectRoot(path))
    }
}
