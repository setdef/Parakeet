package com.setdef.parakeet

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
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
            val lineNumber = match.groupValues[1].toInt() - 1
            val column = match.groupValues[2].toIntOrNull()?.minus(1) ?: 0
            Filter.ResultItem(lineStart + start, lineStart + match.range.last + 1, OpenFileHyperlinkInfo(project, virtualFile, lineNumber, column))
        }.toList()
        return if (items.isEmpty()) null else Filter.Result(items)
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
