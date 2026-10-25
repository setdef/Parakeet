package com.setdef.parakeet

import com.intellij.codeInsight.template.TemplateBuilderImpl
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInsight.template.impl.ConstantNode
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.IntroduceTargetChooser
import com.intellij.refactoring.RefactoringActionHandler
import com.intellij.refactoring.introduce.IntroduceTarget
import com.intellij.refactoring.util.CommonRefactoringUtil

/**
 * Extract Method, worked out from tokens: which variables flow in (become
 * parameters) and out (become return values), what kind of method to make
 * (instance, static, or module, like the method it's taken from), and where
 * it goes (right after that method).
 *
 * Types come from what's written (parameter and `let` annotations, the
 * method's return type), else the compiler's type for a local ([plan]'s
 * `bindingType`), else what's obvious (a local made by `Type(…)` or
 * `Type.new`, a value that's a literal, `Type(…)` or `Type.new`); any other
 * type is left as a placeholder to fill in. Owned values are passed as
 * `mut` borrows so the caller can keep using them; value types (`Int`,
 * `Float`, `Bool`, `String`, `Nil`) and existing borrows are passed as they are.
 */
object InkoExtractMethod {
    const val PLACEHOLDER = "Type"
    private val VALUE_TYPES = setOf("Int", "Float", "Bool", "String", "Nil")
    private val BORROWS = listOf("ref ", "mut ", "uni ", "move ")

    /** Replace [replace] with [call], and insert [method] at [insertAt] (after the enclosing method). */
    class Plan(
        val replace: TextRange,
        val call: String,
        /** Where the new method's name is in [call]. */
        val callName: Int,
        val insertAt: Int,
        val method: String,
        /** Where the new method's name is in [method]. */
        val methodName: Int,
        /** Where each placeholder type is in [method]. */
        val placeholders: List<Int>,
        val name: String,
    )

    /**
     * [bindingType] gives the compiler's type for the local whose name is at an offset, if known: a fresh
     * answer only, since the type is written into code.
     */
    fun plan(text: CharSequence, selection: TextRange, bindingType: (Int) -> String? = { null }): Result<Plan> {
        fun fail(message: String) = Result.failure<Plan>(IllegalStateException(message))
        val stream = InkoTokenStream(text)
        val tokens = stream.tokens
        val inside = tokens.indices.filter { selection.contains(tokens[it].range) }
        if (inside.isEmpty()) return fail("Select the statements or expression to extract.")
        if (tokens.any { it.start < selection.endOffset && it.end > selection.startOffset && !selection.contains(it.range) }) {
            return fail("The selection cuts a word or string in half.")
        }
        val first = inside.first()
        val last = inside.last()
        val range = TextRange(tokens[first].start, tokens[last].end)
        if ((first..last).any { i -> (tokens[i].opens || tokens[i].closes) && stream.match(i) !in first..last }) {
            return fail("Select whole statements or a whole expression: the selection has unmatched brackets.")
        }

        val outline = InkoOutline.of(text)
        val path = outline.enclosing(range.startOffset)
        val methodNode = path.lastOrNull { it.kind == InkoOutline.Kind.METHOD } ?: return fail("Select code inside a method.")
        val bodyOpen = (tokens.indices).firstOrNull { tokens[it].start >= methodNode.nameOffset && tokens[it].type == InkoTokens.LBRACE }
            ?: return fail("Select code inside a method's body.")
        if (first <= bodyOpen || tokens[last].end >= methodNode.range.endOffset) return fail("Select code inside a method's body.")
        val signature = InkoSignatures.at(text, methodNode.nameOffset)

        // Control flow that would have to leave the new method.
        for (i in first..last) {
            val token = tokens[i]
            when {
                token.isKeyword("return", "throw") && !ownedBy(stream, i, first, setOf("fn")) ->
                    return fail("The selection uses `${token.text}`, which would return from the new method instead.")
                token.isKeyword("try") && !ownedBy(stream, i, first, setOf("fn")) ->
                    return fail("The selection uses `try`, whose error would have to be returned from the new method.")
                token.isKeyword("break", "next") && !ownedBy(stream, i, first, setOf("loop", "while", "for")) ->
                    return fail("The selection uses `${token.text}` for a loop outside it.")
            }
        }

        // Whole statements of one block, or one whole expression.
        val block = stream.enclosingBlock(range.startOffset)
        val statements = stream.statementsIn(block)
        val startStatement = statements.indexOfFirst { it.first == first }
        val endStatement = statements.indexOfFirst { it.last == last }
        var statementMode = startStatement >= 0 && endStatement >= startStatement && stream.enclosingBlock(tokens[last].start) == block
        if (statementMode && (startStatement..endStatement).any { tokens[statements[it].first].isKeyword("case") }) {
            return fail("Match arms (`case …`) can't be extracted; select the code inside an arm.")
        }
        // The block's last statement is its value: returned when it gives one and the block's value is used
        // (a method body's only when the method returns something).
        val tail = statementMode && endStatement == statements.lastIndex && isValue(stream, statements[endStatement]) &&
            (block != bodyOpen || signature?.returnType != null)
        // A single value at the end of a block is an expression (its block's value).
        if (tail && startStatement == endStatement) statementMode = false
        if (!statementMode && InkoIntroduceVariable.selected(stream, range) == null) {
            return fail("Select whole statements, or one whole expression.")
        }

        // Variables flowing in and out.
        val methodEnd = tokens.indexOfFirst { it.start >= methodNode.range.endOffset }.let { if (it < 0) tokens.size else it }
        fun localAt(i: Int): InkoLocal? = InkoLocals.visibleAt(text, tokens[i].start).lastOrNull { it.name == tokens[i].text }
        fun isUse(i: Int): Boolean {
            val token = tokens[i]
            if (token.type != InkoTokens.IDENTIFIER || tokens.getOrNull(i - 1)?.type == InkoTokens.DOT) return false
            // A named argument's label (`f(x: 1)`), not a use of `x`.
            val previous = tokens.getOrNull(i - 1)
            val next = tokens.getOrNull(i + 1)
            if (next?.isOperator(":") == true && (previous?.type == InkoTokens.LPAREN || previous?.type == InkoTokens.COMMA) &&
                tokens.getOrNull(i + 2)?.let { it.isOperator("=") && it.start == next.end } != true) return false
            return true
        }
        val inputs = linkedMapOf<Int, InkoLocal>()
        val assigned = mutableSetOf<Int>()
        for (i in first..last) {
            if (!isUse(i)) continue
            val local = localAt(i) ?: continue
            if (local.offset >= range.startOffset) continue
            inputs.putIfAbsent(local.offset, local)
            if (stream.assignmentAt(i + 1)) assigned += local.offset
        }
        fun usedAfter(offset: Int) = (last + 1 until methodEnd).any { isUse(it) && localAt(it)?.offset == offset }
        val outputs = InkoLocals.declarations(text).filter { range.contains(it.offset) && usedAfter(it.offset) }
        // In a loop the variable is declared outside of, the next pass reads what the selection assigns.
        fun loopedOver(local: InkoLocal) = ownedBy(stream, first, stream.indexAt(local.offset), setOf("loop", "while", "for"))
        val reassigned = inputs.values.filter { it.offset in assigned && (usedAfter(it.offset) || loopedOver(it)) }
        if (reassigned.size > 1 || (reassigned.isNotEmpty() && outputs.isNotEmpty())) {
            val names = (reassigned + outputs).joinToString(", ") { "`${it.name}`" }
            return fail("The code after the selection needs $names back, including reassigned variables, and Inko can't assign several variables at once.")
        }
        if (!statementMode && (outputs.isNotEmpty() || reassigned.isNotEmpty())) return fail("Select whole statements to extract code that defines variables used later.")

        // What kind of method, and its name.
        val owner = path.dropLast(1).lastOrNull { it.kind != InkoOutline.Kind.METHOD }?.takeIf { it.children.contains(methodNode) }
        val modifiers = text.subSequence(methodNode.range.startOffset, methodNode.nameOffset).split(Regex("\\s+")).toSet()
        val newModifiers = when {
            owner == null -> ""
            "static" in modifiers -> "static "
            "mut" in modifiers -> "mut "
            else -> ""
        }
        val taken = (owner?.children ?: outline.roots).filter { it.kind == InkoOutline.Kind.METHOD }.map { it.label }.toSet()
        val name = if ("extracted" !in taken) "extracted" else generateSequence(2) { it + 1 }.map { "extracted$it" }.first { it !in taken }

        // Types.
        fun declaredType(local: InkoLocal): String? {
            signature?.parameters?.firstOrNull { it.name == local.name && local.offset < bodyStart(tokens, bodyOpen) }?.let { return it.type }
            val index = tokens.indexOfFirst { it.start == local.offset }
            if (tokens.getOrNull(index + 1)?.isOperator(":") == true) {
                // `let x: Type = …`
                val equals = (index + 2 until tokens.size).firstOrNull { tokens[it].isOperator("=") && tokens.getOrNull(it + 1)?.let { n -> n.isOperator("=") && n.start == tokens[it].end } != true }
                    ?: return null
                return text.substring(tokens[index + 2].start, tokens[equals - 1].end)
            }
            return bindingType(local.offset) ?: local.type
        }
        fun parameterType(local: InkoLocal): String {
            val type = declaredType(local) ?: return PLACEHOLDER
            if (BORROWS.any { type.startsWith(it) } || type.substringBefore('[').trim() in VALUE_TYPES) return type
            return "mut $type"
        }
        val parameters = inputs.values.map { local -> "${local.name}: ${parameterType(local)}" }
        val arguments = if (inputs.isEmpty()) "" else inputs.values.joinToString(", ", "(", ")") { it.name }

        // The body (re-indented) and the call.
        val methodIndent = indentOf(text, methodNode.range.startOffset)
        val bodyIndent = "$methodIndent  "
        val selected = text.substring(range.startOffset, range.endOffset)
        val baseIndent = indentOf(text, range.startOffset)
        // Inko parameters can't be reassigned, so ones the code assigns to start as a mutable copy.
        val bodyLines = (inputs.values.filter { it.offset in assigned }.map { "let mut ${it.name} = ${it.name}" } +
            selected.split('\n').mapIndexed { index, line ->
                if (index == 0) line else line.removePrefix(baseIndent).let { if (it.length == line.length) line.trimStart() else it }
            }).toMutableList()
        val returnType: String?
        val call: String
        val callName: Int
        when {
            !statementMode -> {
                val methodReturnsIt = tail && block == bodyOpen && signature?.returnType != null
                returnType = if (methodReturnsIt) signature!!.returnType else InkoIntroduceParameter.inferType(tokens.subList(first, last + 1)) ?: PLACEHOLDER
                call = name + arguments
                callName = 0
            }
            reassigned.isNotEmpty() -> {
                val variable = reassigned.single()
                bodyLines += variable.name
                returnType = declaredType(variable) ?: PLACEHOLDER
                call = "${variable.name} = $name$arguments"
                callName = "${variable.name} = ".length
            }
            outputs.size == 1 -> {
                val variable = outputs.single()
                bodyLines += variable.name
                returnType = declaredType(variable) ?: PLACEHOLDER
                val let = "let ${if (isMutable(stream, variable)) "mut " else ""}${variable.name} = "
                call = let + name + arguments
                callName = let.length
            }
            outputs.size > 1 -> {
                bodyLines += outputs.joinToString(", ", "(", ")") { it.name }
                returnType = outputs.joinToString(", ", "(", ")") { declaredType(it) ?: PLACEHOLDER }
                val let = "let " + outputs.joinToString(", ", "(", ")") { (if (isMutable(stream, it)) "mut " else "") + it.name } + " = "
                call = let + name + arguments
                callName = let.length
            }
            tail -> {
                val methodReturnsIt = block == bodyOpen && signature?.returnType != null
                val lastStatement = statements[endStatement]
                returnType = if (methodReturnsIt) signature!!.returnType
                    else InkoIntroduceParameter.inferType(tokens.subList(lastStatement.first, lastStatement.last + 1)) ?: PLACEHOLDER
                call = name + arguments
                callName = 0
            }
            else -> {
                returnType = null
                call = name + arguments
                callName = 0
            }
        }

        // The enclosing method's type parameters (`[A, B: Trait]`), when the new signature uses any of them.
        val typeParameters = signature?.typeParameters?.takeIf { declared ->
            val names = Regex("(?:\\[|,)\\s*([A-Z][A-Za-z0-9_]*)").findAll(declared).map { it.groupValues[1] }
            val used = (parameters + listOfNotNull(returnType)).joinToString(" ")
            names.any { Regex("\\b$it\\b").containsMatchIn(used) }
        }.orEmpty()
        val header = "fn $newModifiers$name$typeParameters" +
            (if (parameters.isEmpty()) "" else parameters.joinToString(", ", "(", ")")) +
            (returnType?.let { " -> $it" } ?: "") + " {"
        val method = "\n\n$methodIndent$header\n" + bodyLines.joinToString("\n") { if (it.isBlank()) "" else bodyIndent + it } + "\n$methodIndent}"
        val headerStart = method.indexOf(header)
        val methodName = headerStart + "fn $newModifiers".length
        val placeholders = Regex("\\b$PLACEHOLDER\\b").findAll(header).map { headerStart + it.range.first }.toList()
        return Result.success(Plan(range, call, callName, methodNode.range.endOffset, method, methodName, placeholders, name))
    }

    private fun bodyStart(tokens: List<InkoToken>, bodyOpen: Int) = tokens[bodyOpen].start

    /** Whether [statement] gives a value: it isn't a `let`, an assignment, a declaration, or a jump. */
    private fun isValue(stream: InkoTokenStream, statement: IntRange): Boolean {
        val first = stream.tokens[statement.first]
        if (first.isKeyword("let", "fn", "import", "type", "trait", "impl", "return", "throw", "break", "next")) return false
        var depth = 0
        for (i in statement) {
            val token = stream.tokens[i]
            if (token.opens) depth++ else if (token.closes) depth--
            else if (depth == 0 && stream.assignmentAt(i)) {
                // `==`, `!=`, `<=`, and `>=` compare; a lone `=` (or `:=`) assigns.
                val previous = stream.tokens.getOrNull(i - 1)
                if (!(token.isOperator("=") && previous != null && previous.end == token.start && previous.isOperator("=", "!", "<", ">"))) return false
            }
        }
        return true
    }

    /**
     * Whether the token at [index] belongs to a block opened inside the
     * selection (from [first]) by one of [keywords] (`fn` for a closure, or a
     * loop's keyword), so it stays within the extracted code.
     */
    private fun ownedBy(stream: InkoTokenStream, index: Int, first: Int, keywords: Set<String>): Boolean =
        stream.enclosingBlocks(stream.tokens[index].start).filter { it >= first }.any { open ->
            var i = open - 1
            while (i >= first) {
                val token = stream.tokens[i]
                if (token.closes) {
                    i = stream.match(i).let { if (it >= 0) it - 1 else i - 1 }
                    continue
                }
                if (token.type == InkoTokens.KEYWORD && token.text in keywords) return@any true
                if (token.type == InkoTokens.LBRACE || (i < open - 1 && stream.lineBreakBetween(i, i + 1) && !stream.continues(i + 1))) break
                i--
            }
            false
        }

    private fun isMutable(stream: InkoTokenStream, local: InkoLocal): Boolean =
        stream.tokens.indexOfFirst { it.start == local.offset }.let { it > 0 && stream.tokens[it - 1].isKeyword("mut") }

    private fun indentOf(text: CharSequence, offset: Int): String {
        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        return text.subSequence(lineStart, text.length).takeWhile { it == ' ' || it == '\t' }.toString()
    }

    /** Applies [plan], returning where the call's name and the method's start up in the result. */
    fun apply(project: Project, editor: Editor, plan: Plan): Pair<Int, Int> {
        val document = editor.document
        WriteCommandAction.writeCommandAction(project).withName("Extract Method").run<RuntimeException> {
            // The method goes after the selection, so insert it first.
            document.insertString(plan.insertAt, plan.method)
            document.replaceString(plan.replace.startOffset, plan.replace.endOffset, plan.call)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        val shift = plan.call.length - plan.replace.length
        return (plan.replace.startOffset + plan.callName) to (plan.insertAt + shift)
    }
}

/**
 * Extract Method (⌥⌘M / Ctrl+Alt+M): then name the method in place (at the
 * call and the definition together) and Tab through any placeholder types.
 */
class InkoExtractMethodHandler : RefactoringActionHandler {
    override fun invoke(project: Project, editor: Editor, file: PsiFile, dataContext: DataContext?) {
        val selection = editor.selectionModel
        if (selection.hasSelection()) return extract(project, editor, file, TextRange(selection.selectionStart, selection.selectionEnd))
        val stream = InkoTokenStream(editor.document.immutableCharSequence)
        val candidates = InkoIntroduceVariable.candidatesAt(stream, editor.caretModel.offset)
        when {
            candidates.isEmpty() -> CommonRefactoringUtil.showErrorHint(project, editor, "Select the statements or expression to extract.", TITLE, null)
            candidates.size == 1 -> extract(project, editor, file, candidates.single())
            else -> IntroduceTargetChooser.showIntroduceTargetChooser(
                editor, candidates.map { Target(file, it) }, { extract(project, editor, file, it.textRange) }, "Expressions", 0,
            )
        }
    }

    private fun extract(project: Project, editor: Editor, file: PsiFile, range: TextRange) {
        val text = editor.document.immutableCharSequence
        val inko = file as? InkoFile
        // Off the EDT, where asking Cuttlebone for a local's type may wait for its answer.
        val planned = ProgressManager.getInstance().runProcessWithProgressSynchronously<Result<InkoExtractMethod.Plan>, RuntimeException>(
            { ReadAction.compute<Result<InkoExtractMethod.Plan>, RuntimeException> {
                InkoExtractMethod.plan(text, range) { offset -> inko?.let { InkoCompilerTypes.ofBinding(it, offset) } }
            } },
            TITLE, true, project,
        )
        val plan = planned.getOrElse {
            return CommonRefactoringUtil.showErrorHint(project, editor, it.message.orEmpty(), TITLE, null)
        }
        val (callName, methodStart) = InkoExtractMethod.apply(project, editor, plan)
        editor.selectionModel.removeSelection()
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return
        val builder = TemplateBuilderImpl(psiFile)
        builder.replaceElement(psiFile, TextRange(callName, callName + plan.name.length), "NAME", ConstantNode(plan.name), true)
        val definition = methodStart + plan.methodName
        builder.replaceElement(psiFile, TextRange(definition, definition + plan.name.length), "NAME_DEFINITION", "NAME", false)
        plan.placeholders.forEachIndexed { index, offset ->
            val at = methodStart + offset
            builder.replaceElement(psiFile, TextRange(at, at + InkoExtractMethod.PLACEHOLDER.length), "TYPE$index", ConstantNode(InkoExtractMethod.PLACEHOLDER), true)
        }
        // Building an inline template edits the document.
        WriteCommandAction.writeCommandAction(project).withName("Extract Method").run<RuntimeException> {
            editor.caretModel.moveToOffset(psiFile.textRange.startOffset)
            TemplateManager.getInstance(project).startTemplate(editor, builder.buildInlineTemplate())
        }
    }

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) {}

    private class Target(private val file: PsiFile, private val range: TextRange) : IntroduceTarget {
        override fun getTextRange(): TextRange = range
        override fun getPlace(): PsiElement? = file.findElementAt(range.startOffset)
        override fun render(): String = file.text.substring(range.startOffset, range.endOffset).replace(Regex("\\s+"), " ")
        override fun isValid(): Boolean = file.isValid
    }

    private companion object {
        const val TITLE = "Extract Method"
    }
}
