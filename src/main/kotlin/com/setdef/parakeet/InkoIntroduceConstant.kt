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
 * Introduce Constant: `let NAME = expression` at the top of the module, used
 * in place of the expression. Inko constants hold only integers, floats,
 * strings (without interpolation), arrays of constants, and binary
 * expressions of constants (references/syntax.md).
 */
object InkoIntroduceConstant {
    /** Insert [declaration] at [insertAt], and replace [replace] with [name]. */
    class Plan(val name: String, val insertAt: Int, val declaration: String, val replace: List<TextRange>)

    /** Why [expression] can't be a constant's value, or null if it can. */
    fun unsupported(stream: InkoTokenStream, expression: TextRange): String? {
        val tokens = stream.tokens.filter { expression.contains(it.range) }
        if (tokens.isEmpty()) return "Select an expression."
        val constants = InkoOutline.of(stream.text).roots.filter { it.kind == InkoOutline.Kind.CONSTANT }.map { it.label }.toSet()
        val invalid = "Inko constants can only hold numbers, strings, arrays of constants, and arithmetic on them"
        for ((index, token) in tokens.withIndex()) {
            val allowed = when (token.type) {
                InkoTokens.NUMBER -> true
                // `${…}` interpolation isn't allowed in constants.
                InkoTokens.STRING -> "\${" !in token.text
                InkoTokens.CONSTANT -> token.text in constants || tokens.getOrNull(index - 1)?.type == InkoTokens.DOT
                InkoTokens.OPERATOR, InkoTokens.COMMA, InkoTokens.LBRACKET, InkoTokens.RBRACKET, InkoTokens.LPAREN, InkoTokens.RPAREN -> true
                else -> false
            }
            if (!allowed) return if (token.isString) "$invalid, and their strings can't use \${…}." else "$invalid, not `${token.text}`."
            // `(…)` only groups: a call like `Foo(…)` isn't a constant.
            if (token.type == InkoTokens.LPAREN && index > 0 && tokens[index - 1].end == token.start && tokens[index - 1].type == InkoTokens.CONSTANT) {
                return "$invalid, not `${tokens[index - 1].text}(…)`."
            }
        }
        return null
    }

    fun plan(stream: InkoTokenStream, expression: TextRange, replace: List<TextRange>): Result<Plan> {
        unsupported(stream, expression)?.let { return Result.failure(IllegalStateException(it)) }
        val text = stream.text
        val outline = InkoOutline.of(text)
        val value = text.substring(expression.startOffset, expression.endOffset)
        val taken = outline.roots.map { it.label }.toSet()
        val suggested = InkoIntroduceVariable.suggestName(stream, expression).uppercase().let { if (it == "VALUE" || it == "NUMBER") "VALUE" else it }
        val name = if (suggested !in taken) suggested else generateSequence(2) { it + 1 }.map { "${suggested}_$it" }.first { it !in taken }

        // After the last top-level constant, else after the imports, else at the top.
        val lastConstant = outline.roots.lastOrNull { it.kind == InkoOutline.Kind.CONSTANT && it.range.endOffset <= expression.startOffset }
        val imports = InkoImports.parse(text).statements
        val (insertAt, declaration) = when {
            lastConstant != null -> lineEnd(text, lastConstant.range.endOffset) to "\nlet $name = $value"
            imports.isNotEmpty() -> lineEnd(text, imports.last().range.endOffset) to "\n\nlet $name = $value"
            else -> 0 to "let $name = $value\n\n"
        }
        return Result.success(Plan(name, insertAt, declaration, replace.sortedBy { it.startOffset }))
    }

    private fun lineEnd(text: CharSequence, offset: Int): Int = text.indexOf('\n', offset).let { if (it < 0) text.length else it }

    /** Applies [plan], returning the offset of the new constant's name. */
    fun apply(project: Project, editor: Editor, plan: Plan): Int {
        val document = editor.document
        WriteCommandAction.writeCommandAction(project).withName("Introduce Constant").run<RuntimeException> {
            for (range in plan.replace.sortedByDescending { it.startOffset }) document.replaceString(range.startOffset, range.endOffset, plan.name)
            document.insertString(plan.insertAt, plan.declaration)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        return plan.insertAt + plan.declaration.indexOf("let ") + "let ".length
    }
}

/** Introduce Constant (⌥⌘C / Ctrl+Alt+C), then rename it in place. */
class InkoIntroduceConstantHandler : RefactoringActionHandler {
    override fun invoke(project: Project, editor: Editor, file: PsiFile, dataContext: DataContext?) {
        val stream = InkoTokenStream(editor.document.immutableCharSequence)
        val selection = editor.selectionModel
        val candidates = if (selection.hasSelection()) {
            listOfNotNull(InkoIntroduceVariable.selected(stream, TextRange(selection.selectionStart, selection.selectionEnd)))
        } else {
            InkoIntroduceVariable.candidatesAt(stream, editor.caretModel.offset).filter { InkoIntroduceConstant.unsupported(stream, it) == null }
        }
        when {
            candidates.isEmpty() -> CommonRefactoringUtil.showErrorHint(project, editor, "Select a number, string, or other constant expression.", TITLE, null)
            candidates.size == 1 -> chooseOccurrences(project, editor, file, stream, candidates.single())
            else -> IntroduceTargetChooser.showIntroduceTargetChooser(
                editor, candidates.map { Target(file, it) }, { chooseOccurrences(project, editor, file, stream, it.textRange) }, "Expressions", 0,
            )
        }
    }

    private fun chooseOccurrences(project: Project, editor: Editor, file: PsiFile, stream: InkoTokenStream, expression: TextRange) {
        InkoIntroduceConstant.unsupported(stream, expression)?.let { return CommonRefactoringUtil.showErrorHint(project, editor, it, TITLE, null) }
        val occurrences = InkoIntroduceVariable.occurrences(stream, expression, fileWide = true).ifEmpty { listOf(expression) }
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
        val plan = InkoIntroduceConstant.plan(stream, expression, replace).getOrElse {
            return CommonRefactoringUtil.showErrorHint(project, editor, it.message.orEmpty(), TITLE, null)
        }
        val nameOffset = InkoIntroduceConstant.apply(project, editor, plan)
        val declaration = PsiTreeUtil.getParentOfType(file.findElementAt(nameOffset), InkoDeclaration::class.java, false) ?: return
        editor.caretModel.moveToOffset(nameOffset)
        editor.selectionModel.removeSelection()
        VariableInplaceRenamer(declaration, editor).performInplaceRename()
    }

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) {}

    private class Target(private val file: PsiFile, private val range: TextRange) : IntroduceTarget {
        override fun getTextRange(): TextRange = range
        override fun getPlace(): PsiElement? = file.findElementAt(range.startOffset)
        override fun render(): String = file.text.substring(range.startOffset, range.endOffset).replace(Regex("\\s+"), " ")
        override fun isValid(): Boolean = file.isValid
    }

    private companion object {
        const val TITLE = "Introduce Constant"
    }
}
