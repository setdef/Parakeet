package com.setdef.parakeet

import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil

/**
 * Parameter-name hints before positional arguments (`greet(name: 'Alice')`),
 * shown only when the call resolves to exactly one parameter list, so a hint
 * is never a guess. Arguments that are already named, or whose text is the
 * parameter's name, get none.
 */
class InkoParameterHintsProvider : InlayHintsProvider {
    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? =
        if (file is InkoFile) Collector() else null

    private class Collector : SharedBypassCollector {
        override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
            if (!element.isToken(InkoTokens.LPAREN)) return
            for ((offset, name) in InkoParameterHints.at(element)) {
                sink.addPresentation(InlineInlayPosition(offset, false, 0), null, null, HintFormat.default) { text("$name:", null) }
            }
        }
    }
}

object InkoParameterHints {
    /** For a call's `(`, the (offset, parameter name) hints for its arguments. */
    fun at(openParen: PsiElement): List<Pair<Int, String>> {
        val callee = openParen.previousToken()?.let { PsiTreeUtil.getParentOfType(it, InkoName::class.java, false) } ?: return emptyList()
        // `fn name(…)` declares parameters; it isn't a call.
        if (generateSequence(callee.previousToken()) { it.previousToken() }.takeWhile { it.isToken(InkoTokens.KEYWORD) }.any { it.text == "fn" }) return emptyList()
        val file = openParen.containingFile ?: return emptyList()
        val path = Inko.pathOf(file.virtualFile) ?: return emptyList()
        val symbols = InkoDocIndex.getInstance(file.project).symbols(Inko.projectRoot(path))
        val parameterLists = InkoResolver.resolveDefinitions(callee).map { InkoCalls.parameters(it.first, symbols) }.distinct()
        val parameters = parameterLists.singleOrNull()?.map { it.substringBefore(':').trim().split(' ').last() } ?: return emptyList()
        return arguments(openParen).zip(parameters).mapNotNull { (argument, name) ->
            val (start, text) = argument
            when {
                Regex("""^[a-z_]\w*\s*:(?!:)""").containsMatchIn(text) -> null // Already named, e.g. `count: 1`.
                text.trim() == name -> null
                else -> start to name
            }
        }
    }

    /** Each top-level argument's start offset and text, between [openParen] and its `)`. */
    private fun arguments(openParen: PsiElement): List<Pair<Int, String>> {
        val text = openParen.containingFile.text
        val result = mutableListOf<Pair<Int, String>>()
        var depth = 0
        var start: Int? = null
        var token = openParen.nextToken()
        fun finish(end: Int) {
            start?.let { result += it to text.substring(it, end) }
            start = null
        }
        while (token != null) {
            when {
                token.isToken(InkoTokens.LPAREN) || token.isToken(InkoTokens.LBRACKET) || token.isToken(InkoTokens.LBRACE) -> { if (start == null) start = token.textRange.startOffset; depth++ }
                token.isToken(InkoTokens.RPAREN) && depth == 0 -> { finish(token.textRange.startOffset); return result }
                // A `]` or `}` that closes something outside the call: the call isn't finished yet.
                (token.isToken(InkoTokens.RBRACKET) || token.isToken(InkoTokens.RBRACE)) && depth == 0 -> return emptyList()
                token.isToken(InkoTokens.RPAREN) || token.isToken(InkoTokens.RBRACKET) || token.isToken(InkoTokens.RBRACE) -> depth--
                token.isToken(InkoTokens.COMMA) && depth == 0 -> finish(token.textRange.startOffset)
                token !is com.intellij.psi.PsiComment -> if (start == null) start = token.textRange.startOffset
            }
            token = token.nextToken()
        }
        return emptyList() // Unclosed: still being typed.
    }
}
