package com.setdef.parakeet

import com.intellij.codeInsight.editorActions.ExtendWordSelectionHandlerBase
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement

/**
 * Extend Selection (⌥↑ / Ctrl+W) in Inko. The PSI is flat, so the steps come
 * from tokens: a string's contents, an argument, a bracket's contents and the
 * brackets with them, the statement, and enclosing declarations. The IDE picks
 * the smallest of these that's bigger than the current selection.
 */
class InkoExtendSelectionHandler : ExtendWordSelectionHandlerBase() {
    override fun canSelect(e: PsiElement): Boolean = e.containingFile is InkoFile && e.firstChild == null

    override fun select(e: PsiElement, editorText: CharSequence, cursorOffset: Int, editor: Editor): List<TextRange> =
        InkoSelections.around(editorText, cursorOffset, e.containingFile.inkoOutline)
}

object InkoSelections {
    fun around(text: CharSequence, offset: Int, outline: InkoOutline): List<TextRange> {
        val stream = InkoTokenStream(text)
        val tokens = stream.tokens
        val ranges = mutableListOf<TextRange>()

        // A string's contents, then the string.
        tokens.firstOrNull { it.type == InkoTokens.STRING && it.start < offset && offset < it.end }?.let { string ->
            if (string.text.length >= 2) ranges += TextRange(string.start + 1, string.end - 1)
            ranges += string.range
        }

        for ((open, close) in enclosingBrackets(stream, offset)) {
            // An interpolated string: the code at the offset, then the string's contents, then the string.
            if (tokens[open].type == InkoTokens.STRING_START) {
                val parts = listOf(open) + (open + 1 until close).filter { tokens[it].type == InkoTokens.STRING_MIDDLE && stream.match(it) == open } + close
                parts.zipWithNext().firstOrNull { (a, b) -> tokens[a].end <= offset && offset <= tokens[b].start }
                    ?.let { (a, b) -> trimmed(text, TextRange(tokens[a].end, tokens[b].start))?.let { ranges += it } }
                ranges += TextRange(tokens[open].start + 1, tokens[close].end - 1)
                ranges += TextRange(tokens[open].start, tokens[close].end)
                continue
            }
            val inner = TextRange(tokens[open].end, tokens[close].start)
            // The argument (or array element) the offset is in, for `(` and `[`.
            if (tokens[open].type != InkoTokens.LBRACE) argumentAt(stream, open, close, offset)?.let { ranges += it }
            trimmed(text, inner)?.let { ranges += it }
            ranges += TextRange(tokens[open].start, tokens[close].end)
            // A block's statement: `if x { … }`, `fn name { … }`.
            if (tokens[open].type == InkoTokens.LBRACE) statementAround(stream, open)?.let { ranges += it }
        }

        // The statement at the offset, in its innermost block.
        val start = stream.statementStart(stream.enclosingBlock(offset), offset)
        if (start < tokens.size) ranges += TextRange(tokens[start].start, tokens[stream.statementEnd(start)].end)

        // Enclosing declarations, with their doc comments.
        for (node in outline.enclosing(offset)) {
            ranges += node.range
            withComments(text, node.range)?.let { ranges += it }
        }
        return ranges.filter { !it.isEmpty && it.containsOffset(offset) }.distinct()
    }

    // Each bracket pair around [offset], innermost first, as (open, close) token indices.
    private fun enclosingBrackets(stream: InkoTokenStream, offset: Int): List<Pair<Int, Int>> =
        stream.tokens.indices
            .filter { stream.tokens[it].opens }
            .mapNotNull { open -> stream.match(open).takeIf { it > open }?.let { open to it } }
            .filter { (open, close) -> stream.tokens[open].end <= offset && offset <= stream.tokens[close].start }
            .sortedByDescending { it.first }

    // The top-level, comma-separated item between [open] and [close] that holds [offset].
    private fun argumentAt(stream: InkoTokenStream, open: Int, close: Int, offset: Int): TextRange? {
        val tokens = stream.tokens
        var start = open + 1
        var i = open + 1
        while (i <= close) {
            if (i == close || tokens[i].type == InkoTokens.COMMA) {
                if (start < i && tokens[start].start <= offset && offset <= tokens[i - 1].end) {
                    return TextRange(tokens[start].start, tokens[i - 1].end)
                }
                start = i + 1
                i++
                continue
            }
            i = if (tokens[i].opens && stream.match(i) > i) stream.match(i) + 1 else i + 1
        }
        return null
    }

    // The statement a block belongs to, from its first token through the block's `}`.
    private fun statementAround(stream: InkoTokenStream, open: Int): TextRange? {
        val close = stream.match(open).takeIf { it > open } ?: return null
        val outer = stream.enclosingBlock(stream.tokens[open].start)
        val start = stream.statementStart(outer, stream.tokens[open].start)
        if (start >= open) return null
        return TextRange(stream.tokens[start].start, stream.tokens[close].end)
    }

    private fun trimmed(text: CharSequence, range: TextRange): TextRange? {
        var start = range.startOffset
        var end = range.endOffset
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        return if (start < end) TextRange(start, end) else null
    }

    // A declaration's range extended up over the `#` comment lines right above it.
    private fun withComments(text: CharSequence, range: TextRange): TextRange? {
        var start = text.lastIndexOf('\n', range.startOffset - 1) + 1
        val original = start
        while (start > 0) {
            val previous = text.lastIndexOf('\n', start - 2) + 1
            if (!text.subSequence(previous, start).trimStart().startsWith("#")) break
            start = previous
        }
        if (start == original) return null
        return TextRange(start + text.subSequence(start, range.startOffset).takeWhile { it == ' ' }.length, range.endOffset)
    }
}
