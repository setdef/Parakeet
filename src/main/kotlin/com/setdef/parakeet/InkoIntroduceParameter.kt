package com.setdef.parakeet

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.IntroduceTargetChooser
import com.intellij.refactoring.RefactoringActionHandler
import com.intellij.refactoring.introduce.IntroduceTarget
import com.intellij.refactoring.ui.RefactoringDialog
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent
import com.setdef.parakeet.InkoDefinitionKind as Kind

/**
 * Introduce Parameter: an expression in a method becomes a new parameter, and
 * every call passes that expression instead. Built on Change Signature.
 */
object InkoIntroduceParameter {
    class Plan(
        val method: InkoDeclaration,
        val expression: TextRange,
        /** Where the same expression appears in the method (including [expression]). */
        val occurrences: List<TextRange>,
        val name: String,
        /** The parameter's type, when it can be worked out from the expression. */
        val type: String?,
        /** What callers in other modules must import to evaluate the expression: (module, name). */
        val imports: List<Pair<String, String>>,
        /** Of those, names private to this module, which other modules can't import. */
        val privateNames: Set<String> = emptySet(),
    ) {
        fun expressionText(): String = method.containingFile.text.substring(expression.startOffset, expression.endOffset)
    }

    /** [compilerType] gives the compiler's type for the expression in a range, if known, to write as the parameter's. */
    fun plan(file: PsiFile, expression: TextRange, compilerType: (TextRange) -> String? = { null }): Result<Plan> {
        fun fail(message: String) = Result.failure<Plan>(IllegalStateException(message))
        val text = file.text
        val node = file.inkoOutline.enclosing(expression.startOffset).lastOrNull { it.kind == InkoOutline.Kind.METHOD }
            ?: return fail("Only expressions inside a method can become parameters.")
        val method = PsiTreeUtil.getParentOfType(file.findElementAt(node.nameOffset), InkoDeclaration::class.java, false)
            ?: return fail("Only expressions inside a method can become parameters.")
        InkoChangeSignature.unsupported(method)?.let { return fail(it) }
        val signature = InkoSignatures.at(text, node.nameOffset) ?: return fail("`${method.name}`'s signature couldn't be read.")
        if (expression.startOffset < signature.headerRange.endOffset) return fail("Select an expression in the method's body.")

        val stream = InkoTokenStream(text)
        val tokens = stream.tokens.filter { expression.contains(it.range) }
        tokens.firstOrNull { it.isKeyword("self") }?.let { return fail("The expression uses `self`, which callers don't have.") }
        tokens.firstOrNull { it.type == InkoTokens.FIELD }?.let { return fail("The expression uses `${it.text}`, which callers can't see.") }

        val names = PsiTreeUtil.findChildrenOfType(file, InkoName::class.java).filter { expression.contains(it.textRange) }
        val imports = InkoImports.parse(text)
        for (name in names) {
            if (InkoNameContext.of(name, imports) is InkoNameContext.Local) return fail("The expression uses `${name.text}`, a variable callers don't have.")
            // `Self` names the type it's in, and is never imported.
            if (name.previousToken().isToken(InkoTokens.DOT) || name.text == "Self") continue
            if (InkoResolver.resolveDefinitions(name, exact = true).any { it.first.kind == Kind.INSTANCE_METHOD || it.first.kind == Kind.STATIC_METHOD }) {
                return fail("The expression calls `${name.text}` on `self`, which callers don't have.")
            }
        }
        val (needed, private) = InkoPortableNames.of(file, names)

        val occurrences = InkoIntroduceVariable.occurrences(stream, expression).ifEmpty { listOf(expression) }
        val taken = signature.parameters.map { it.name }.toSet() +
            InkoLocals.declarations(text).filter { node.range.contains(it.offset) }.map { it.name }
        val suggested = InkoIntroduceVariable.suggestName(stream, expression)
        val name = if (suggested !in taken) suggested else generateSequence(2) { it + 1 }.map { "$suggested$it" }.first { it !in taken }
        return Result.success(Plan(method, expression, occurrences, name, compilerType(expression) ?: inferType(tokens), needed, private))
    }

    /** A type for an expression when it's obvious: a literal, or `Type(…)`/`Type.new(…)`. */
    fun inferType(tokens: List<InkoToken>): String? {
        val first = tokens.firstOrNull() ?: return null
        if (tokens.size == 1) when {
            first.type == InkoTokens.STRING -> return "String"
            first.type == InkoTokens.NUMBER -> return if ('.' in first.text && !first.text.startsWith("0x")) "Float" else "Int"
            first.isKeyword("true", "false") -> return "Bool"
        }
        if (first.type == InkoTokens.STRING_START && tokens.last().type == InkoTokens.STRING_END && depthReturnsToZeroOnlyAtEnd(tokens)) return "String"
        if (tokens.size == 2 && first.isOperator("-") && tokens[1].type == InkoTokens.NUMBER) return inferType(tokens.drop(1))
        if (first.type == InkoTokens.CONSTANT) {
            val second = tokens.getOrNull(1)
            // `Type(…)` as the whole expression, or `Type.new` / `Type.new(…)`.
            if (second?.type == InkoTokens.LPAREN && tokens.last().type == InkoTokens.RPAREN && depthReturnsToZeroOnlyAtEnd(tokens.drop(1))) return first.text
            if (second?.type == InkoTokens.DOT && tokens.getOrNull(2)?.text == "new" &&
                (tokens.size == 3 || (tokens[3].type == InkoTokens.LPAREN && tokens.last().type == InkoTokens.RPAREN && depthReturnsToZeroOnlyAtEnd(tokens.drop(3))))) {
                return first.text
            }
        }
        return null
    }

    private fun depthReturnsToZeroOnlyAtEnd(tokens: List<InkoToken>): Boolean {
        var depth = 0
        tokens.forEachIndexed { index, token ->
            if (token.opens) depth++ else if (token.closes) depth--
            if (depth == 0 && index < tokens.size - 1) return false
        }
        return depth == 0
    }

    /** The refactoring for [plan]: append parameter [name] of [type], passed the expression by every call. */
    fun processor(project: Project, plan: Plan, name: String, type: String, replaceAll: Boolean): InkoChangeSignatureProcessor {
        val signature = InkoSignatures.at(plan.method.containingFile.text, plan.method.textRange.startOffset)!!
        val change = InkoSignatureChange(
            signature.name,
            signature.parameters.mapIndexed { index, it -> InkoParameterChange(index, it.name, it.type) } +
                InkoParameterChange(-1, name, type, plan.expressionText()),
            signature.returnType,
        )
        return InkoChangeSignatureProcessor(
            project, plan.method, change,
            replacements = if (replaceAll) plan.occurrences else listOf(plan.expression),
            replacement = name,
            commandName = "Introduce Parameter",
            imports = plan.imports,
            privateImports = plan.privateNames,
        )
    }
}

/**
 * What code moving out of its module needs: for [names] (as written in
 * [file]), the (module, name) pairs to import where the code goes, and which
 * of those are private to [file]'s module (so other modules can't import them).
 * Names imported into [file] are imported the same way; names defined in its
 * module are imported from it; anything else (locals, the prelude) needs nothing.
 */
object InkoPortableNames {
    fun of(file: PsiFile, names: List<InkoName>): Pair<List<Pair<String, String>>, Set<String>> {
        val module = Inko.pathOf(file.virtualFile)?.let(Inko::moduleName)
        val imports = InkoImports.parse(file.text)
        val needed = mutableListOf<Pair<String, String>>()
        val private = mutableSetOf<String>()
        for (name in names) {
            // `Self` names the type it's in, and is never imported.
            if (name.previousToken().isToken(InkoTokens.DOT) || name.text == "Self") continue
            if (InkoNameContext.of(name, imports) is InkoNameContext.Local) continue
            imports.symbol(name.text)?.let { (from, original) -> needed += from to original; continue }
            val resolved = InkoResolver.resolveDefinitions(name, exact = true)
            val (definition, site) = resolved.firstOrNull { it.first.kind in setOf(Kind.TYPE, Kind.TRAIT, Kind.CONSTANT, Kind.MODULE_METHOD) } ?: continue
            if (module == null || definition.module != module) continue
            needed += module to name.text
            if (!isPublic(site)) private += name.text
        }
        return needed.distinct() to private
    }

    /** Whether the declaration named at [site] is `pub` (`let pub X`, `fn pub x`, `type pub X`). */
    private fun isPublic(site: PsiElement): Boolean {
        var token = site.previousToken()
        while (token.isToken(InkoTokens.KEYWORD)) {
            if (token!!.text == "pub") return true
            token = token.previousToken()
        }
        return false
    }
}

/** Introduce Parameter (⌥⌘P / Ctrl+Alt+P). */
class InkoIntroduceParameterHandler : RefactoringActionHandler {
    override fun invoke(project: Project, editor: Editor, file: PsiFile, dataContext: DataContext?) {
        // Every call to the method gets the new argument, so its calls must be found.
        if (CuttleboneService.getInstance(project).unavailable) return Cuttlebone.showNeeded(project, editor, TITLE)
        val stream = InkoTokenStream(editor.document.immutableCharSequence)
        val selection = editor.selectionModel
        val candidates = if (selection.hasSelection()) {
            listOfNotNull(InkoIntroduceVariable.selected(stream, TextRange(selection.selectionStart, selection.selectionEnd)))
        } else {
            InkoIntroduceVariable.candidatesAt(stream, editor.caretModel.offset)
        }
        when {
            candidates.isEmpty() -> CommonRefactoringUtil.showErrorHint(project, editor, "Select an expression to turn into a parameter.", TITLE, null)
            candidates.size == 1 -> introduce(project, editor, file, candidates.single())
            else -> IntroduceTargetChooser.showIntroduceTargetChooser(
                editor, candidates.map { ExpressionTarget(file, it) }, { introduce(project, editor, file, it.textRange) }, "Expressions", 0,
            )
        }
    }

    private fun introduce(project: Project, editor: Editor, file: PsiFile, expression: TextRange) {
        // Off the EDT, where asking Cuttlebone for the expression's type may wait for its answer.
        val type = ProgressManager.getInstance().runProcessWithProgressSynchronously<String?, RuntimeException>(
            { ReadAction.compute<String?, RuntimeException> { compilerType(file, expression) } }, TITLE, true, project,
        )
        val plan = InkoIntroduceParameter.plan(file, expression) { type }.getOrElse {
            return CommonRefactoringUtil.showErrorHint(project, editor, it.message.orEmpty(), TITLE, null)
        }
        InkoIntroduceParameterDialog(project, plan).show()
    }

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) {}

    private class ExpressionTarget(private val file: PsiFile, private val range: TextRange) : IntroduceTarget {
        override fun getTextRange(): TextRange = range
        override fun getPlace(): PsiElement? = file.findElementAt(range.startOffset)
        override fun render(): String = file.text.substring(range.startOffset, range.endOffset).replace(Regex("\\s+"), " ")
        override fun isValid(): Boolean = file.isValid
    }

    companion object {
        private const val TITLE = "Introduce Parameter"

        /** The compiler's type for the expression at [range], if it can be written in [file] as it is (no import needed). */
        fun compilerType(file: PsiFile, range: TextRange): String? {
            val inko = file as? InkoFile ?: return null
            val imports = mutableSetOf<Pair<String, String>>()
            return InkoCompilerTypes.of(inko, range, stale = false)?.type?.let { InkoCompilerTypes.writable(inko, it, imports) }?.takeIf { imports.isEmpty() }
        }
    }
}

/** The new parameter's name and type, and whether to replace every occurrence. */
class InkoIntroduceParameterDialog(project: Project, private val plan: InkoIntroduceParameter.Plan) : RefactoringDialog(project, true) {
    private val nameField = JBTextField(plan.name)
    private val typeField = JBTextField(plan.type.orEmpty())
    private val replaceAll = JBCheckBox("Replace all ${plan.occurrences.size} occurrences in `${plan.method.name}`", true)

    init {
        title = "Introduce Parameter"
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Name:") { cell(nameField).align(AlignX.FILL) }
        row("Type:") {
            cell(typeField).align(AlignX.FILL).comment(if (plan.type == null) "Inko needs a type for every parameter, and this one couldn't be worked out." else "")
        }
        if (plan.occurrences.size > 1) row { cell(replaceAll) }
        row { comment("Every call to `${plan.method.name}` passes <code>${plan.expressionText().replace("<", "&lt;")}</code>.") }
    }

    override fun getPreferredFocusedComponent(): JComponent = if (plan.type == null) typeField else nameField

    override fun canRun() {
        val name = nameField.text.trim()
        if (!Regex("[a-z_][A-Za-z0-9_]*").matches(name) || name in InkoTokens.KEYWORDS) throw ConfigurationException("`$name` isn't a valid parameter name.")
        if (typeField.text.isBlank()) throw ConfigurationException("Enter the parameter's type.")
    }

    override fun doAction() {
        invokeRefactoring(InkoIntroduceParameter.processor(project, plan, nameField.text.trim(), typeField.text.trim(), replaceAll.isSelected))
    }
}
