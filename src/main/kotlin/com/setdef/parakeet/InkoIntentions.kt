package com.setdef.parakeet

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil

/** An intention (⌥⏎ / Alt+Enter) that applies an [InkoRewrite] at the caret. */
abstract class InkoIntention(private val family: String) : PsiElementBaseIntentionAction() {
    init {
        text = family
    }

    override fun getFamilyName(): String = family

    abstract fun rewrite(file: PsiFile, offset: Int): InkoRewrite?

    override fun isAvailable(project: Project, editor: Editor, element: PsiElement): Boolean {
        val file = element.containingFile as? InkoFile ?: return false
        val rewrite = rewrite(file, editor.caretModel.offset) ?: return false
        text = rewrite.title
        return true
    }

    override fun invoke(project: Project, editor: Editor, element: PsiElement) {
        val file = element.containingFile ?: return
        val rewrite = rewrite(file, editor.caretModel.offset) ?: return
        val document = editor.document
        rewrite.edits.sortedByDescending { it.range.startOffset }.forEach { document.replaceString(it.range.startOffset, it.range.endOffset, it.text) }
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }
}

class InkoFlipOperandsIntention : InkoIntention("Flip binary expression") {
    override fun rewrite(file: PsiFile, offset: Int) = InkoRewrites.flip(file.text, offset)
}

class InkoInvertIfIntention : InkoIntention("Invert 'if' condition") {
    override fun rewrite(file: PsiFile, offset: Int) = InkoRewrites.invertIf(file.text, offset)
}

class InkoIfToMatchIntention : InkoIntention("Replace 'if' with 'match'") {
    override fun rewrite(file: PsiFile, offset: Int) = InkoRewrites.ifToMatch(file.text, offset)
}

class InkoMatchToIfIntention : InkoIntention("Replace 'match' with 'if'") {
    override fun rewrite(file: PsiFile, offset: Int) = InkoRewrites.matchToIf(file.text, offset)
}

class InkoAddArgumentNamesIntention : InkoIntention("Add argument names") {
    override fun rewrite(file: PsiFile, offset: Int) = InkoArgumentNames.add(file, offset)
}

class InkoRemoveArgumentNamesIntention : InkoIntention("Remove argument names") {
    override fun rewrite(file: PsiFile, offset: Int) = InkoArgumentNames.remove(file, offset)
}

class InkoSpecifyTypeIntention : InkoIntention("Specify type explicitly") {
    override fun rewrite(file: PsiFile, offset: Int) = InkoLetTypes.add(file, offset)
}

class InkoRemoveTypeIntention : InkoIntention("Remove explicit type") {
    override fun rewrite(file: PsiFile, offset: Int) = InkoLetTypes.remove(file, offset)
}

/** Naming or un-naming a call's arguments, when the call resolves to one parameter list. */
object InkoArgumentNames {
    private class Argument(val start: Int, val name: String?, val valueStart: Int, val text: String)

    private class Call(val parameters: List<String>, val arguments: List<Argument>)

    /**
     * Names every argument from the first whose value isn't already its
     * parameter's name (`start` for a `start` parameter stays as it is), since
     * Inko doesn't allow positional arguments after named ones.
     */
    fun add(file: PsiFile, offset: Int): InkoRewrite? {
        val call = callAt(file, offset) ?: return null
        val first = call.arguments.indices.firstOrNull { i ->
            call.arguments[i].name == null && call.arguments[i].text.trim() != call.parameters.getOrNull(i)
        } ?: return null
        val edits = (first until call.arguments.size).mapNotNull { i ->
            val argument = call.arguments[i]
            if (argument.name != null) return@mapNotNull null
            val name = call.parameters.getOrNull(i) ?: return null
            InkoEdit(TextRange(argument.start, argument.start), "$name: ")
        }
        return if (edits.isEmpty()) null else InkoRewrite("Add argument names", edits)
    }

    /** Removes argument names, when each named argument is in its parameter's position. */
    fun remove(file: PsiFile, offset: Int): InkoRewrite? {
        val call = callAt(file, offset) ?: return null
        val named = call.arguments.withIndex().filter { it.value.name != null }
        if (named.isEmpty() || named.any { (i, argument) -> argument.name != call.parameters.getOrNull(i) }) return null
        return InkoRewrite("Remove argument names", named.map { (_, argument) -> InkoEdit(TextRange(argument.start, argument.valueStart), "") })
    }

    private fun callAt(file: PsiFile, offset: Int): Call? {
        val text = file.text
        val stream = InkoTokenStream(text)
        val tokens = stream.tokens
        // The caret on a callee's name, or inside its parentheses.
        val nameHere = PsiTreeUtil.getParentOfType(file.findElementAt(offset), InkoName::class.java, false)
            ?: if (offset > 0) PsiTreeUtil.getParentOfType(file.findElementAt(offset - 1), InkoName::class.java, false) else null
        val call = nameHere?.let { name ->
            val index = tokens.indexOfFirst { it.start == name.textRange.startOffset }
            tokens.getOrNull(index + 1)?.takeIf { it.type == InkoTokens.LPAREN && it.start == name.textRange.endOffset }?.let { InkoCalls.at(file, it.end) }
        } ?: InkoCalls.at(file, offset) ?: return null
        // `fn name(…)` declares parameters; it isn't a call.
        if (generateSequence(call.callee.previousToken()) { it.previousToken() }.takeWhile { it.isToken(InkoTokens.KEYWORD) }.any { it.text == "fn" }) return null
        val path = Inko.pathOf(file.virtualFile) ?: return null
        val symbols = InkoDefinitionIndex.getInstance(file.project).symbols(Inko.projectRoot(path))
        val parameters = InkoResolver.resolveDefinitions(call.callee, exact = true).map { InkoCalls.parameters(it.first, symbols) }.distinct().singleOrNull()
            ?.map { it.substringBefore(':').trim().split(' ').last() } ?: return null
        val open = tokens.indexOfFirst { it.start == call.openParen }.takeIf { it >= 0 } ?: return null
        val close = stream.match(open).takeIf { it > open } ?: return null
        val arguments = mutableListOf<Argument>()
        var start = open + 1
        var i = open + 1
        while (i <= close) {
            if (i == close || tokens[i].type == InkoTokens.COMMA) {
                if (i > start) {
                    val first = tokens[start]
                    val named = first.type == InkoTokens.IDENTIFIER && i - 1 > start && tokens[start + 1].isOperator(":") &&
                        tokens.getOrNull(start + 2)?.let { it.isOperator("=") && it.start == tokens[start + 1].end } != true
                    val valueStart = if (named) tokens[start + 2].start else first.start
                    arguments += Argument(first.start, if (named) first.text else null, valueStart, text.substring(valueStart, tokens[i - 1].end))
                }
                start = i + 1
                i++
                continue
            }
            i = if (tokens[i].opens && stream.match(i) > i) stream.match(i) + 1 else i + 1
        }
        if (arguments.size > parameters.size) return null
        return Call(parameters, arguments)
    }
}

/** Adding or removing a `let`'s type annotation. */
object InkoLetTypes {
    private class Let(val name: InkoToken, val annotation: TextRange?, val value: TextRange)

    /** `let x = value` → `let x: Type = value`, with the compiler's (fresh) type for `x`, from Cuttlebone. */
    fun add(file: PsiFile, offset: Int): InkoRewrite? {
        val let = letAt(file.text, offset) ?: return null
        if (let.annotation != null) return null
        val type = InkoCompilerTypes.ofBinding(file as? InkoFile ?: return null, let.name.start) ?: return null
        return InkoRewrite("Specify type explicitly", listOf(InkoEdit(TextRange(let.name.end, let.name.end), ": $type")))
    }

    /**
     * `let x: Type = value` → `let x = value`, only where the value says its type itself (so the annotation
     * can't be what decides it) and the compiler's (fresh) type for the value is the one written.
     */
    fun remove(file: PsiFile, offset: Int): InkoRewrite? {
        val let = letAt(file.text, offset) ?: return null
        val annotation = let.annotation ?: return null
        val written = annotation.substring(file.text).substringAfter(':').trim()
        val owner = evidentOwner(file.text, let.value) ?: return null
        val inferred = InkoCompilerTypes.of(file as? InkoFile ?: return null, let.value, stale = false)?.type ?: return null
        if (written.replace(" ", "") != inferred.replace(" ", "")) return null
        if (owner.isNotEmpty() && inferred.replace(Regex("^((ref|mut|uni)\\s+)+"), "").substringBefore('[') != owner) return null
        return InkoRewrite("Remove explicit type", listOf(InkoEdit(annotation, "")))
    }

    /**
     * Whether [value] says its type itself, as Cuttlebone's type hints count it: a literal, a cast, a type's
     * constructor `Point(…)`, or a static method `Type.name(…)` (which must return that type). Gives the type
     * it names (empty for a literal or a cast), or null when the type comes from elsewhere.
     */
    private fun evidentOwner(text: CharSequence, value: TextRange): String? {
        val stream = InkoTokenStream(text)
        val all = stream.tokens
        val first = all.indexOfFirst { it.start >= value.startOffset }.takeIf { it >= 0 } ?: return null
        val last = all.indexOfLast { it.end <= value.endOffset }.takeIf { it >= first } ?: return null
        val head = all[first]
        if (first == last && (head.type == InkoTokens.STRING || head.type == InkoTokens.NUMBER || head.isKeyword("true", "false", "nil"))) return ""
        if (head.type == InkoTokens.STRING_START && stream.match(first) == last) return ""
        var depth = 0
        for (i in first..last) {
            if (all[i].opens) depth++ else if (all[i].closes) depth--
            if (depth == 0 && i > first && all[i].isKeyword("as")) return ""
        }
        if (head.type != InkoTokens.CONSTANT) return null
        var end = first + 1
        if (all.getOrNull(end)?.type == InkoTokens.DOT) {
            if (all.getOrNull(end + 1)?.type != InkoTokens.IDENTIFIER) return null
            end += 2
        }
        if (end == last + 1) return head.text.takeIf { end > first + 1 }
        if (all.getOrNull(end)?.type == InkoTokens.LPAREN && stream.match(end) == last) return head.text
        return null
    }

    /** The `let` whose `let … =` part holds [offset]. */
    private fun letAt(text: CharSequence, offset: Int): Let? {
        val stream = InkoTokenStream(text)
        val tokens = stream.tokens
        for (index in tokens.indices.reversed()) {
            if (!tokens[index].isKeyword("let") || tokens[index].start > offset) continue
            var n = index + 1
            while (tokens.getOrNull(n)?.isKeyword("mut") == true) n++
            val name = tokens.getOrNull(n)?.takeIf { it.type == InkoTokens.IDENTIFIER } ?: return null
            // The `=` that assigns: not part of `==`, and outside the annotation's brackets.
            var i = n + 1
            var equals = -1
            while (i < tokens.size) {
                val token = tokens[i]
                if (token.opens) { i = stream.match(i).takeIf { it > i }?.plus(1) ?: return null; continue }
                if (token.isOperator("=") && tokens.getOrNull(i + 1)?.let { it.isOperator("=") && it.start == token.end } != true) { equals = i; break }
                if (stream.lineBreakBetween(i - 1, i) && !stream.continues(i)) return null
                i++
            }
            if (equals < 0 || offset > tokens[equals].end) return null
            val end = stream.statementEnd(index)
            if (end <= equals) return null
            val annotation = if (tokens[n + 1].isOperator(":")) TextRange(name.end, tokens[equals - 1].end) else null
            return Let(name, annotation, TextRange(tokens[equals + 1].start, tokens[end].end))
        }
        return null
    }
}
