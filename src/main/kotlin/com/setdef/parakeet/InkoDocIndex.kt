package com.setdef.parakeet

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
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
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.listDirectoryEntries

enum class InkoDefinitionKind { MODULE, TYPE, TRAIT, CONSTANT, MODULE_METHOD, STATIC_METHOD, INSTANCE_METHOD, CONSTRUCTOR, FIELD }

/** A definition reported by `inko doc`. [owner] is the type or trait it belongs to, if any. */
data class InkoDefinition(
    val kind: InkoDefinitionKind,
    val name: String,
    val module: String,
    val owner: String?,
    val file: Path,
    val line: Int,
    val column: Int,
)

class InkoSymbols(definitions: List<InkoDefinition>) {
    private val byName: Map<String, List<InkoDefinition>> = definitions.groupBy { it.name }
    private val modules: Map<String, InkoDefinition> =
        definitions.filter { it.kind == InkoDefinitionKind.MODULE }.associateBy { it.module }
    private val moduleByFile: Map<Path, String> = modules.values.associate { it.file to it.module }

    fun named(name: String): List<InkoDefinition> = byName[name].orEmpty()

    fun module(name: String): InkoDefinition? = modules[name]

    fun moduleOf(file: Path): String? = moduleByFile[file]
}

/**
 * Definitions for each Inko project, from `inko doc --private --dependencies`
 * run in a mirror of the project. Rebuilt in the background when the project's
 * Inko files change; lookups use the last good result.
 */
@Service(Service.Level.PROJECT)
class InkoDocIndex(private val project: Project, private val scope: CoroutineScope) {
    private class Entry {
        @Volatile var symbols: InkoSymbols? = null
        @Volatile var stale = true
        val running = AtomicBoolean()
    }

    private val entries = ConcurrentHashMap<Path, Entry>()

    /** The definitions for the project at [root], scheduling a rebuild if they're missing or stale. */
    fun symbols(root: Path): InkoSymbols? {
        val entry = entries.computeIfAbsent(root) { Entry() }
        if (entry.stale) {
            if (ApplicationManager.getApplication().isUnitTestMode) {
                entry.stale = false
                build(root)?.let { entry.symbols = it }
            } else {
                refresh(root, entry)
            }
        }
        return entry.symbols
    }

    fun invalidate(path: Path) {
        for ((root, entry) in entries) {
            if (path.startsWith(root)) {
                entry.stale = true
                if (!ApplicationManager.getApplication().isUnitTestMode) refresh(root, entry)
            }
        }
    }

    fun invalidateAll() {
        entries.values.forEach { it.stale = true }
    }

    private fun refresh(root: Path, entry: Entry) {
        if (!entry.running.compareAndSet(false, true)) return
        entry.stale = false
        scope.launch(Dispatchers.IO) {
            try {
                build(root)?.let { entry.symbols = it }
            } finally {
                entry.running.set(false)
            }
        }
    }

    private fun build(root: Path): InkoSymbols? {
        val inko = InkoExecutable.find(project) ?: return null
        return try {
            InkoMirror.create(root).use { mirror ->
                val command = GeneralCommandLine(inko.toString(), "doc", "--private", "--dependencies")
                    .withWorkDirectory(mirror.directory.toFile())
                    .withCharset(StandardCharsets.UTF_8)
                val output = CapturingProcessHandler(command).runProcess(TIMEOUT_MS)
                if (output.exitCode != 0) return null
                val docs = mirror.directory.resolve("build").resolve("docs")
                if (!Files.isDirectory(docs)) return null
                InkoSymbols(docs.listDirectoryEntries("*.json").flatMap { parseModule(it, mirror) })
            }
        } catch (e: Exception) {
            LOG.warn("Failed to index Inko definitions for $root", e)
            null
        }
    }

    private fun parseModule(json: Path, mirror: InkoMirror): List<InkoDefinition> {
        val root = JsonParser.parseString(Files.readString(json)).takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()
        val module = root.string("name") ?: return emptyList()
        val moduleFile = mirror.toReal(Path.of(root.string("file") ?: return emptyList()))
        val result = mutableListOf(InkoDefinition(InkoDefinitionKind.MODULE, module, module, null, moduleFile, 1, 1))

        fun add(kind: InkoDefinitionKind, obj: JsonObject, owner: String?) {
            val name = obj.string("name") ?: return
            val location = obj.getAsJsonObject("location") ?: return
            val file = obj.string("file")?.let { mirror.toReal(Path.of(it)) } ?: moduleFile
            val line = location.getAsJsonObject("lines")?.get("start")?.asInt ?: 1
            val column = location.getAsJsonObject("columns")?.get("start")?.asInt ?: 1
            result += InkoDefinition(kind, name, module, owner, file, line, column)
        }

        root.objects("constants").forEach { add(InkoDefinitionKind.CONSTANT, it, null) }
        root.objects("methods").forEach { add(InkoDefinitionKind.MODULE_METHOD, it, null) }
        for (type in root.objects("types")) {
            add(InkoDefinitionKind.TYPE, type, null)
            val owner = type.string("name")
            type.objects("constructors").forEach { add(InkoDefinitionKind.CONSTRUCTOR, it, owner) }
            type.objects("fields").forEach { add(InkoDefinitionKind.FIELD, it, owner) }
            type.objects("static_methods").forEach { add(InkoDefinitionKind.STATIC_METHOD, it, owner) }
            type.objects("instance_methods").forEach { add(InkoDefinitionKind.INSTANCE_METHOD, it, owner) }
        }
        for (trait in root.objects("traits")) {
            add(InkoDefinitionKind.TRAIT, trait, null)
            val owner = trait.string("name")
            trait.objects("required_methods").forEach { add(InkoDefinitionKind.INSTANCE_METHOD, it, owner) }
            trait.objects("default_methods").forEach { add(InkoDefinitionKind.INSTANCE_METHOD, it, owner) }
        }
        return result
    }

    private fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.objects(key: String): List<JsonObject> =
        (get(key) as? JsonArray)?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }.orEmpty()

    companion object {
        private const val TIMEOUT_MS = 60_000
        private val LOG = logger<InkoDocIndex>()

        fun getInstance(project: Project): InkoDocIndex = project.service()
    }
}

/** Rebuilds the index when Inko files or manifests change. */
class InkoDocIndexFileListener(private val project: Project) : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        val index = InkoDocIndex.getInstance(project)
        for (event in events) {
            val path = event.path.takeIf { it.endsWith(".${Inko.EXTENSION}") || it.endsWith("/inko.pkg") } ?: continue
            index.invalidate(Path.of(path))
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
