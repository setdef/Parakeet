package com.setdef.parakeet

import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.OwnBypassCollector
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.eclipse.lsp4j.InlayHintKind

/**
 * Parameter-name hints before positional arguments (`greet(name: 'Alice')`):
 * Cuttlebone's, or else shown only when the call resolves to exactly one
 * parameter list, so a hint is never a guess. Arguments that are already
 * named, or whose text is the parameter's name, get none.
 */
class InkoParameterHintsProvider : InlayHintsProvider {
    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? =
        if (file is InkoFile) Collector() else null

    private class Collector : OwnBypassCollector {
        override fun collectHintsForFile(file: PsiFile, sink: InlayTreeSink) {
            for ((offset, name) in InkoParameterHints.inFile(file)) {
                sink.addPresentation(InlineInlayPosition(offset, false, 0), null, null, HintFormat.default) { text("$name:", null) }
            }
        }
    }
}

/** The types the compiler infers for `let` bindings and closure arguments that don't write one, from Cuttlebone. */
class InkoTypeHintsProvider : InlayHintsProvider {
    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? =
        if (file is InkoFile) Collector() else null

    private class Collector : OwnBypassCollector {
        override fun collectHintsForFile(file: PsiFile, sink: InlayTreeSink) {
            for ((offset, label) in InkoParameterHints.types(file)) {
                sink.addPresentation(InlineInlayPosition(offset, true, 0), null, null, HintFormat.default) { text(label, null) }
            }
        }
    }
}

object InkoParameterHints {
    /**
     * [file]'s parameter-name hints, as (offset, name): Cuttlebone's when it has
     * a fresh answer (one request for the whole file), else the heuristic's.
     */
    fun inFile(file: PsiFile): List<Pair<Int, String>> {
        cuttlebone(file)?.let { hints -> return hints.filter { it.third == InlayHintKind.Parameter }.map { it.first to it.second.removeSuffix(":") } }
        return PsiTreeUtil.collectElements(file) { it.isToken(InkoTokens.LPAREN) }.flatMap { at(it, askCuttlebone = false) }
    }

    /** [file]'s inferred types, as (offset, `: Type`): only Cuttlebone knows them. */
    fun types(file: PsiFile): List<Pair<Int, String>> =
        cuttlebone(file).orEmpty().filter { it.third == InlayHintKind.Type }.map { it.first to it.second }

    /** Cuttlebone's hints for [file] as (offset, label, kind), or null without a fresh answer. */
    private fun cuttlebone(file: PsiFile): List<Triple<Int, String, InlayHintKind?>>? {
        val hints = CuttleboneQueries.getInstance(file.project).inlayHints(file)?.value ?: return null
        val document = file.viewProvider.document ?: return null
        return hints.mapNotNull { hint ->
            val offset = CuttleboneQueries.offset(document, hint.position) ?: return@mapNotNull null
            val label = if (hint.label.isLeft) hint.label.left else hint.label.right.joinToString("") { it.value }
            Triple(offset, label, hint.kind)
        }
    }

    /**
     * For a call's `(`, the (offset, parameter name) hints for its arguments, from
     * Parakeet's own resolve. Without [askCuttlebone], resolve doesn't ask Cuttlebone
     * about the call: a file whose hints it can't give gets no definitions either.
     */
    fun at(openParen: PsiElement, askCuttlebone: Boolean = true): List<Pair<Int, String>> {
        val callee = openParen.previousToken()?.let { PsiTreeUtil.getParentOfType(it, InkoName::class.java, false) } ?: return emptyList()
        // `fn name(…)` declares parameters; it isn't a call.
        if (generateSequence(callee.previousToken()) { it.previousToken() }.takeWhile { it.isToken(InkoTokens.KEYWORD) }.any { it.text == "fn" }) return emptyList()
        val file = openParen.containingFile ?: return emptyList()
        val path = Inko.pathOf(file.virtualFile) ?: return emptyList()
        val symbols = InkoDocIndex.getInstance(file.project).symbols(Inko.projectRoot(path))
        val parameterLists = InkoResolver.resolveDefinitions(callee, askCuttlebone).map { InkoCalls.parameters(it.first, symbols) }.distinct()
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
