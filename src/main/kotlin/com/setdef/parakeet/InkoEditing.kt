package com.setdef.parakeet

import com.intellij.codeInsight.editorActions.QuoteHandler
import com.intellij.lang.BracePair
import com.intellij.lang.Commenter
import com.intellij.lang.PairedBraceMatcher
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType

class InkoCommenter : Commenter {
    override fun getLineCommentPrefix() = "# "
    override fun getBlockCommentPrefix(): String? = null
    override fun getBlockCommentSuffix(): String? = null
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}

class InkoBraceMatcher : PairedBraceMatcher {
    override fun getPairs() = arrayOf(
        BracePair(InkoTokens.LBRACE, InkoTokens.RBRACE, true),
        BracePair(InkoTokens.LPAREN, InkoTokens.RPAREN, false),
        BracePair(InkoTokens.LBRACKET, InkoTokens.RBRACKET, false),
    )

    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?) = true
    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int) = openingBraceOffset
}

/**
 * Auto-closes and steps over quotes. The editor's tokens come from the TextMate
 * grammar, so this lexes the document with [InkoLexer] to find strings.
 */
class InkoQuoteHandler : QuoteHandler {
    /**
     * The whole string literal whose text (not interpolated code) holds
     * [offset]: from its opening quote through its closing one (or the end of
     * the file, when it isn't closed).
     */
    private fun stringAt(iterator: HighlighterIterator, offset: Int): TextRange? {
        val stream = InkoTokenStream(iterator.document.charsSequence)
        val tokens = stream.tokens
        val at = tokens.indexOfFirst { it.start <= offset && offset < it.end }.takeIf { it >= 0 && tokens[it].isString } ?: return null
        val first = if (tokens[at].type == InkoTokens.STRING || tokens[at].type == InkoTokens.STRING_START) at else stream.match(at)
        if (first < 0) return null
        val last = if (tokens[first].type == InkoTokens.STRING) first else stream.match(first)
        return TextRange(tokens[first].start, if (last < 0) stream.text.length else tokens[last].end)
    }

    private fun isClosed(text: CharSequence, range: TextRange): Boolean =
        range.length >= 2 && text[range.endOffset - 1] == text[range.startOffset] && text[range.endOffset - 2] != '\\'

override fun isClosingQuote(iterator: HighlighterIterator, offset: Int): Boolean {
        val range = stringAt(iterator, offset) ?: return false
        return range.length >= 2 && offset == range.endOffset - 1
    }

    override fun isOpeningQuote(iterator: HighlighterIterator, offset: Int): Boolean =
        stringAt(iterator, offset)?.startOffset == offset

    override fun hasNonClosedLiteral(editor: Editor, iterator: HighlighterIterator, offset: Int): Boolean {
        val range = stringAt(iterator, offset) ?: return false
        val text = editor.document.charsSequence
        return !isClosed(text, range) || text.subSequence(range.startOffset, range.endOffset).contains('\n')
    }

    override fun isInsideLiteral(iterator: HighlighterIterator): Boolean = stringAt(iterator, iterator.start) != null
}
