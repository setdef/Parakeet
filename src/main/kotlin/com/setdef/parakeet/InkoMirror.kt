package com.setdef.parakeet

import com.intellij.openapi.util.io.NioFiles
import java.io.IOException
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
        private const val MANIFEST = "inko.pkg"
        private val SOURCE_DIRECTORIES = listOf("src", "test")

        /** Mirrors [root], replacing [file] (if given) with [text]. */
        fun create(root: Path, file: Path? = null, text: String? = null): InkoMirror {
            val mirror = InkoMirror(root, Files.createTempDirectory("parakeet").toRealPath())
            try {
                for (name in SOURCE_DIRECTORIES) {
                    val dir = root.resolve(name)
                    if (!Files.isDirectory(dir)) continue
                    Files.walk(dir).use { paths ->
                        paths.filter { it.isRegularFile() && it.extension == Inko.EXTENSION }.forEach { source ->
                            val link = mirror.toMirror(source)
                            Files.createDirectories(link.parent)
                            linkOrCopy(source, link)
                        }
                    }
                }
                // inko only treats test/ as part of a project when there's a src/ beside it.
                Files.createDirectories(mirror.directory.resolve("src"))
                root.resolve(MANIFEST).takeIf(Files::isRegularFile)?.let { linkOrCopy(it, mirror.directory.resolve(MANIFEST)) }
                root.resolve("dep").takeIf(Files::isDirectory)?.let { linkOrCopy(it, mirror.directory.resolve("dep")) }
                if (file != null && text != null) {
                    val copy = mirror.toMirror(file)
                    Files.createDirectories(copy.parent)
                    Files.deleteIfExists(copy)
                    Files.writeString(copy, text)
                }
                return mirror
            } catch (e: Exception) {
                mirror.close()
                throw e
            }
        }

        // Symlinks can require extra privileges on Windows, so fall back to copying.
        private fun linkOrCopy(source: Path, link: Path) {
            try {
                Files.createSymbolicLink(link, source)
            } catch (_: IOException) {
                copyRecursively(source, link)
            } catch (_: UnsupportedOperationException) {
                copyRecursively(source, link)
            }
        }

        private fun copyRecursively(source: Path, target: Path) {
            Files.walk(source).use { paths ->
                paths.forEach { Files.copy(it, target.resolve(source.relativize(it).toString())) }
            }
        }
    }
}
