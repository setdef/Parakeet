package com.setdef.parakeet

import com.intellij.codeInsight.editorActions.QuoteHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.util.TextRange
import com.intellij.lang.BracePair
import com.intellij.lang.Commenter
import com.intellij.lang.PairedBraceMatcher
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Colors
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import org.jetbrains.plugins.textmate.TextMateService
import org.jetbrains.plugins.textmate.language.syntax.highlighting.TextMateSyntaxHighlighterFactory

/** Highlights with the Inko TextMate grammar, falling back to [InkoLexer] tokens. */
class InkoSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter {
        if (virtualFile == null || TextMateService.getInstance().getLanguageDescriptorByFileName(virtualFile.name) == null) {
            return InkoFallbackHighlighter()
        }
        return TextMateSyntaxHighlighterFactory().getSyntaxHighlighter(project, virtualFile)
    }
}

class InkoFallbackHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer() = InkoLexer()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = pack(
        when (tokenType) {
            InkoTokens.KEYWORD -> Colors.KEYWORD
            InkoTokens.STRING -> Colors.STRING
            InkoTokens.NUMBER -> Colors.NUMBER
            InkoTokens.COMMENT -> Colors.LINE_COMMENT
            InkoTokens.CONSTANT -> Colors.CLASS_NAME
            InkoTokens.FIELD -> Colors.INSTANCE_FIELD
            else -> null
        },
    )
}

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
    private fun stringAt(iterator: HighlighterIterator, offset: Int): TextRange? {
        val text = iterator.document.charsSequence
        val lexer = InkoLexer()
        lexer.start(text, 0, text.length, 0)
        while (lexer.tokenType != null && lexer.tokenEnd <= offset) lexer.advance()
        if (lexer.tokenType != InkoTokens.STRING || lexer.tokenStart > offset) return null
        return TextRange(lexer.tokenStart, lexer.tokenEnd)
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
