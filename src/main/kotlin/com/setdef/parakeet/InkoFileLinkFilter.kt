package com.setdef.parakeet

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * Links `path/to/file.inko:LINE[:COLUMN]` in console output (compiler errors
 * and panic backtraces) to the file. Paths may contain spaces, so the longest
 * existing path ending at `.inko` wins.
 */
class InkoFileLinkFilter(private val project: Project, private val workingDirectory: Path?) : Filter {
    private val location = Regex("""\.inko:(\d+)(?::(\d+))?""")

    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val lineStart = entireLength - line.length
        val items = location.findAll(line).mapNotNull { match ->
            val pathEnd = match.range.first + ".inko".length
            val start = (0 until match.range.first)
                .filter { !line[it].isWhitespace() && (it == 0 || line[it - 1].isWhitespace() || line[it - 1] in "'\"(") }
                .firstOrNull { resolve(line.substring(it, pathEnd)) != null } ?: return@mapNotNull null
            val file = resolve(line.substring(start, pathEnd)) ?: return@mapNotNull null
            val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file) ?: return@mapNotNull null
            val lineNumber = match.groupValues[1].toInt()
            val column = match.groupValues[2].toIntOrNull() ?: 1
            Filter.ResultItem(lineStart + start, lineStart + match.range.last + 1, link(virtualFile, lineNumber, column))
        }.toList()
        return if (items.isEmpty()) null else Filter.Result(items)
    }

    /**
     * A link to a compiler location. Its column counts grapheme clusters (see
     * [Inko.offsetOf]), so it's converted to the line's UTF-16 column here.
     */
    private fun link(file: VirtualFile, line: Int, column: Int): OpenFileHyperlinkInfo {
        val document = ApplicationManager.getApplication().runReadAction<Document?> { FileDocumentManager.getInstance().getDocument(file) }
            ?: return OpenFileHyperlinkInfo(project, file, line - 1, column - 1)
        val lineIndex = (line - 1).coerceIn(0, maxOf(document.lineCount - 1, 0))
        val offset = ApplicationManager.getApplication().runReadAction<Int> { Inko.offsetOf(document, line, column) }
        return OpenFileHyperlinkInfo(project, file, lineIndex, offset - document.getLineStartOffset(lineIndex))
    }

    private fun resolve(text: String): Path? {
        val path = try {
            Path.of(text)
        } catch (_: Exception) {
            return null
        }
        val absolute = if (path.isAbsolute) path else workingDirectory?.resolve(path) ?: return null
        return absolute.takeIf { Files.isRegularFile(it) }
    }
}
