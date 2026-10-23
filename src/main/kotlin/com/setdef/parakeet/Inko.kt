package com.setdef.parakeet

import com.intellij.openapi.editor.Document
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path

object Inko {
    const val EXTENSION = "inko"
    const val MANIFEST = "inko.pkg"

    /** Shown in Settings | Appearance & Behavior | Notifications. */
    const val NOTIFICATION_GROUP = "Parakeet for Inko"
    val SOURCE_DIRECTORIES = listOf("src", "test")

    fun isInkoFile(file: VirtualFile?): Boolean = pathOf(file) != null

    /** The on-disk path of an Inko [file], or null for other files and ones not on disk. */
    fun pathOf(file: VirtualFile?): Path? =
        file?.takeIf { it.extension == EXTENSION }?.let { it.fileSystem.getNioPath(it) }

    /** The module name the compiler gives [file], e.g. `app.util` for `src/app/util.inko`. */
    fun moduleName(file: Path): String? {
        val root = projectRoot(file)
        val base = SOURCE_DIRECTORIES.map(root::resolve).firstOrNull(file::startsWith) ?: return null
        return base.relativize(file).joinToString(".").removeSuffix(".$EXTENSION")
    }

    /**
     * The directory `inko` should run in for [file], mirroring how the compiler
     * finds project roots: the parent of the nearest `src` directory, or of the
     * nearest `test` directory that has a sibling `src`, or the file's own
     * directory for standalone scripts.
     */
    fun projectRoot(file: Path): Path {
        var dir = file.parent
        while (dir != null) {
            val parent = dir.parent ?: break
            when (dir.fileName?.toString()) {
                "src" -> return parent
                "test" -> if (Files.isDirectory(parent.resolve("src"))) return parent
            }
            dir = parent
        }
        return file.parent
    }

    /**
     * The offset of a compiler location (1-based [line] and [column]) in
     * [document], clamped to that line so stale locations stay in range.
     */
    fun offsetOf(document: Document, line: Int, column: Int): Int {
        val lineIndex = (line - 1).coerceIn(0, maxOf(document.lineCount - 1, 0))
        val lineStart = document.getLineStartOffset(lineIndex)
        val lineEnd = document.getLineEndOffset(lineIndex)
        return (lineStart + column - 1).coerceIn(lineStart, lineEnd)
    }

    /** The minimum Inko version a project's `inko.pkg` requires (`require inko X`), if any. */
    fun requiredVersion(root: Path): String? {
        val manifest = root.resolve(MANIFEST).takeIf(Files::isRegularFile) ?: return null
        return Files.readAllLines(manifest).firstNotNullOfOrNull { REQUIRE_INKO.find(it)?.groupValues?.get(1) }
    }

    private val REQUIRE_INKO = Regex("""^\s*require\s+inko\s+(\S+)""")
}
