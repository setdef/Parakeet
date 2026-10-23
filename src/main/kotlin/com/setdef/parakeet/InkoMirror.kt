package com.setdef.parakeet

import com.intellij.openapi.util.io.NioFiles
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries

/**
 * A copy of an Inko project made of symlinks to its source files, manifest, and
 * `dep` directory, so `inko` can run against it without writing into the real
 * project, optionally with one file's unsaved text swapped in.
 *
 * A temporary mirror is deleted when closed. A persistent one (for test runs)
 * lives in the IDE's system directory and keeps its `build` directory between
 * uses, so compiles stay incremental; only its links are refreshed.
 */
class InkoMirror private constructor(val root: Path, val directory: Path, private val temporary: Boolean) : AutoCloseable {
    /** Maps a path inside the mirror back to the real project (other paths are returned as-is). */
    fun toReal(path: Path): Path = if (path.startsWith(directory)) root.resolve(directory.relativize(path)) else path

    fun toMirror(path: Path): Path = directory.resolve(root.relativize(path))

    override fun close() {
        if (temporary) NioFiles.deleteRecursively(directory)
    }

    companion object {
        /** What a mirror links to; refreshing a persistent mirror replaces exactly these. */
        private val LINKED = Inko.SOURCE_DIRECTORIES + Inko.MANIFEST + "dep"

        /**
         * A temporary mirror of [root], replacing [file] (if given) with [text].
         * Without [sources], only the manifest and dependencies are linked.
         */
        fun create(root: Path, file: Path? = null, text: String? = null, sources: Boolean = true): InkoMirror {
            val mirror = InkoMirror(root, Files.createTempDirectory("parakeet").toRealPath(), temporary = true)
            try {
                mirror.populate(file, text, sources)
                return mirror
            } catch (e: Exception) {
                mirror.close()
                throw e
            }
        }

        /** A mirror of [root] in [directory], with its links refreshed and anything else (e.g. `build`) kept. */
        fun persistent(root: Path, directory: Path): InkoMirror {
            Files.createDirectories(directory)
            val mirror = InkoMirror(root, directory.toRealPath(), temporary = false)
            LINKED.map(mirror.directory::resolve).forEach(::deleteWithoutFollowingLinks)
            mirror.populate(null, null, sources = true)
            return mirror
        }

        /** Deletes [path] and, for a real directory, its contents; symlinks are removed, never followed. */
        internal fun deleteWithoutFollowingLinks(path: Path) {
            if (Files.isSymbolicLink(path)) {
                Files.delete(path)
            } else if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                path.listDirectoryEntries().forEach(::deleteWithoutFollowingLinks)
                Files.delete(path)
            } else {
                Files.deleteIfExists(path)
            }
        }
    }

    private fun populate(file: Path?, text: String?, sources: Boolean) {
        for (dir in Inko.SOURCE_DIRECTORIES.map(root::resolve).filter(Files::isDirectory).takeIf { sources }.orEmpty()) {
            Files.walk(dir).use { paths ->
                paths.filter { it.isRegularFile() && it.extension == Inko.EXTENSION }.forEach(::link)
            }
        }
        // inko only treats test/ as part of a project when there's a src/ beside it.
        Files.createDirectories(directory.resolve("src"))
        val manifest = root.resolve(Inko.MANIFEST).takeIf(Files::isRegularFile)?.also(::link)
        if (manifest != null) dependencies(manifest)
        if (file != null && text != null) replace(file, text)
    }

    /**
     * The project's installed dependencies, as they are. `inko` syncs
     * (running `git`, maybe over the network) whenever `dep/hash` isn't the
     * hash of `inko.pkg`, so the mirror gets its own `dep` directory: links to
     * each package the project has, and a `hash` that matches the manifest.
     * Packages that were never installed are simply missing; the editor banner
     * says why (see [InkoDependencySync]).
     */
    private fun dependencies(manifest: Path) {
        val dep = Files.createDirectories(directory.resolve("dep"))
        root.resolve("dep").takeIf(Files::isDirectory)?.listDirectoryEntries()
            ?.filter { it.fileName.toString() != "hash" }
            ?.forEach { Files.createSymbolicLink(dep.resolve(it.fileName.toString()), it) }
        Files.writeString(dep.resolve("hash"), InkoBlake3.hex(Files.readAllBytes(manifest)))
    }

    private fun link(source: Path) {
        val link = toMirror(source)
        Files.createDirectories(link.parent)
        Files.createSymbolicLink(link, source)
    }

    private fun replace(file: Path, text: String) {
        val copy = toMirror(file)
        Files.createDirectories(copy.parent)
        Files.deleteIfExists(copy)
        Files.writeString(copy, text)
    }
}
