package com.setdef.parakeet

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.InspectionSuppressor
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.SuppressQuickFix
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.RefactoringFactory

/**
 * Inspections for the rules in Inko's style guide (docs/source/references/style.md)
 * that `inko fmt` doesn't apply. They only look at the project's own files, not
 * the standard library or dependencies.
 */
abstract class InkoStyleInspection : LocalInspectionTool() {
    // These read the whole file's tokens, so run once per file rather than per changed element.
    override fun runForWholeFile(): Boolean = true

    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        if (file !is InkoFile) return null
        val virtualFile = file.virtualFile ?: return null
        // The standard library and dependencies (External Libraries) aren't ours to restyle.
        if (ProjectFileIndex.getInstance(file.project).isInLibrary(virtualFile)) return null
        val problems = mutableListOf<ProblemDescriptor>()
        check(file) { range, message, fix ->
            // Anchored to elements, not offsets, so fixes stay correct after earlier fixes edit the file.
            val start = file.findElementAt(range.startOffset) ?: return@check
            val end = file.findElementAt(range.endOffset - 1) ?: return@check
            // A problem spanning tokens is anchored to their common parent (the file) when the IDE checks
            // suppression, so check `# noinspection` here, at the flagged line.
            if (SUPPRESSOR.isSuppressedFor(start, shortName)) return@check
            problems += manager.createProblemDescriptor(
                start, end, message, ProblemHighlightType.GENERIC_ERROR_OR_WARNING, isOnTheFly, *listOfNotNull(fix).toTypedArray(),
            )
        }
        return problems.toTypedArray()
    }

    protected abstract fun check(file: InkoFile, report: (TextRange, String, LocalQuickFix?) -> Unit)

    private companion object {
        val SUPPRESSOR = InkoInspectionSuppressor()
    }
}

/** What each declaration in a file declares, worked out once per file. */
private class InkoDeclarationKinds(file: InkoFile) {
    enum class Kind { TYPE, CONSTRUCTOR, CONSTANT, METHOD, FIELD, LOCAL }

    class Entry(val declaration: InkoDeclaration, val kind: Kind, val node: InkoOutline.Node?, val traitImpl: Boolean, val extern: Boolean)

    val entries: List<Entry>

    init {
        val text = file.text
        val outline = InkoOutline.of(text)
        val nodes = outline.all.associateBy { it.nameOffset }
        val locals = InkoLocals.declarations(text).map { it.offset }.toSet()
        entries = PsiTreeUtil.findChildrenOfType(file, InkoDeclaration::class.java).mapNotNull { declaration ->
            val offset = declaration.textRange.startOffset
            val node = nodes[offset]
            val path = outline.enclosing(offset)
            val parent = path.getOrNull(path.size - 2)
            val traitImpl = parent?.kind == InkoOutline.Kind.IMPL && outline.implTrait(parent) != null
            val modifiers = node?.let { text.substring(it.range.startOffset, it.nameOffset) }.orEmpty()
            val extern = Regex("\\bextern\\b").containsMatchIn(modifiers) ||
                (parent != null && Regex("\\bextern\\b").containsMatchIn(text.substring(parent.range.startOffset, parent.nameOffset)))
            val kind = when {
                node == null && offset in locals -> Kind.LOCAL
                node == null -> Kind.CONSTRUCTOR
                else -> when (node.kind) {
                    InkoOutline.Kind.TYPE, InkoOutline.Kind.TRAIT -> Kind.TYPE
                    InkoOutline.Kind.CONSTANT -> Kind.CONSTANT
                    InkoOutline.Kind.METHOD -> Kind.METHOD
                    InkoOutline.Kind.FIELD -> Kind.FIELD
                    InkoOutline.Kind.IMPL -> return@mapNotNull null
                }
            }
            Entry(declaration, kind, node, traitImpl, extern)
        }
    }
}

/** Names follow the style guide: PascalCase types, snake_case methods and variables, SCREAMING_SNAKE_CASE constants. */
class InkoNamingInspection : InkoStyleInspection() {
    override fun check(file: InkoFile, report: (TextRange, String, LocalQuickFix?) -> Unit) {
        for (entry in InkoDeclarationKinds(file).entries) {
            // Names here are dictated by the trait or by C, not chosen.
            if (entry.traitImpl || entry.extern) continue
            val name = entry.declaration.name
            val (expected, style) = when (entry.kind) {
                InkoDeclarationKinds.Kind.TYPE, InkoDeclarationKinds.Kind.CONSTRUCTOR -> InkoNames.pascal(name) to "PascalCase"
                InkoDeclarationKinds.Kind.CONSTANT -> InkoNames.screaming(name) to "SCREAMING_SNAKE_CASE"
                else -> InkoNames.snake(name) to "snake_case"
            }
            if (expected == name) continue
            val what = when (entry.kind) {
                InkoDeclarationKinds.Kind.TYPE -> "Type"
                InkoDeclarationKinds.Kind.CONSTRUCTOR -> "Constructor"
                InkoDeclarationKinds.Kind.CONSTANT -> "Constant"
                InkoDeclarationKinds.Kind.METHOD -> "Method"
                InkoDeclarationKinds.Kind.FIELD -> "Field"
                InkoDeclarationKinds.Kind.LOCAL -> "Variable"
            }
            report(entry.declaration.textRange, "$what name '$name' should be $style", InkoRenameFix(expected))
        }
    }
}

/** A method returning `Bool` ends in `?` rather than starting with `is_`, e.g. `allowed?`, not `is_allowed`. */
class InkoBooleanMethodNameInspection : InkoStyleInspection() {
    override fun check(file: InkoFile, report: (TextRange, String, LocalQuickFix?) -> Unit) {
        val text = file.text
        val outline = file.inkoOutline
        for (entry in InkoDeclarationKinds(file).entries) {
            val node = entry.node ?: continue
            if (entry.kind != InkoDeclarationKinds.Kind.METHOD || entry.traitImpl || entry.extern) continue
            val name = entry.declaration.name
            val isPrefixed = name.startsWith("is_") && name.length > 3
            val header = text.substring(node.range.startOffset, node.nameOffset)
            // `mut` and `move` methods are actions; a Bool they return reports success (like std's `insert -> Bool`).
            // So are methods of `ref` types, which change shared state without `mut` (std's `AtomicBool.load` and
            // `compare_and_swap`), and functions writing into a `mut` argument (std's `uri.decode`).
            val owner = outline.enclosing(node.nameOffset).dropLast(1).lastOrNull()
            val inRefType = owner?.kind == InkoOutline.Kind.TYPE && Regex("\\bref\\b").containsMatchIn(text.substring(owner.range.startOffset, owner.nameOffset))
            val writesArgument = InkoSignatures.at(text, node.nameOffset)?.parameters.orEmpty().any { it.type.startsWith("mut ") }
            val isAction = Regex("\\b(mut|move)\\b").containsMatchIn(header) || inRefType || writesArgument
            val needsQuestionMark = !name.endsWith("?") && !name.endsWith("=") && !isAction && InkoNames.returnsBool(text, node)
            if (!isPrefixed && !needsQuestionMark) continue
            val expected = name.removePrefix(if (isPrefixed) "is_" else "").removeSuffix("?") + "?"
            val message = if (isPrefixed) "Name a Bool method '$expected' rather than prefixing it with is_" else "A method returning Bool should end in ?: '$expected'"
            report(entry.declaration.textRange, message, InkoRenameFix(expected))
        }
    }
}

/** `slice(start: start)` names an argument after the variable passed to it; `slice(start)` says the same. */
class InkoRedundantNamedArgumentInspection : InkoStyleInspection() {
    override fun check(file: InkoFile, report: (TextRange, String, LocalQuickFix?) -> Unit) {
        val stream = InkoTokenStream(file.text)
        val tokens = stream.tokens
        tokens.forEachIndexed { open, token ->
            if (token.type != InkoTokens.LPAREN || open == 0) return@forEachIndexed
            val calleeToken = tokens[open - 1]
            if (calleeToken.type != InkoTokens.IDENTIFIER || calleeToken.end != token.start || tokens.getOrNull(open - 2)?.isKeyword("fn") == true) return@forEachIndexed
            val close = stream.match(open).takeIf { it > 0 } ?: return@forEachIndexed
            val arguments = arguments(stream, open, close)
            // Resolving the callee is the costly part, so only for calls with a `name: name` argument.
            if (arguments.none { isRedundant(tokens, it) }) return@forEachIndexed
            val callee = PsiTreeUtil.getParentOfType(file.findElementAt(calleeToken.start), InkoName::class.java, false) ?: return@forEachIndexed
            if (callee is InkoDeclaration) return@forEachIndexed
            val parameters = InkoResolver.resolveDefinitions(callee, exact = true).map { it.first }
                .filter { it.kind != InkoDefinitionKind.TYPE }
                .map { InkoCalls.parameters(it, null).map { p -> p.substringBefore(':').trim() } }
                .distinct()
                .singleOrNull() ?: return@forEachIndexed
            for ((index, argument) in arguments.withIndex()) {
                // Positional arguments can't follow named ones, so only a leading run can drop their names.
                if (arguments.take(index).any { isNamed(tokens, it) }) break
                if (!isRedundant(tokens, argument)) continue
                val label = tokens[argument[0]]
                val value = tokens[argument[2]]
                // Dropping the name only keeps the meaning when the argument is already in that parameter's position.
                if (parameters.getOrNull(index) != label.text) continue
                report(TextRange(label.start, value.end), "Redundant argument name '${label.text}:'", InkoRemoveTextFix("Remove redundant argument name", throughEnd = false))
            }
        }
    }

    // Each top-level argument between [open] and [close], as the indices of its tokens.
    private fun arguments(stream: InkoTokenStream, open: Int, close: Int): List<List<Int>> {
        val result = mutableListOf<List<Int>>()
        var current = mutableListOf<Int>()
        var i = open + 1
        while (i < close) {
            val token = stream.tokens[i]
            if (token.type == InkoTokens.COMMA) {
                result += current
                current = mutableListOf()
                i++
                continue
            }
            if (token.opens) {
                val end = stream.match(i).takeIf { it > i } ?: close
                (i..end).forEach(current::add)
                i = end + 1
                continue
            }
            current += i
            i++
        }
        if (current.isNotEmpty()) result += current
        return result
    }

    // `name: name`: a name, `:`, and the same name.
    private fun isRedundant(tokens: List<InkoToken>, argument: List<Int>): Boolean =
        argument.size == 3 && tokens[argument[1]].isOperator(":") &&
            tokens[argument[0]].type == InkoTokens.IDENTIFIER && tokens[argument[2]].type == InkoTokens.IDENTIFIER &&
            tokens[argument[0]].text == tokens[argument[2]].text

    private fun isNamed(tokens: List<InkoToken>, argument: List<Int>): Boolean =
        argument.size >= 2 && tokens[argument[0]].type == InkoTokens.IDENTIFIER && tokens[argument[1]].isOperator(":")
}

/** `foo()`: a call without arguments leaves out the parentheses. */
class InkoEmptyParenthesesInspection : InkoStyleInspection() {
    override fun check(file: InkoFile, report: (TextRange, String, LocalQuickFix?) -> Unit) {
        val text = file.text
        val stream = InkoTokenStream(text)
        val tokens = stream.tokens
        for (i in 1 until tokens.size - 1) {
            val callee = tokens[i - 1]
            val open = tokens[i]
            val close = tokens[i + 1]
            if (open.type != InkoTokens.LPAREN || close.type != InkoTokens.RPAREN || callee.end != open.start) continue
            // Only method calls: `Foo()` builds a value of a type without fields, and `fn foo()` declares.
            if (callee.type != InkoTokens.IDENTIFIER || tokens.getOrNull(i - 2)?.isKeyword("fn") == true) continue
            if (file.findElementAt(callee.start)?.parent is InkoDeclaration) continue
            // `size()` calls the method `size` even where a local `size` would shadow it.
            if (tokens.getOrNull(i - 2)?.type != InkoTokens.DOT && InkoLocals.visibleAt(text, callee.start).any { it.name == callee.text }) continue
            report(TextRange(open.start, close.end), "Unnecessary parentheses in a call without arguments", InkoRemoveTextFix("Remove the parentheses", throughEnd = true))
        }
    }
}

object InkoNames {
    private val WORD_BOUNDARY = Regex("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")

    private fun words(name: String): List<String> =
        name.split('_').flatMap { it.split(WORD_BOUNDARY) }.filter { it.isNotEmpty() }

    /** `fooBar` → `foo_bar`, keeping leading underscores and a trailing `?` or `=`. */
    fun snake(name: String): String {
        val prefix = name.takeWhile { it == '_' }
        val suffix = name.takeLastWhile { it == '?' || it == '=' }
        val core = name.removePrefix(prefix).removeSuffix(suffix)
        if (core.isEmpty()) return name
        return prefix + words(core).joinToString("_") { it.lowercase() } + suffix
    }

    /** `foo_bar` → `FooBar` (an all-caps acronym word keeps its case, like `HTTPClient`). */
    fun pascal(name: String): String = words(name).joinToString("") { word ->
        if (word.all { it.isUpperCase() || it.isDigit() } && word.length > 1 && name.none { it == '_' }) word else word.lowercase().replaceFirstChar { it.uppercase() }
    }

    /** `maxSize` → `MAX_SIZE`. */
    fun screaming(name: String): String = words(name).joinToString("_") { it.uppercase() }

    /** Whether the method at [node] declares `-> Bool` as its return type. */
    fun returnsBool(text: String, node: InkoOutline.Node): Boolean {
        val end = text.indexOf('{', node.nameOffset).let { if (it < 0 || it > node.range.endOffset) node.range.endOffset else it }
        val header = text.substring(node.nameOffset, end)
        // The return type follows the parameter list's closing parenthesis (or the name, without parameters).
        var depth = 0
        var afterParameters = header.length
        for ((index, c) in header.withIndex()) {
            if (c == '(') depth++
            if (c == ')') depth--
            if (depth == 0 && header.startsWith("->", index)) {
                afterParameters = index
                break
            }
        }
        return Regex("^->\\s*Bool\\s*$").matches(header.substring(afterParameters).trim())
    }
}

/** Renames the flagged declaration with the IDE's Rename refactoring, so its uses follow. */
class InkoRenameFix(private val newName: String) : LocalQuickFix {
    override fun getName(): String = "Rename to '$newName'"

    override fun getFamilyName(): String = "Rename to match the style guide"

    override fun startInWriteAction(): Boolean = false

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val declaration = PsiTreeUtil.getParentOfType(descriptor.startElement, InkoDeclaration::class.java, false) ?: return
        RefactoringFactory.getInstance(project).createRename(declaration, newName, false, false).run()
    }
}

/**
 * Deletes the flagged text: from its first token up to its last one (`name: `
 * in `name: name`), or [throughEnd] including the last (`()`).
 */
class InkoRemoveTextFix(private val text: String, private val throughEnd: Boolean) : LocalQuickFix {
    override fun getName(): String = text

    override fun getFamilyName(): String = text

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val file = descriptor.startElement?.containingFile ?: return
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
        val start = descriptor.startElement.textRange.startOffset
        val end = descriptor.endElement.textRange.let { if (throughEnd) it.endOffset else it.startOffset }
        document.deleteString(start, end)
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }
}

/**
 * Lets `# noinspection Id` on the line above silence an inspection there (or
 * `# noinspection ALL`), and offers to add that comment.
 */
class InkoInspectionSuppressor : InspectionSuppressor {
    override fun isSuppressedFor(element: PsiElement, toolId: String): Boolean {
        val document = element.containingFile?.viewProvider?.document ?: return false
        val line = document.getLineNumber(element.textRange.startOffset)
        if (line == 0) return false
        val above = document.charsSequence.subSequence(document.getLineStartOffset(line - 1), document.getLineEndOffset(line - 1)).trim()
        val ids = Regex("^#\\s*noinspection\\s+(.+)$").find(above)?.groupValues?.get(1)?.split(',')?.map { it.trim() } ?: return false
        return toolId in ids || "ALL" in ids
    }

    override fun getSuppressActions(element: PsiElement?, toolId: String): Array<SuppressQuickFix> = arrayOf(InkoSuppressFix(toolId))
}

class InkoSuppressFix(private val toolId: String) : SuppressQuickFix {
    override fun getName(): String = "Suppress for this line"

    override fun getFamilyName(): String = "Suppress with # noinspection"

    override fun isAvailable(project: Project, context: PsiElement): Boolean = context.containingFile is InkoFile

    override fun isSuppressAll(): Boolean = false

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = descriptor.startElement ?: return
        val document = PsiDocumentManager.getInstance(project).getDocument(element.containingFile) ?: return
        val lineStart = document.getLineStartOffset(document.getLineNumber(element.textRange.startOffset))
        val indent = document.charsSequence.subSequence(lineStart, document.textLength).takeWhile { it == ' ' }
        document.insertString(lineStart, "$indent# noinspection $toolId\n")
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }
}
