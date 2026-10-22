package com.setdef.parakeet

import com.intellij.openapi.util.io.NioFiles
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/**
 * A temporary copy of an Inko project made of symlinks to its source files,
 * manifest, and `dep` directory, so `inko` can run against it without writing
 * into the real project, optionally with one file's unsaved text swapped in.
 */
class InkoMirror private constructor(val root: Path, val directory: Path) : AutoCloseable {
    /** Maps a path inside the mirror back to the real project (other paths are returned as-is). */
    fun toReal(path: Path): Path = if (path.startsWith(directory)) root.resolve(directory.relativize(path)) else path

    fun toMirror(path: Path): Path = directory.resolve(root.relativize(path))

    override fun close() {
        NioFiles.deleteRecursively(directory)
    }

    companion object {
        /** Mirrors [root], replacing [file] (if given) with [text]. */
        fun create(root: Path, file: Path? = null, text: String? = null): InkoMirror {
            val mirror = InkoMirror(root, Files.createTempDirectory("parakeet").toRealPath())
            try {
                for (dir in Inko.SOURCE_DIRECTORIES.map(root::resolve).filter(Files::isDirectory)) {
                    Files.walk(dir).use { paths ->
                        paths.filter { it.isRegularFile() && it.extension == Inko.EXTENSION }.forEach(mirror::link)
                    }
                }
                // inko only treats test/ as part of a project when there's a src/ beside it.
                Files.createDirectories(mirror.directory.resolve("src"))
                root.resolve(Inko.MANIFEST).takeIf(Files::isRegularFile)?.let(mirror::link)
                root.resolve("dep").takeIf(Files::isDirectory)?.let(mirror::link)
                if (file != null && text != null) mirror.replace(file, text)
                return mirror
            } catch (e: Exception) {
                mirror.close()
                throw e
            }
        }
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
