package com.setdef.parakeet

import com.intellij.lexer.Lexer
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.cache.impl.BaseFilterLexer
import com.intellij.psi.impl.cache.impl.OccurrenceConsumer
import com.intellij.psi.impl.cache.impl.todo.LexerBasedTodoIndexer
import com.intellij.psi.impl.search.IndexPatternBuilder
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet

/** Finds TODO/FIXME patterns in Inko comments, for highlighting and the TODO tool window. */
class InkoIndexPatternBuilder : IndexPatternBuilder {
    override fun getIndexingLexer(file: PsiFile): Lexer? = if (file is InkoFile) InkoLexer() else null

    override fun getCommentTokenSet(file: PsiFile): TokenSet? = if (file is InkoFile) InkoTokens.COMMENTS else null

    // Skip the `#`.
    override fun getCommentStartDelta(tokenType: IElementType?): Int = if (tokenType == InkoTokens.COMMENT) 1 else 0

    override fun getCommentEndDelta(tokenType: IElementType?): Int = 0
}

/** Counts TODOs in Inko comments for the TODO index. */
class InkoTodoIndexer : LexerBasedTodoIndexer() {
    override fun createLexer(consumer: OccurrenceConsumer): Lexer = object : BaseFilterLexer(InkoLexer(), consumer) {
        override fun advance() {
            if (myDelegate.tokenType == InkoTokens.COMMENT) advanceTodoItemCountsInToken()
            myDelegate.advance()
        }
    }
}
