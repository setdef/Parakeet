package com.setdef.parakeet

import com.intellij.openapi.editor.Document
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.text.BreakIterator
import java.util.Locale
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
     *
     * Inko's lexer counts columns in extended grapheme clusters
     * (`graphemes(true)` in ast/src/lexer.rs), so `👩‍👩‍👦` or `é` written as
     * `e` and a combining accent is one column, however many UTF-16 units it
     * takes here. A tab is one column, as is any other character.
     */
    fun offsetOf(document: Document, line: Int, column: Int): Int {
        val lineIndex = (line - 1).coerceIn(0, maxOf(document.lineCount - 1, 0))
        val lineStart = document.getLineStartOffset(lineIndex)
        val lineEnd = document.getLineEndOffset(lineIndex)
        return lineStart + graphemeOffset(document.charsSequence.subSequence(lineStart, lineEnd), column - 1)
    }

    /** The UTF-16 offset in [text] after its first [graphemes] grapheme clusters (clamped to [text]). */
    fun graphemeOffset(text: CharSequence, graphemes: Int): Int {
        if (graphemes <= 0) return 0
        val clusters = BreakIterator.getCharacterInstance(Locale.ROOT)
        clusters.setText(text.toString())
        var offset = 0
        repeat(graphemes) {
            val next = clusters.next()
            if (next == BreakIterator.DONE) return text.length
            offset = next
        }
        return offset
    }

    /** The minimum Inko version a project's `inko.pkg` requires (`require inko X`), if any. */
    fun requiredVersion(root: Path): String? {
        awaitWritesToDisk()
        val manifest = root.resolve(MANIFEST).takeIf(Files::isRegularFile) ?: return null
        return Files.readAllLines(manifest).firstNotNullOfOrNull { REQUIRE_INKO.find(it)?.groupValues?.get(1) }
    }

    private val REQUIRE_INKO = Regex("""^\s*require\s+inko\s+(\S+)""")

    /**
     * Waits until what IntelliJ saved is on disk, for whatever reads it there next. IntelliJ 2026.2 returns from a
     * save before the disk has it (`vfs.async-content-write`): the document and the VFS's cached content change at
     * once, the VFS's event for the save comes first too, and a background task writes the file. 2025.3 writes before
     * it returns, and has no such method: hence reflection, which also keeps the Plugin Verifier from finding an
     * experimental API.
     */
    fun awaitWritesToDisk() {
        val flush = FLUSH ?: return
        try {
            flush.invoke(com.intellij.openapi.vfs.newvfs.ManagingFS.getInstance())
        } catch (e: java.lang.reflect.InvocationTargetException) {
            LOG.warn("couldn't wait for IntelliJ's writes to disk", e.targetException)
        }
    }

    private val FLUSH: java.lang.reflect.Method? =
        runCatching { com.intellij.openapi.vfs.newvfs.ManagingFS::class.java.getMethod("flushPendingUpdates") }.getOrNull()

    private val LOG = com.intellij.openapi.diagnostic.logger<Inko>()
}
