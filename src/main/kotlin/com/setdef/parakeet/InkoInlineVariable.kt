package com.setdef.parakeet

import com.intellij.lang.Language
import com.intellij.lang.refactoring.InlineActionHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.refactoring.util.CommonRefactoringUtil

/**
 * Inline Variable, worked out from tokens and Inko's rules (references/syntax.md):
 * only a plain `let name = …` (not a pattern, `let … else`, or `let _`, which
 * drops its value right away), never reassigned (with `=`, `:=`, or a
 * compound assignment like `+=`).
 */
object InkoInlineVariable {
    /** Replace each of [uses] with [expression] (in parentheses where marked), and delete [declaration]. */
    class Plan(val expression: String, val declaration: TextRange, val uses: List<Pair<TextRange, Boolean>>)

    // Operators and keywords that bind loosely: Inko's binary operators all share one precedence (left to right),
    // `and`/`or` bind more loosely than them (`a or b == c` is `a or (b == c)`), and `as` binds like them. Treating them
    // all as loose only ever adds parentheses. Prefixes bind tighter than a following `.call`.
    private fun isLoose(token: InkoToken) = token.isOperator() || token.isKeyword("and", "or", "as")

    private fun isPrefix(token: InkoToken) = token.isOperator("!", "-") || token.isKeyword("ref", "mut", "move", "recover", "try", "throw", "return")

    fun plan(stream: InkoTokenStream, nameOffset: Int, uses: List<TextRange>): Result<Plan> {
        fun fail(message: String) = Result.failure<Plan>(IllegalStateException(message))
        val tokens = stream.tokens
        val n = tokens.indexOfFirst { it.start == nameOffset }.takeIf { it >= 0 } ?: return fail("Put the caret on a variable.")
        val name = tokens[n].text
        val before = tokens.getOrNull(n - 1)
        val isLet = before?.isKeyword("let") == true || (before?.isKeyword("mut") == true && tokens.getOrNull(n - 2)?.isKeyword("let") == true)
        if (!isLet) return fail("Only variables defined with `let $name = …` can be inlined.")
        if (name == "_") return fail("`let _ = …` drops its value right away, so it can't be inlined.")

        // `let name = …` or `let name: Type = …`.
        var equals = n + 1
        if (tokens.getOrNull(equals)?.isOperator(":") == true) {
            var depth = 0
            while (equals < tokens.size && !(depth == 0 && tokens[equals].isOperator("=") && tokens.getOrNull(equals + 1)?.isOperator("=") != true)) {
                if (tokens[equals].opens) depth++ else if (tokens[equals].closes) depth--
                equals++
            }
        }
        if (tokens.getOrNull(equals)?.isOperator("=") != true || equals + 1 >= tokens.size) return fail("`$name` has no value to inline.")
        val start = equals + 1
        val end = stream.statementEnd(start)
        // `let … = x else …` only binds when the pattern matches.
        if ((start..end).any { tokens[it].isKeyword("else") && depthBetween(stream, start, it) == 0 }) return fail("`let … else` can't be inlined.")

        for (use in uses) {
            val i = tokens.indexOfFirst { it.start == use.startOffset }
            if (stream.assignmentAt(i + 1)) return fail("`$name` is assigned a new value, so it can't be inlined.")
        }

        // In a closure, `try`, `throw` and `return` would leave the closure, not the method the `let` is in.
        val control = (start..end).firstOrNull { tokens[it].isKeyword("try", "throw", "return") && !inClosure(stream, it, start) }
        if (control != null && uses.any { use -> inClosure(stream, tokens.indexOfFirst { it.start == use.startOffset }, n) }) {
            return fail("`$name`'s value uses `${tokens[control].text}`, which would return from the closure it would be moved into.")
        }

        val expression = stream.text.substring(tokens[start].start, tokens[end].end)
        val loose = (start..end).any { isLoose(tokens[it]) && depthBetween(stream, start, it) == 0 } || isPrefix(tokens[start])
        val parenthesized = uses.map { use ->
            val i = tokens.indexOfFirst { it.start == use.startOffset }
            val previous = tokens.getOrNull(i - 1)
            val next = tokens.getOrNull(i + 1)
            val tight = (previous != null && (isLoose(previous) || isPrefix(previous))) ||
                (next != null && (isLoose(next) || next.type == InkoTokens.DOT || ((next.type == InkoTokens.LPAREN || next.type == InkoTokens.LBRACKET) && next.start == tokens[i].end)))
            use to (loose && tight)
        }

        val text = stream.text
        val lineStart = text.lastIndexOf('\n', tokens[n].start - 1) + 1
        val lineEnd = text.indexOf('\n', tokens[end].end).let { if (it < 0) text.length else it + 1 }
        return Result.success(Plan(expression, TextRange(lineStart, lineEnd), parenthesized))
    }

    /** Whether token [index] is in a closure's body (`fn (…) { … }`) that starts after token [after]. */
    private fun inClosure(stream: InkoTokenStream, index: Int, after: Int): Boolean =
        stream.enclosingBlocks(stream.tokens[index].start).any { block -> block > after && isClosureBody(stream, block) }

    // A block whose header, back to its statement's start, is a closure's: `fn`, its parameters, and its return type.
    private fun isClosureBody(stream: InkoTokenStream, block: Int): Boolean {
        var j = block - 1
        while (j >= 0) {
            val token = stream.tokens[j]
            if (token.closes) { j = stream.match(j).let { if (it >= 0) it - 1 else j - 1 }; continue }
            if (token.isKeyword("fn")) return true
            if (token.type == InkoTokens.LBRACE || token.type == InkoTokens.RBRACE || token.type == InkoTokens.COMMA ||
                token.type == InkoTokens.LPAREN || stream.lineBreakBetween(j, j + 1)) return false
            j--
        }
        return false
    }

    private fun depthBetween(stream: InkoTokenStream, from: Int, to: Int): Int =
        (from until to).sumOf { i -> stream.tokens[i].let { if (it.opens) 1 else if (it.closes) -1 else 0 }.toInt() }

    fun apply(project: Project, editor: Editor, plan: Plan) {
        val document = editor.document
        WriteCommandAction.writeCommandAction(project).withName("Inline Variable").run<RuntimeException> {
            // Back to front (uses come after the declaration), so earlier offsets stay put.
            for ((range, parens) in plan.uses.sortedByDescending { it.first.startOffset }) {
                document.replaceString(range.startOffset, range.endOffset, if (parens) "(${plan.expression})" else plan.expression)
            }
            document.deleteString(plan.declaration.startOffset, plan.declaration.endOffset)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
    }
}

/** Inline Variable (⌥⌘N / Ctrl+Alt+N) for local variables. */
class InkoInlineVariableHandler : InlineActionHandler() {
    override fun isEnabledForLanguage(language: Language): Boolean = language == InkoLanguage

    override fun canInlineElement(element: PsiElement): Boolean = element is InkoDeclaration && element.isLocal

    override fun inlineElement(project: Project, editor: Editor, element: PsiElement) {
        val declaration = element as? InkoDeclaration ?: return
        val uses = InkoUsages.inFile(declaration).filter { (name, isDeclaration) -> !isDeclaration && name != declaration }.map { it.first.textRange }
        if (uses.isEmpty()) return CommonRefactoringUtil.showErrorHint(project, editor, "`${declaration.name}` is never used.", "Inline Variable", null)
        val stream = InkoTokenStream(editor.document.immutableCharSequence)
        val plan = InkoInlineVariable.plan(stream, declaration.textRange.startOffset, uses).getOrElse {
            return CommonRefactoringUtil.showErrorHint(project, editor, it.message.orEmpty(), "Inline Variable", null)
        }
        // Inko values are owned: using the expression in several places evaluates (and creates) it each time.
        if (uses.size > 1 && !ApplicationManager.getApplication().isUnitTestMode) {
            val answer = Messages.showYesNoDialog(
                project,
                "`${declaration.name}` is used ${uses.size} times. Inlining evaluates `${plan.expression}` at each use, instead of once.",
                "Inline Variable",
                Messages.getQuestionIcon(),
            )
            if (answer != Messages.YES) return
        }
        InkoInlineVariable.apply(project, editor, plan)
    }
}
