package com.setdef.parakeet

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Pass
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.IntroduceTargetChooser
import com.intellij.refactoring.RefactoringActionHandler
import com.intellij.refactoring.introduce.IntroduceTarget
import com.intellij.refactoring.introduce.inplace.OccurrencesChooser
import com.intellij.refactoring.rename.inplace.VariableInplaceRenamer
import com.intellij.refactoring.util.CommonRefactoringUtil

/**
 * Introduce Variable, worked out from tokens: which expressions are at the
 * caret, where else the same expression appears, and where a `let` goes so
 * it's in scope for every use.
 */
object InkoIntroduceVariable {
    /** The expression [selection] covers, trimmed, if it's a whole expression (whole tokens, balanced brackets). */
    fun selected(stream: InkoTokenStream, selection: TextRange): TextRange? {
        val tokens = stream.tokens.filter { selection.contains(it.range) }
        if (tokens.isEmpty()) return null
        val first = stream.tokens.indexOf(tokens.first())
        val last = stream.tokens.indexOf(tokens.last())
        // The selection must not cut a token in half.
        if (stream.tokens.any { it.start < selection.endOffset && it.end > selection.startOffset && !selection.contains(it.range) }) return null
        // Brackets, and an interpolated string's parts, must all be in the selection.
        if ((first..last).any { i -> stream.tokens[i].let { t -> (t.opens || t.closes || t.type == InkoTokens.STRING_MIDDLE) && stream.match(i) !in first..last } }) return null
        val head = stream.tokens[first]
        val tail = stream.tokens[last]
        if (head.type == InkoTokens.DOT || head.type == InkoTokens.COMMA || head.isOperator("=") || tail.isOperator() || tail.type == InkoTokens.DOT || tail.type == InkoTokens.COMMA) return null
        if (head.isKeyword("let", "fn", "type", "trait", "impl", "import", "return", "throw", "case")) return null
        if (!standsAlone(stream, first, last)) return null
        return TextRange(head.start, tail.end)
    }

    /**
     * Whether tokens [first]..[last] are a whole expression where they are.
     * Inko's binary operators share one precedence and group left to right, so
     * `b - c` in `a - b - c` is part of `(a - b) - c`, not an expression of its
     * own: one containing an operator can't follow another operator.
     */
    fun standsAlone(stream: InkoTokenStream, first: Int, last: Int): Boolean {
        val tokens = stream.tokens
        var depth = 0
        var hasOperator = false
        for (i in first..last) {
            val token = tokens[i]
            if (token.opens) depth++ else if (token.closes) depth--
            // A leading `-` or `!` is a prefix, not a binary operator.
            else if (depth == 0 && i > first && (token.isOperator() || token.isKeyword("and", "or", "as"))) hasOperator = true
        }
        if (!hasOperator) return true
        val before = tokens.getOrNull(first - 1) ?: return true
        if (before.isKeyword("and", "or", "as")) return false
        if (!before.isOperator()) return true
        val beforeThat = tokens.getOrNull(first - 2)?.takeIf { it.end == before.start && it.isOperator() }
        return when {
            // `=` and `:=` assign; `==`, `!=`, `<=`, and `>=` compare.
            before.text == "=" -> beforeThat == null || beforeThat.text == ":"
            // A match arm's `->`.
            before.text == ">" -> beforeThat?.text == "-"
            else -> false
        }
    }

    /** The expressions around [offset], innermost first: `foo`, `foo.bar`, `foo.bar(1).baz`. */
    fun candidatesAt(stream: InkoTokenStream, offset: Int): List<TextRange> {
        val tokens = stream.tokens
        var at = tokens.indexOfFirst { offset in it.start..it.end && (isOperand(it) || it.isString) }
        if (at < 0) return emptyList()
        // In an interpolated string's text, the expression is the string.
        if (tokens[at].type == InkoTokens.STRING_MIDDLE || tokens[at].type == InkoTokens.STRING_END) at = stream.match(at).takeIf { it >= 0 } ?: return emptyList()
        // A call's own name means the call: extend to its arguments.
        val start = chainStart(stream, at)
        val ends = mutableListOf<Int>()
        var end = segmentEnd(stream, at)
        ends += end
        while (end + 2 < tokens.size && tokens[end + 1].type == InkoTokens.DOT && isOperand(tokens[end + 2])) {
            end = segmentEnd(stream, end + 2)
            ends += end
        }
        return ends.map { TextRange(tokens[start].start, tokens[it].end) }
            .filter { range -> !(tokens[start].isKeyword("self") && range.length == 4) }
    }

    private fun isOperand(token: InkoToken) = token.type in setOf(
        InkoTokens.IDENTIFIER, InkoTokens.CONSTANT, InkoTokens.FIELD, InkoTokens.STRING, InkoTokens.STRING_START, InkoTokens.NUMBER,
    ) || token.isKeyword("self", "true", "false", "nil")

    /** The last token of the operand at [index], with its call or index arguments: `foo`, `foo(1)`, `Foo(a: 1)`. */
    private fun segmentEnd(stream: InkoTokenStream, index: Int): Int {
        if (stream.tokens[index].type == InkoTokens.STRING_START) return stream.match(index).takeIf { it > index } ?: index
        val next = stream.tokens.getOrNull(index + 1)
        if (next != null && (next.type == InkoTokens.LPAREN || next.type == InkoTokens.LBRACKET) && next.start == stream.tokens[index].end) {
            val close = stream.match(index + 1)
            if (close > 0) return close
        }
        return index
    }

    /** Where the chain containing [index] starts: back over `.member` steps to the first receiver. */
    fun chainStart(stream: InkoTokenStream, index: Int): Int {
        var start = index
        while (start >= 2 && stream.tokens[start - 1].type == InkoTokens.DOT) {
            var receiver = start - 2
            val token = stream.tokens[receiver]
            if (token.closes) {
                val open = stream.match(receiver)
                if (open < 0) break
                receiver = if (open > 0 && isOperand(stream.tokens[open - 1]) && stream.tokens[open - 1].end == stream.tokens[open].start) open - 1 else open
            } else if (!isOperand(token)) {
                break
            }
            start = receiver
        }
        return start
    }

    /**
     * Other places in the same method (or the file, outside methods, or with
     * [fileWide]) where the same expression appears, by tokens, and not as
     * part of a longer chain. Not where it has another value: past an
     * assignment (`x = …`, `x := …`, `x += …`) to a variable or field the
     * expression reads, or in a loop that assigns one and holds only one of
     * the two (its next pass reads what the last assigned).
     */
    fun occurrences(stream: InkoTokenStream, expression: TextRange, fileWide: Boolean = false): List<TextRange> {
        val tokens = stream.tokens
        val pattern = tokens.filter { expression.contains(it.range) }.map { it.text }
        val method = if (fileWide) null else InkoOutline.of(stream.text).enclosing(expression.startOffset).lastOrNull { it.kind == InkoOutline.Kind.METHOD }
        val scope = method?.range ?: TextRange(0, stream.text.length)
        val selected = tokens.indexOfFirst { expression.contains(it.range) }
        fun isVariable(i: Int) = tokens[i].type == InkoTokens.FIELD || (tokens[i].type == InkoTokens.IDENTIFIER && tokens.getOrNull(i - 1)?.type != InkoTokens.DOT)
        val reads = (selected until selected + pattern.size).filter(::isVariable).map { tokens[it].text }.toSet()
        val assignments = tokens.indices.filter { i ->
            tokens[i].start >= scope.startOffset && tokens[i].end <= scope.endOffset && tokens[i].text in reads && isVariable(i) && stream.assignmentAt(i + 1)
        }
        val loops = loops(stream).filter { loop -> assignments.any { it in loop } }
        fun sameValue(i: Int): Boolean {
            val between = minOf(i, selected)..maxOf(i, selected) + pattern.size - 1
            return assignments.none { it in between } && loops.none { (i in it) != (selected in it) }
        }
        return tokens.indices.filter { i ->
            tokens[i].start >= scope.startOffset && tokens[i].end <= scope.endOffset &&
                i + pattern.size <= tokens.size &&
                pattern.indices.all { tokens[i + it].text == pattern[it] } &&
                tokens.getOrNull(i - 1)?.type != InkoTokens.DOT &&
                standsAlone(stream, i, i + pattern.size - 1) &&
                // Not a call of it: `foo.bar` in `foo.bar(1)` calls `bar` with arguments (in `foo.bar.baz`, it's still `foo.bar`).
                tokens.getOrNull(i + pattern.size)?.let { next ->
                    !((next.type == InkoTokens.LPAREN || next.type == InkoTokens.LBRACKET) && next.start == tokens[i + pattern.size - 1].end)
                } != false &&
                // `x = …`, `x := …` and `x += …` assign to `x`, they don't (only) read it.
                !stream.assignmentAt(i + pattern.size) &&
                (i == selected || sameValue(i))
        }.map { TextRange(tokens[it].start, tokens[it + pattern.size - 1].end) }
    }

    /** Each `loop`, `while` and `for`, from its keyword to its body's `}`, as token indices. */
    private fun loops(stream: InkoTokenStream): List<IntRange> {
        val tokens = stream.tokens
        return tokens.indices.filter { tokens[it].isKeyword("loop", "while", "for") }.mapNotNull { keyword ->
            var i = keyword + 1
            while (i < tokens.size && tokens[i].type != InkoTokens.LBRACE) {
                if (tokens[i].closes) return@mapNotNull null
                i = if (tokens[i].opens) stream.match(i).takeIf { it > i }?.plus(1) ?: return@mapNotNull null else i + 1
            }
            stream.match(i).takeIf { it > i }?.let { keyword..it }
        }
    }

    /** What to do: insert [declaration] at [insertAt], and replace [replace] with [name]. */
    class Plan(val name: String, val insertAt: Int, val declaration: String, val replace: List<TextRange>)

    /** The plan, or why there isn't one. */
    fun plan(stream: InkoTokenStream, expression: TextRange, replace: List<TextRange>): Result<Plan> {
        val text = stream.text
        val first = replace.minOf { it.startOffset }
        // The innermost block containing every replaced occurrence, and the statement in it holding the first one.
        val block = replace.map { stream.enclosingBlocks(it.startOffset) }.reduce { common, blocks -> common.zip(blocks).takeWhile { (a, b) -> a == b }.map { it.first } }.lastOrNull() ?: -1
        val startToken = stream.tokens[stream.statementStart(block, first)]
        // A `case` arm without braces has nowhere to put a `let` (and its bindings would be out of scope above it).
        if (startToken.isKeyword("case")) return Result.failure(IllegalStateException("Put the match arm's body in braces ({ … }) to introduce a variable in it."))
        if (block < 0) return Result.failure(IllegalStateException("Variables can only be introduced inside a method."))
        val statement = startToken.start
        val lineStart = text.lastIndexOf('\n', statement - 1) + 1
        val indent = text.subSequence(lineStart, statement).takeWhile { it == ' ' || it == '\t' }
        val name = uniqueName(stream, suggestName(stream, expression), statement)
        return Result.success(Plan(name, statement, "let $name = ${text.subSequence(expression.startOffset, expression.endOffset)}\n$indent", replace.sortedBy { it.startOffset }))
    }

    /** A name for [expression], following Inko's style: snake_case, conversions without `to_`, predicates without `?`. */
    fun suggestName(stream: InkoTokenStream, expression: TextRange): String {
        val tokens = stream.tokens.filter { expression.contains(it.range) }
        val first = tokens.first()
        if (tokens.size == 1) when (first.type) {
            InkoTokens.STRING -> return "string"
            InkoTokens.NUMBER -> return if ('.' in first.text) "float" else "number"
        }
        if (first.type == InkoTokens.STRING_START && stream.match(stream.tokens.indexOf(first)) == stream.tokens.indexOf(tokens.last())) return "string"
        if (first.type == InkoTokens.LBRACKET) return "values"
        // The last name outside any brackets: the method called, or the type constructed.
        var depth = 0
        var last: InkoToken? = null
        for (token in tokens) {
            when {
                token.opens -> depth++
                token.closes -> depth--
                depth == 0 && token.type in setOf(InkoTokens.IDENTIFIER, InkoTokens.CONSTANT, InkoTokens.FIELD) -> last = token
            }
        }
        val name = last ?: return "value"
        val word = when {
            // `Foo.new` and `Foo(…)` make a Foo.
            name.type == InkoTokens.CONSTANT -> snakeCase(name.text)
            name.text in setOf("new", "with_capacity", "default") && tokens.getOrNull(tokens.indexOf(name) - 2)?.type == InkoTokens.CONSTANT ->
                snakeCase(tokens[tokens.indexOf(name) - 2].text)
            else -> name.text.removePrefix("@").removeSuffix("?").removePrefix("to_").removePrefix("into_").removePrefix("as_")
        }
        return word.ifEmpty { "value" }.let { if (it in InkoTokens.KEYWORDS) "${it}_value" else it }
    }

    private fun snakeCase(text: String) = text.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()

    /** [name], or [name] with a number, so it doesn't shadow a local visible at [offset]. */
    private fun uniqueName(stream: InkoTokenStream, name: String, offset: Int): String {
        val taken = InkoLocals.visibleAt(stream.text, offset).map { it.name }.toSet() + InkoLocals.declarations(stream.text).map { it.name }
        if (name !in taken) return name
        return generateSequence(2) { it + 1 }.map { "$name$it" }.first { it !in taken }
    }

    /** Applies [plan] to [editor]'s document, returning the new declaration's name offset. */
    fun apply(project: Project, editor: Editor, plan: Plan): Int {
        val document = editor.document
        var nameOffset = plan.insertAt + "let ".length
        WriteCommandAction.writeCommandAction(project).withName("Introduce Variable").run<RuntimeException> {
            for (range in plan.replace.sortedByDescending { it.startOffset }) document.replaceString(range.startOffset, range.endOffset, plan.name)
            document.insertString(plan.insertAt, plan.declaration)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        return nameOffset
    }
}

/** An expression offered when there's more than one at the caret. */
private class InkoExpressionTarget(private val file: PsiFile, private val range: TextRange) : IntroduceTarget {
    override fun getTextRange(): TextRange = range
    override fun getPlace(): PsiElement? = file.findElementAt(range.startOffset)
    override fun render(): String = file.text.substring(range.startOffset, range.endOffset).replace(Regex("\\s+"), " ")
    override fun isValid(): Boolean = file.isValid
}

/** Introduce Variable (⌥⌘V / Ctrl+Alt+V): `let name = expression`, then rename it in place. */
class InkoIntroduceVariableHandler : RefactoringActionHandler {
    override fun invoke(project: Project, editor: Editor, file: PsiFile, dataContext: DataContext?) {
        val stream = InkoTokenStream(editor.document.immutableCharSequence)
        val selection = editor.selectionModel
        val candidates = if (selection.hasSelection()) {
            listOfNotNull(InkoIntroduceVariable.selected(stream, TextRange(selection.selectionStart, selection.selectionEnd)))
        } else {
            InkoIntroduceVariable.candidatesAt(stream, editor.caretModel.offset)
        }
        when {
            candidates.isEmpty() -> CommonRefactoringUtil.showErrorHint(
                project, editor, "Select an expression to introduce a variable for.", "Introduce Variable", null,
            )
            candidates.size == 1 -> chooseOccurrences(project, editor, file, stream, candidates.single())
            else -> IntroduceTargetChooser.showIntroduceTargetChooser(
                editor, candidates.map { InkoExpressionTarget(file, it) }, { chooseOccurrences(project, editor, file, stream, it.textRange) }, "Expressions", 0,
            )
        }
    }

    private fun chooseOccurrences(project: Project, editor: Editor, file: PsiFile, stream: InkoTokenStream, expression: TextRange) {
        val occurrences = InkoIntroduceVariable.occurrences(stream, expression).ifEmpty { listOf(expression) }
        if (occurrences.size == 1) return introduce(project, editor, file, stream, expression, listOf(expression))
        object : OccurrencesChooser<TextRange>(editor) {
            override fun getOccurrenceRange(occurrence: TextRange): TextRange = occurrence
        }.showChooser(expression, occurrences, object : Pass<OccurrencesChooser.ReplaceChoice>() {
            override fun pass(choice: OccurrencesChooser.ReplaceChoice) {
                introduce(project, editor, file, stream, expression, if (choice == OccurrencesChooser.ReplaceChoice.ALL) occurrences else listOf(expression))
            }
        })
    }

    private fun introduce(project: Project, editor: Editor, file: PsiFile, stream: InkoTokenStream, expression: TextRange, replace: List<TextRange>) {
        val plan = InkoIntroduceVariable.plan(stream, expression, replace).getOrElse {
            return CommonRefactoringUtil.showErrorHint(project, editor, it.message.orEmpty(), "Introduce Variable", null)
        }
        val nameOffset = InkoIntroduceVariable.apply(project, editor, plan)
        val declaration = PsiTreeUtil.getParentOfType(file.findElementAt(nameOffset), InkoDeclaration::class.java, false) ?: return
        editor.caretModel.moveToOffset(nameOffset)
        editor.selectionModel.removeSelection()
        VariableInplaceRenamer(declaration, editor).performInplaceRename()
    }

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) {}
}
