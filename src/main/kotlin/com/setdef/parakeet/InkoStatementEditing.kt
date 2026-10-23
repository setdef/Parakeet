package com.setdef.parakeet

import com.intellij.codeInsight.editorActions.moveUpDown.LineRange
import com.intellij.codeInsight.editorActions.moveUpDown.StatementUpDownMover
import com.intellij.codeInsight.editorActions.smartEnter.SmartEnterProcessor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

/**
 * Move Statement Up/Down (⇧⌘↑/↓ / Ctrl+Shift+↑/↓): swaps a whole statement
 * (with its block, and the comment lines right above it) with its neighbor in
 * the same block, stopping at the block's edges.
 */
class InkoStatementMover : StatementUpDownMover() {
    override fun checkAvailable(editor: Editor, file: PsiFile, info: MoveInfo, down: Boolean): Boolean {
        if (file !is InkoFile) return false
        val document = editor.document
        val stream = InkoTokenStream(document.immutableCharSequence)
        val caret = document.getLineStartOffset(document.getLineNumber(editor.caretModel.offset))
        val lineEnd = document.getLineEndOffset(document.getLineNumber(editor.caretModel.offset))
        // The statement on the caret's line, in the innermost block containing it.
        val token = stream.tokens.indexOfFirst { it.start in caret..lineEnd }.takeIf { it >= 0 } ?: return false
        val block = stream.enclosingBlock(stream.tokens[token].start)
        val statements = stream.statementsIn(block)
        val index = statements.indexOfFirst { token in it }.takeIf { it >= 0 } ?: return false
        val neighbor = statements.getOrNull(if (down) index + 1 else index - 1)
        if (neighbor == null) {
            // At the block's edge: don't move out of it (or let a line move break the code).
            info.prohibitMove()
            return true
        }
        info.toMove = lines(document, stream, statements[index])
        info.toMove2 = lines(document, stream, neighbor)
        info.indentSource = false
        info.indentTarget = false
        return true
    }

    /** A statement's lines, from any comment lines directly above it to its last line. */
    private fun lines(document: Document, stream: InkoTokenStream, statement: IntRange): LineRange {
        var first = document.getLineNumber(stream.tokens[statement.first].start)
        while (first > 0) {
            val above = document.charsSequence.subSequence(document.getLineStartOffset(first - 1), document.getLineEndOffset(first - 1)).trim()
            if (!above.startsWith("#")) break
            first--
        }
        val last = document.getLineNumber(stream.tokens[statement.last].end)
        return LineRange(first, last + 1)
    }
}

/**
 * Complete Current Statement (⇧⌘⏎ / Ctrl+Shift+Enter): closes open brackets,
 * adds `{ … }` after `if`, `while`, `for`, `loop`, `match`, `fn`, `type`,
 * `trait`, `impl`, and `else` (with the caret inside), and otherwise starts a
 * new line.
 */
class InkoSmartEnterProcessor : SmartEnterProcessor() {
    private val bodied = setOf("if", "while", "for", "loop", "match", "fn", "type", "trait", "impl", "else")

    override fun process(project: Project, editor: Editor, psiFile: PsiFile): Boolean {
        if (psiFile !is InkoFile) return false
        val document = editor.document
        val text = document.immutableCharSequence
        val stream = InkoTokenStream(text)
        val line = document.getLineNumber(editor.caretModel.offset)
        val lineStart = document.getLineStartOffset(line)
        val lineEnd = document.getLineEndOffset(line)
        val indent = text.subSequence(lineStart, lineEnd).takeWhile { it == ' ' }.toString()
        val onLine = stream.tokens.indices.filter { stream.tokens[it].start in lineStart until lineEnd }
        if (onLine.isEmpty()) return false

        // Brackets opened on this line and never closed, innermost last.
        val unclosed = onLine.filter { stream.tokens[it].let { t -> t.type == InkoTokens.LPAREN || t.type == InkoTokens.LBRACKET } && stream.match(it) < 0 }
        fun closers(after: Int) = unclosed.filter { it > after }.reversed().joinToString("") { if (stream.tokens[it].type == InkoTokens.LPAREN) ")" else "]" }
        val lastToken = onLine.last()
        val body = "\n$indent  \n$indent}"

        val (insertion, caretInInsertion) = when {
            // `if x {` (or `fn (x) {`) left open: go inside it, then close it (and any brackets around it).
            stream.tokens[lastToken].type == InkoTokens.LBRACE && isOpen(document, stream, lastToken, indent) ->
                (body + closers(lastToken)) to "\n$indent  ".length
            // A keyword that takes a body, without one: add it (inside any bracket the keyword is in, e.g. a closure argument).
            onLine.none { stream.tokens[it].type == InkoTokens.LBRACE } && bodied(stream, onLine) != null && !isRequiredTraitMethod(text, stream.tokens[onLine.first()].start) -> {
                val keyword = bodied(stream, onLine)!!
                val inside = closers(keyword)
                val outside = unclosed.filter { it < keyword }.reversed().joinToString("") { if (stream.tokens[it].type == InkoTokens.LPAREN) ")" else "]" }
                (inside + " {" + body + outside) to (inside + " {\n$indent  ").length
            }
            // Otherwise close what's open, and start the next line.
            else -> (closers(-1) + "\n$indent").let { it to it.length }
        }
        document.insertString(lineEnd, insertion)
        editor.caretModel.moveToOffset(lineEnd + caretInInsertion)
        PsiDocumentManager.getInstance(project).commitDocument(document)
        return true
    }

    /**
     * Whether the `{` at [brace] is still open. Typing `while x {` in a method
     * pairs it with the method's own `}`; a `}` indented less than the line
     * with the `{` belongs to an outer block, so this one is still open.
     */
    private fun isOpen(document: Document, stream: InkoTokenStream, brace: Int, indent: String): Boolean {
        val close = stream.match(brace)
        if (close < 0) return true
        val line = document.getLineNumber(stream.tokens[close].start)
        val closeIndent = document.charsSequence.subSequence(document.getLineStartOffset(line), stream.tokens[close].start).takeWhile { it == ' ' }.length
        return closeIndent < indent.length
    }

    /** The last keyword on the line that takes a body (`fn pub name`, `} else`, `if x`, `fn (x)`), if any (ast/src/parser.rs). */
    private fun bodied(stream: InkoTokenStream, onLine: List<Int>): Int? =
        onLine.lastOrNull { index ->
            val token = stream.tokens[index]
            token.isKeyword(*bodied.toTypedArray()) &&
                // A match guard's or build tags' `if` takes no body, nor does `fn extern` (a C function's signature).
                (!token.isKeyword("if") || stream.startsIfExpression(index)) &&
                !(token.isKeyword("fn") && stream.tokens.getOrNull(index + 1)?.isKeyword("extern") == true) &&
                !(token.isKeyword("fn") && stream.tokens.getOrNull(index + 1)?.isKeyword("pub") == true && stream.tokens.getOrNull(index + 2)?.isKeyword("extern") == true)
        }

    // A trait's method without a body is a required method; don't give it one.
    private fun isRequiredTraitMethod(text: CharSequence, offset: Int): Boolean =
        InkoOutline.of(text).enclosing(offset).let { path -> path.size >= 1 && path.first().kind == InkoOutline.Kind.TRAIT && path.last().kind == InkoOutline.Kind.METHOD }
}
