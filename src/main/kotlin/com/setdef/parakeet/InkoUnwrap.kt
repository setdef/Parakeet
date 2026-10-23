package com.setdef.parakeet

import com.intellij.codeInsight.unwrap.UnwrapDescriptor
import com.intellij.codeInsight.unwrap.Unwrapper
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Pair
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Unwrap/Remove (⌘⇧Delete / Ctrl+Shift+Delete): the reverse of Surround With.
 * For each block around the caret, innermost first, it offers to unwrap it
 * (keep its body, drop the `if`, `while`, `loop`, `for`, `recover`, or braces)
 * and, for an `else`, to remove it.
 */
class InkoUnwrapDescriptor : UnwrapDescriptor {
    override fun collectUnwrappers(project: Project, editor: Editor, file: PsiFile): List<Pair<PsiElement, Unwrapper>> {
        val text = editor.document.charsSequence
        return InkoUnwraps.around(text, editor.caretModel.offset).mapNotNull { option ->
            val anchor = file.findElementAt(option.anchor) ?: return@mapNotNull null
            Pair(anchor, InkoUnwrapper(option))
        }
    }

    override fun showOptionsDialog(): Boolean = true

    override fun shouldTryToRestoreCaretPosition(): Boolean = false
}

/** One way to unwrap: replace [range] with [replacement]. */
class InkoUnwrapOption(val description: String, val anchor: Int, val range: TextRange, val replacement: String)

class InkoUnwrapper(private val option: InkoUnwrapOption) : Unwrapper {
    override fun isApplicableTo(e: PsiElement): Boolean = true

    override fun collectElementsToIgnore(element: PsiElement, result: MutableSet<PsiElement>) {}

    override fun getDescription(e: PsiElement): String = option.description

    // The PSI is flat, so only the keyword stands in for what's affected.
    override fun collectAffectedElements(e: PsiElement, toExtract: MutableList<in PsiElement>): PsiElement = e

    override fun unwrap(editor: Editor, element: PsiElement): List<PsiElement> {
        val document = editor.document
        document.replaceString(option.range.startOffset, option.range.endOffset, option.replacement)
        PsiDocumentManager.getInstance(element.project).commitDocument(document)
        editor.caretModel.moveToOffset(option.range.startOffset)
        return emptyList()
    }
}

object InkoUnwraps {
    private val CONSTRUCTS = setOf("if", "else", "while", "loop", "for", "recover")

    fun around(text: CharSequence, offset: Int): List<InkoUnwrapOption> {
        val stream = InkoTokenStream(text)
        val tokens = stream.tokens
        val options = mutableListOf<InkoUnwrapOption>()
        for (open in stream.enclosingBlocks(offset).reversed()) {
            val close = stream.match(open).takeIf { it > open } ?: continue
            val keyword = governingKeyword(stream, open)
            val body = body(text, tokens[open], tokens[close])
            when (keyword?.text) {
                null -> {
                    // A bare `{ … }` block statement (not a method's, type's, or closure's body).
                    if (!startsStatement(stream, open)) continue
                    options += InkoUnwrapOption("Unwrap '{...}'", tokens[open].start, TextRange(tokens[open].start, tokens[close].end), body)
                }
                "else" -> {
                    val ifStart = chainStart(stream, tokens.indexOf(keyword))
                    val chainEnd = chainEnd(stream, ifStart)
                    options += InkoUnwrapOption("Unwrap 'else...'", keyword.start, TextRange(tokens[ifStart].start, tokens[chainEnd].end), body)
                    // `} else { … }` → `}`.
                    val previousClose = tokens.indexOf(keyword) - 1
                    options += InkoUnwrapOption("Remove 'else...'", keyword.start, TextRange(tokens[previousClose].end, tokens[close].end), "")
                }
                "if" -> {
                    val ifIndex = tokens.indexOf(keyword)
                    // Unwrapping an `else if`'s `if` would leave a bare `else`; its own `else` options cover it.
                    if (ifIndex > 0 && tokens[ifIndex - 1].isKeyword("else")) continue
                    options += InkoUnwrapOption("Unwrap 'if...'", keyword.start, TextRange(keyword.start, tokens[chainEnd(stream, ifIndex)].end), body)
                }
                else -> options += InkoUnwrapOption("Unwrap '${keyword.text}...'", keyword.start, TextRange(keyword.start, tokens[close].end), body)
            }
        }
        return options
    }

    // The construct keyword a block belongs to: the nearest one before its `{` at the same level (`if a and b {` → `if`).
    private fun governingKeyword(stream: InkoTokenStream, open: Int): InkoToken? {
        val tokens = stream.tokens
        var i = open - 1
        while (i >= 0) {
            val token = tokens[i]
            if (token.closes) {
                val opener = stream.match(i)
                // `} else {`: a `}` right before means an else's block (or a chain); anything else isn't ours.
                if (token.type == InkoTokens.RBRACE) return null
                i = if (opener >= 0) opener - 1 else i - 1
                continue
            }
            if (token.type == InkoTokens.LBRACE || token.type == InkoTokens.COMMA || token.type == InkoTokens.LPAREN || token.type == InkoTokens.LBRACKET) return null
            if (token.type == InkoTokens.KEYWORD) {
                // A match guard's or an impl's `if` doesn't make the block an `if`'s.
                if (token.text == "if" && !stream.startsIfExpression(i)) return null
                if (token.text in CONSTRUCTS) return token
                if (token.text in setOf("fn", "type", "trait", "impl", "let", "match", "case", "return")) return null
            }
            if (stream.lineBreakBetween(i, i + 1) && !stream.continues(i + 1)) return null
            i--
        }
        return null
    }

    private fun startsStatement(stream: InkoTokenStream, open: Int): Boolean =
        open == 0 || stream.lineBreakBetween(open - 1, open) || stream.tokens[open - 1].type == InkoTokens.LBRACE

    // From an `else`, back to the `if` its chain starts with (past `else if`s).
    private fun chainStart(stream: InkoTokenStream, elseIndex: Int): Int {
        val tokens = stream.tokens
        var i = elseIndex - 1
        while (i >= 0) {
            if (tokens[i].type == InkoTokens.RBRACE) {
                val open = stream.match(i).takeIf { it >= 0 } ?: return i
                val keyword = governingKeyword(stream, open)
                val keywordIndex = keyword?.let { tokens.indexOf(it) } ?: return open
                // An `if` preceded by `else` continues the chain further back.
                if (keyword.text == "if" && keywordIndex > 0 && tokens[keywordIndex - 1].isKeyword("else")) {
                    i = keywordIndex - 2
                    continue
                }
                return keywordIndex
            }
            i--
        }
        return 0
    }

    // From an `if`, forward through its `else` branches to the last `}`.
    private fun chainEnd(stream: InkoTokenStream, ifIndex: Int): Int {
        val tokens = stream.tokens
        var open = (ifIndex until tokens.size).firstOrNull { tokens[it].type == InkoTokens.LBRACE } ?: return ifIndex
        while (true) {
            val close = stream.match(open).takeIf { it > open } ?: return open
            if (tokens.getOrNull(close + 1)?.isKeyword("else") != true) return close
            open = (close + 1 until tokens.size).firstOrNull { tokens[it].type == InkoTokens.LBRACE } ?: return close
        }
    }

    /** A block's contents, dedented one level, to stand where the construct started. */
    private fun body(text: CharSequence, open: InkoToken, close: InkoToken): String {
        val inner = text.subSequence(open.end, close.start).toString()
        if ('\n' !in inner) return inner.trim()
        val lines = inner.split('\n').drop(1).dropLast(1).dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
        if (lines.isEmpty()) return ""
        val dedented = lines.map { if (it.startsWith("  ")) it.substring(2) else it.trimStart() }
        // The first line goes where the keyword was, which already has the line's indentation.
        return (listOf(dedented.first().trimStart()) + dedented.drop(1)).joinToString("\n")
    }
}
