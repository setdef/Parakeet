package com.setdef.parakeet

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.lang.Language
import com.intellij.lang.refactoring.InlineActionHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.inline.InlineOptionsDialog
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.usageView.UsageInfo
import com.intellij.usageView.UsageViewBundle
import com.intellij.usageView.UsageViewDescriptor
import com.intellij.util.containers.MultiMap
import com.setdef.parakeet.InkoDefinitionKind as Kind

/**
 * Inline Method, worked out from tokens: a call becomes the method's body,
 * with its arguments in place of its parameters. An argument that's more than
 * a name or literal, and is used more than once (or in a loop or closure), is
 * bound to a `let` first so it's still evaluated once. The body's locals are
 * renamed where they'd clash with names at the call.
 */
object InkoInlineMethod {
    class Edit(val range: TextRange, val text: String)

    /** Why the method declared at [nameOffset] can't be inlined at all, or null if it can. */
    fun unsupported(text: CharSequence, nameOffset: Int): String? = Definition.of(text, nameOffset).exceptionOrNull()?.message

    /** The method being inlined: its signature, body, and where its parameters are used. */
    private class Definition(
        val text: CharSequence,
        val stream: InkoTokenStream,
        val signature: InkoSignature,
        /** The body's `{` and `}`, as token indices. */
        val open: Int,
        val close: Int,
    ) {
        val statements: List<IntRange> = stream.statementsIn(open)

        fun localAt(i: Int): InkoLocal? = InkoLocals.visibleAt(text, stream.tokens[i].start).lastOrNull { it.name == stream.tokens[i].text }

        fun isUse(i: Int): Boolean {
            val tokens = stream.tokens
            if (tokens[i].type != InkoTokens.IDENTIFIER || tokens.getOrNull(i - 1)?.type == InkoTokens.DOT) return false
            val next = tokens.getOrNull(i + 1)
            val previous = tokens.getOrNull(i - 1)
            // A named argument's label.
            return !(next?.isOperator(":") == true && (previous?.type == InkoTokens.LPAREN || previous?.type == InkoTokens.COMMA))
        }

        /** Whether the token at [i] is inside a loop or closure in the body (so it may run more than once). */
        fun repeats(i: Int): Boolean = stream.enclosingBlocks(stream.tokens[i].start).filter { it > open }.any { block ->
            var j = block - 1
            while (j > open) {
                val token = stream.tokens[j]
                if (token.closes) { j = stream.match(j).let { if (it >= 0) it - 1 else j - 1 }; continue }
                if (token.isKeyword("fn", "loop", "while", "for")) return@any true
                if (token.type == InkoTokens.LBRACE || (j < block - 1 && stream.lineBreakBetween(j, j + 1) && !stream.continues(j + 1))) break
                j--
            }
            false
        }

        companion object {
            fun of(text: CharSequence, nameOffset: Int): Result<Definition> {
                fun fail(message: String) = Result.failure<Definition>(IllegalStateException(message))
                val signature = InkoSignatures.at(text, nameOffset) ?: return fail("Only methods with a name (not operators) can be inlined.")
                val outline = InkoOutline.of(text)
                val path = outline.enclosing(nameOffset)
                val node = path.lastOrNull { it.nameOffset == nameOffset && it.kind == InkoOutline.Kind.METHOD } ?: return fail("Put the caret on a method.")
                val owner = path.dropLast(1).lastOrNull()
                if (owner?.kind == InkoOutline.Kind.TRAIT) return fail("Trait methods can't be inlined: a call may run any implementation.")
                val modifiers = text.subSequence(node.range.startOffset, nameOffset).split(Regex("\\s+")).toSet()
                if ("async" in modifiers) return fail("`fn async` methods can't be inlined: calling one sends a message to another process.")
                if ("extern" in modifiers) return fail("External functions can't be inlined.")
                val stream = InkoTokenStream(text)
                val tokens = stream.tokens
                val open = tokens.indices.firstOrNull { tokens[it].start >= signature.headerRange.endOffset && tokens[it].type == InkoTokens.LBRACE && tokens[it].end <= node.range.endOffset }
                    ?: return fail("`${signature.name}` has no body to inline.")
                val close = stream.match(open).takeIf { it > open } ?: return fail("`${signature.name}`'s body isn't closed.")
                val definition = Definition(text, stream, signature, open, close)
                for (i in open + 1 until close) {
                    val token = tokens[i]
                    val inClosure = stream.enclosingBlocks(token.start).filter { it > open }.any { block -> closureBlock(stream, block, open) }
                    if (token.isKeyword("return", "throw", "try") && !inClosure) {
                        return fail("`${signature.name}` uses `${token.text}`, which would ${if (token.text == "try") "pass its error to" else "return from"} the method it's inlined into.")
                    }
                    val callsItself = token.text == signature.name && token.type == InkoTokens.IDENTIFIER &&
                        (tokens.getOrNull(i - 1)?.type != InkoTokens.DOT || tokens.getOrNull(i - 2)?.isKeyword("self") == true) &&
                        definition.localAt(i) == null
                    if (callsItself) return fail("`${signature.name}` calls itself, so it can't be inlined.")
                }
                return Result.success(definition)
            }

            private fun closureBlock(stream: InkoTokenStream, block: Int, after: Int): Boolean {
                var j = block - 1
                while (j > after) {
                    val token = stream.tokens[j]
                    if (token.closes) { j = stream.match(j).let { if (it >= 0) it - 1 else j - 1 }; continue }
                    if (token.isKeyword("fn")) return true
                    if (token.type == InkoTokens.LBRACE || (j < block - 1 && stream.lineBreakBetween(j, j + 1) && !stream.continues(j + 1))) return false
                    j--
                }
                return false
            }
        }
    }

    /**
     * The edits (to [callText]) that inline the call whose name is at
     * [callNameOffset], of the method whose name is at [methodNameOffset] in
     * [methodText]. With [usesSelf], the body uses `self` or fields, so only
     * calls on `self` (or bare calls) can be inlined.
     */
    fun inline(methodText: CharSequence, methodNameOffset: Int, callText: CharSequence, callNameOffset: Int, usesSelf: Boolean): Result<List<Edit>> {
        fun fail(message: String) = Result.failure<List<Edit>>(IllegalStateException(message))
        val definition = Definition.of(methodText, methodNameOffset).getOrElse { return Result.failure(it) }
        val signature = definition.signature
        val mTokens = definition.stream.tokens

        // The call and its receiver.
        val stream = InkoTokenStream(callText)
        val tokens = stream.tokens
        val nameIndex = tokens.indexOfFirst { it.start == callNameOffset }.takeIf { it >= 0 } ?: return fail("Not a call.")
        val call = InkoSignatures.callAt(callText, callNameOffset) ?: return fail("Not a call.")
        val hasReceiver = tokens.getOrNull(nameIndex - 1)?.type == InkoTokens.DOT
        val receiverStart = if (hasReceiver) InkoIntroduceVariable.chainStart(stream, nameIndex).let { start -> tokens[start] } else null
        val receiver = receiverStart?.let { callText.substring(it.start, tokens[nameIndex - 2].end) }
        val simpleReceiver = receiver == null || (tokens.indexOf(receiverStart) == nameIndex - 2 &&
            (tokens[nameIndex - 2].type in InkoTokens.NAMES || tokens[nameIndex - 2].isKeyword("self")))
        if (usesSelf && receiver != null && receiver != "self") {
            return fail("`${signature.name}` uses `self` or its fields, so it can only be inlined where it's called on `self`, not on `$receiver`.")
        }
        if (!simpleReceiver) return fail("`$receiver` would no longer be evaluated if the call were inlined.")

        // Arguments for each parameter.
        val parameters = signature.parameters
        val argumentFor = arrayOfNulls<String>(parameters.size)
        call.arguments.forEachIndexed { position, argument ->
            val index = if (argument.name == null) position else parameters.indexOfFirst { it.name == argument.name }
            if (index !in parameters.indices) return fail("The call passes an argument `${signature.name}` has no parameter for.")
            argumentFor[index] = argument.value
        }
        argumentFor.forEachIndexed { index, it -> if (it == null) return fail("The call doesn't pass `${parameters[index].name}`.") }

        // Names that would clash at the call: anything visible there, or declared later in its method.
        val callStart = receiverStart?.start ?: tokens[nameIndex].start
        val callerMethod = InkoOutline.of(callText).enclosing(callStart).lastOrNull { it.kind == InkoOutline.Kind.METHOD }
        val taken = (InkoLocals.visibleAt(callText, callStart).map { it.name } +
            InkoLocals.declarations(callText).filter { callerMethod?.range?.contains(it.offset) == true }.map { it.name }).toMutableSet()
        fun fresh(name: String): String {
            if (name !in taken) return name.also { taken += it }
            return generateSequence(2) { it + 1 }.map { "$name$it" }.first { it !in taken }.also { taken += it }
        }

        // What replaces each use of a parameter or body local, and `let`s binding arguments that can't be repeated.
        val parameterDeclarations = InkoLocals.declarations(methodText).filter { signature.headerRange.contains(it.offset) }
        val bodyRange = TextRange(mTokens[definition.open].end, mTokens[definition.close].start)
        val bodyLocals = InkoLocals.declarations(methodText).filter { bodyRange.contains(it.offset) }
        val replacements = mutableMapOf<Int, String>() // token start in methodText -> new text
        // Called through its module's name (`bytes.space?(b)`), the module's own names are named through it too.
        if (receiver != null && InkoImports.parse(callText).moduleAliasedAs(receiver) != null) {
            val moduleNames = InkoOutline.of(methodText).roots.filter { it.kind != InkoOutline.Kind.IMPL }.map { it.label }.toSet()
            for (i in definition.open + 1 until definition.close) {
                val token = mTokens[i]
                if (token.type != InkoTokens.IDENTIFIER && token.type != InkoTokens.CONSTANT) continue
                if (token.text !in moduleNames || mTokens.getOrNull(i - 1)?.type == InkoTokens.DOT || definition.localAt(i) != null) continue
                replacements[token.start] = "$receiver.${token.text}"
            }
        }
        // `Self` is the method's type: outside it (in another type, or module), it's named.
        val selves = (definition.open + 1 until definition.close).filter { mTokens[it].type == InkoTokens.CONSTANT && mTokens[it].text == "Self" }
        if (selves.isNotEmpty()) {
            val methodOutline = InkoOutline.of(methodText)
            val owner = methodOutline.enclosing(methodNameOffset).dropLast(1).lastOrNull()
            val type = when (owner?.kind) {
                InkoOutline.Kind.TYPE -> owner.label
                InkoOutline.Kind.IMPL -> methodOutline.implTarget(owner)
                else -> null
            }
            val callOutline = InkoOutline.of(callText)
            val callOwner = callOutline.enclosing(callStart).lastOrNull { it.kind == InkoOutline.Kind.TYPE || it.kind == InkoOutline.Kind.IMPL }
            val sameFile = callText.toString() == methodText.toString()
            val inside = sameFile && type != null && callOwner != null &&
                (if (callOwner.kind == InkoOutline.Kind.TYPE) callOwner.label else callOutline.implTarget(callOwner)) == type
            if (!inside) {
                if (type == null) return fail("`${signature.name}` uses `Self`, which is its type, so it can't be inlined elsewhere.")
                val declared = methodOutline.roots.firstOrNull { it.kind == InkoOutline.Kind.TYPE && it.label == type }
                val generic = declared?.let { methodText.getOrNull(it.nameOffset + type.length) == '[' } ?: (owner?.label?.contains("$type[") == true)
                if (generic) return fail("`${signature.name}` uses `Self`, which is `$type` with its type arguments, so it can only be inlined inside `$type`.")
                // In another module, the name must be imported there already.
                if (!sameFile && InkoImports.parse(callText).symbol(type) == null) {
                    return fail("`${signature.name}` uses `Self`, which is `$type`, and the call's module doesn't import `$type`.")
                }
                selves.forEach { replacements[mTokens[it].start] = type }
            }
        }
        val bindings = mutableListOf<String>()
        for ((index, parameter) in parameters.withIndex()) {
            val declaration = parameterDeclarations.firstOrNull { it.name == parameter.name } ?: continue
            val uses = (definition.open + 1 until definition.close).filter { definition.isUse(it) && definition.localAt(it)?.offset == declaration.offset }
            val argument = argumentFor[index]!!
            val simple = isSimple(argument)
            when {
                uses.isEmpty() -> if (!simple) bindings += "let _ = $argument"
                simple || (uses.size == 1 && !definition.repeats(uses.single())) ->
                    uses.forEach { replacements[mTokens[it].start] = if (needsParentheses(argument, definition.stream, it)) "($argument)" else argument }
                else -> {
                    val name = fresh(parameter.name)
                    bindings += "let $name = $argument"
                    uses.forEach { replacements[mTokens[it].start] = name }
                }
            }
        }
        for (local in bodyLocals) {
            val name = fresh(local.name)
            if (name == local.name) continue
            replacements[local.offset] = name
            (definition.open + 1 until definition.close).filter { definition.isUse(it) && definition.localAt(it)?.offset == local.offset }
                .forEach { replacements[mTokens[it].start] = name }
        }

        // The body's statements, rewritten and re-indented for the call's place.
        val block = stream.enclosingBlock(callStart)
        val statementStart = stream.statementStart(block, callStart)
        val statementEnd = stream.statementEnd(statementStart)
        val indent = indentOf(callText, tokens[statementStart].start)
        val bodyIndent = definition.statements.firstOrNull()?.let { indentOf(methodText, mTokens[it.first].start) }.orEmpty()
        fun rewrite(range: IntRange, indent: String): String {
            val start = mTokens[range.first].start
            val end = mTokens[range.last].end
            val text = StringBuilder()
            var at = start
            for (i in range) {
                val token = mTokens[i]
                val replacement = replacements[token.start] ?: continue
                text.append(methodText, at, token.start).append(replacement)
                at = token.end
            }
            text.append(methodText, at, end)
            return text.split('\n').mapIndexed { index, line -> if (index == 0) line else indent + line.removePrefix(bodyIndent) }.joinToString("\n")
        }
        val statements = bindings + definition.statements.map { rewrite(it, indent) }
        val lastIsValue = definition.statements.lastOrNull()?.let { isValue(definition.stream, it) } == true

        val callEnd = call.range.endOffset
        val endIndex = tokens.indexOfLast { it.end <= callEnd }
        val wholeStatement = statementStart == tokens.indexOf(receiverStart ?: tokens[nameIndex]) && statementEnd == endIndex
        val before = tokens.getOrNull(tokens.indexOf(receiverStart ?: tokens[nameIndex]) - 1)
        val letValue = tokens[statementStart].isKeyword("let") && statementEnd == endIndex && before?.isOperator("=") == true
        val replace = TextRange(callStart, callEnd)
        return Result.success(
            when {
                wholeStatement && statements.isEmpty() -> {
                    val lineStart = callText.lastIndexOf('\n', callStart - 1) + 1
                    val lineEnd = callText.indexOf('\n', callEnd).let { if (it < 0) callText.length else it + 1 }
                    listOf(Edit(TextRange(lineStart, lineEnd), ""))
                }
                wholeStatement -> listOf(Edit(replace, statements.joinToString("\n$indent")))
                letValue -> {
                    if (!lastIsValue) return fail("`${signature.name}` doesn't end with a value to assign.")
                    val prefix = statements.dropLast(1)
                    listOfNotNull(
                        prefix.takeIf { it.isNotEmpty() }?.let { Edit(TextRange(tokens[statementStart].start, tokens[statementStart].start), it.joinToString("\n$indent") + "\n$indent") },
                        Edit(replace, statements.last()),
                    )
                }
                statements.size == 1 && lastIsValue -> {
                    val expression = statements.single()
                    val loose = needsParenthesesAt(stream, tokens.indexOf(receiverStart ?: tokens[nameIndex]), endIndex) && isLoose(expression)
                    listOf(Edit(replace, if (loose) "($expression)" else expression))
                }
                // In the middle of an expression, more than one statement becomes a block, whose value is its last line.
                else -> listOf(Edit(replace, "{\n" + statements.joinToString("\n") { "$indent  $it" } + "\n$indent}"))
            },
        )
    }

    /** A name, literal, `self`, or field: safe to repeat or reorder. */
    private fun isSimple(argument: String): Boolean {
        val tokens = InkoTokenStream(argument).tokens
        if (tokens.size == 2 && tokens[0].isOperator("-") && tokens[1].type == InkoTokens.NUMBER) return true
        return tokens.size == 1 && (tokens[0].type in InkoTokens.NAMES || tokens[0].type == InkoTokens.NUMBER ||
            tokens[0].type == InkoTokens.STRING || tokens[0].isKeyword("self", "true", "false", "nil"))
    }

    /** Whether [text] has an operator (or prefix) that would bind differently next to other operators. */
    private fun isLoose(text: String): Boolean {
        val tokens = InkoTokenStream(text).tokens
        var depth = 0
        tokens.forEachIndexed { index, token ->
            if (token.opens) depth++ else if (token.closes) depth--
            else if (depth == 0 && (token.isOperator() || token.isKeyword("and", "or", "as") || (index == 0 && token.isKeyword("ref", "mut", "recover", "move")))) return true
        }
        return false
    }

    // An argument replacing the parameter use at [index]: parenthesized when it's loose and its neighbors bind tightly.
    private fun needsParentheses(argument: String, stream: InkoTokenStream, index: Int): Boolean =
        isLoose(argument) && needsParenthesesAt(stream, index, index)

    /** Whether the tokens around [first]..[last] bind more tightly than a loose expression there would. */
    private fun needsParenthesesAt(stream: InkoTokenStream, first: Int, last: Int): Boolean {
        val tokens = stream.tokens
        val previous = tokens.getOrNull(first - 1)
        val beforePrevious = tokens.getOrNull(first - 2)?.takeIf { previous != null && it.end == previous.start }
        val previousBinds = previous != null && when {
            previous.isKeyword("and", "or", "as", "ref", "mut", "move", "recover") -> true
            // `==`, `!=`, `<=`, `>=` compare; a lone `=` or `:=` assigns, which doesn't bind.
            previous.isOperator("=") -> beforePrevious?.isOperator("=", "!", "<", ">") == true
            // A match arm's `->`.
            previous.isOperator(">") && beforePrevious?.isOperator("-") == true -> false
            else -> previous.isOperator()
        }
        val next = tokens.getOrNull(last + 1)
        val nextBinds = next != null && (next.isOperator() || next.isKeyword("and", "or", "as") || next.type == InkoTokens.DOT ||
            ((next.type == InkoTokens.LPAREN || next.type == InkoTokens.LBRACKET) && next.start == tokens[last].end))
        return previousBinds || nextBinds
    }

    private fun isValue(stream: InkoTokenStream, statement: IntRange): Boolean {
        val first = stream.tokens[statement.first]
        if (first.isKeyword("let", "fn", "return", "throw", "break", "next")) return false
        var depth = 0
        for (i in statement) {
            val token = stream.tokens[i]
            if (token.opens) depth++ else if (token.closes) depth--
            else if (depth == 0 && token.isOperator("=")) {
                val previous = stream.tokens.getOrNull(i - 1)
                val next = stream.tokens.getOrNull(i + 1)
                val comparison = (previous != null && previous.end == token.start && previous.isOperator("=", "!", "<", ">")) ||
                    (next != null && next.start == token.end && next.isOperator("="))
                if (!comparison) return false
            }
        }
        return true
    }

    private fun indentOf(text: CharSequence, offset: Int): String {
        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        return text.subSequence(lineStart, text.length).takeWhile { it == ' ' || it == '\t' }.toString()
    }

    /** Whether [method]'s body uses `self`: the keyword, a field, or a method called on `self` without it. */
    fun usesSelf(method: InkoDeclaration): Boolean {
        val file = method.containingFile
        val text = file.text
        val definition = Definition.of(text, method.textRange.startOffset).getOrNull() ?: return false
        val body = TextRange(definition.stream.tokens[definition.open].end, definition.stream.tokens[definition.close].start)
        if (definition.stream.tokens.any { body.contains(it.range) && (it.isKeyword("self") || it.type == InkoTokens.FIELD) }) return true
        return PsiTreeUtil.findChildrenOfType(file, InkoName::class.java)
            .filter { body.contains(it.textRange) && it.tokenType == InkoTokens.IDENTIFIER && !it.previousToken().isToken(InkoTokens.DOT) }
            .any { name -> InkoResolver.resolveDefinitions(name, exact = true).any { it.first.kind == Kind.INSTANCE_METHOD } }
    }
}

/** Inline Method: every call (or one), keeping or removing the method. */
class InkoInlineMethodProcessor(
    project: Project,
    private val method: InkoDeclaration,
    /** Inline just this call, or (null) every one. */
    private val reference: PsiElement?,
    private val keepMethod: Boolean,
) : BaseRefactoringProcessor(project) {
    private val name = method.name
    private val usesSelf = InkoInlineMethod.usesSelf(method)

    /** The type and trait, if the method is in `impl Trait for Type`: it implements the trait's method, so it stays. */
    private val implementing: Pair<String, String>? = method.containingFile.inkoOutline.let { outline ->
        val owner = outline.enclosing(method.textRange.startOffset).dropLast(1).lastOrNull()?.takeIf { it.kind == InkoOutline.Kind.IMPL }
        owner?.let { outline.implTrait(it) }?.let { trait -> outline.implTarget(owner)!! to trait }
    }

    override fun getCommandName(): String = "Inline Method"

    override fun createUsageViewDescriptor(usages: Array<out UsageInfo>): UsageViewDescriptor = object : UsageViewDescriptor {
        override fun getElements(): Array<PsiElement> = arrayOf(method)
        override fun getProcessedElementsHeader(): String = "Method to inline"
        override fun getCodeReferencesText(usagesCount: Int, filesCount: Int): String =
            "Calls to inline ${UsageViewBundle.getReferencesString(usagesCount, filesCount)}"
        override fun getCommentReferencesText(usagesCount: Int, filesCount: Int): String? = null
    }

    override fun findUsages(): Array<UsageInfo> =
        (reference?.let(::listOf) ?: ReferencesSearch.search(method, GlobalSearchScope.projectScope(myProject)).findAll().map { it.element })
            .map(::UsageInfo).toTypedArray()

    override fun isPreviewUsages(usages: Array<out UsageInfo>): Boolean =
        super.isPreviewUsages(usages) || usages.any { usage -> usage.element?.let(InkoChangeSignature::isUncertain) == true }

    private fun isImportUse(element: PsiElement): Boolean = InkoImports.parse(element.containingFile.text).at(element.textRange.startOffset) != null

    // A call through the method's module (`bytes.space?(b)`): what the body names from it is named through it too.
    private fun isThroughTheModule(element: PsiElement): Boolean {
        val dot = element.previousToken()?.takeIf { it.isToken(InkoTokens.DOT) } ?: return false
        val receiver = dot.previousToken()?.takeIf { it.isToken(InkoTokens.IDENTIFIER) } ?: return false
        return InkoImports.parse(element.containingFile.text).moduleAliasedAs(receiver.text) != null
    }

    override fun preprocessUsages(refUsages: Ref<Array<UsageInfo>>): Boolean {
        val conflicts = MultiMap<PsiElement, String>()
        val file = method.containingFile
        val (_, private) = portableNames()
        val module = Inko.pathOf(file.virtualFile)?.let(Inko::moduleName)
        var failures = 0
        for (usage in refUsages.get()) {
            val element = usage.element ?: continue
            if (isImportUse(element)) continue
            InkoInlineMethod.inline(file.text, method.textRange.startOffset, element.containingFile.text, element.textRange.startOffset, usesSelf).onFailure {
                failures++
                val line = PsiDocumentManager.getInstance(myProject).getDocument(element.containingFile)?.getLineNumber(element.textRange.startOffset)?.plus(1)
                conflicts.putValue(element, "The call in ${element.containingFile.name}${line?.let { ":$it" }.orEmpty()} can't be inlined: ${it.message}")
            }
            if (element.containingFile != file && Inko.pathOf(element.containingFile.virtualFile)?.let(Inko::moduleName) != module && !isThroughTheModule(element)) {
                private.sorted().forEach { conflicts.putValue(element, "`$it` is private to ${module ?: "its module"}, so the call in ${element.containingFile.name} can't use it once inlined.") }
            }
        }
        if (failures > 0 && !keepMethod && reference == null) conflicts.putValue(method, "`$name` will be kept, since not every call can be inlined.")
        else if (implementing != null && !keepMethod && reference == null) {
            val (type, trait) = implementing
            conflicts.putValue(method, "`$name` implements $trait's method, so it's kept: without it, $type wouldn't implement $trait.")
        }
        return showConflicts(conflicts, refUsages.get())
    }

    // What the body names that calls in other modules must import.
    private fun portableNames(): Pair<List<Pair<String, String>>, Set<String>> {
        val file = method.containingFile
        val range = file.inkoOutline.enclosing(method.textRange.startOffset).lastOrNull { it.nameOffset == method.textRange.startOffset }?.range ?: return emptyList<Pair<String, String>>() to emptySet()
        val names = PsiTreeUtil.findChildrenOfType(file, InkoName::class.java).filter { range.contains(it.textRange) && it.textRange.startOffset > method.textRange.endOffset }
        return InkoPortableNames.of(file, names)
    }

    override fun performRefactoring(usages: Array<out UsageInfo>) {
        val manager = PsiDocumentManager.getInstance(myProject)
        val methodPointer = SmartPointerManager.createPointer(method)
        val (imports, _) = portableNames()
        val methodModule = Inko.pathOf(method.containingFile.virtualFile)?.let(Inko::moduleName)
        // Later calls first in each file, so earlier ones (and nested ones) stay where they are.
        val calls = usages.mapNotNull { it.element }.filter { !isImportUse(it) }
            .sortedWith(compareBy<PsiElement>({ it.containingFile.virtualFile?.path }, { -it.textRange.startOffset }))
            .map { SmartPointerManager.createPointer(it) }
        var failed = 0
        val callFiles = mutableSetOf<PsiFile>()
        val throughTheModule = mutableSetOf<PsiFile>()
        for (pointer in calls) {
            val element = pointer.element
            val method = methodPointer.element
            if (element == null || method == null) { failed++; continue }
            // The call itself is replaced, so note its file first.
            val callFile = element.containingFile
            // Through the method's module, its own names are named through it: only what it imports is imported.
            if (isThroughTheModule(element)) throughTheModule += callFile
            val document = manager.getDocument(callFile) ?: continue
            manager.doPostponedOperationsAndUnblockDocument(document)
            val edits = InkoInlineMethod.inline(method.containingFile.text, method.textRange.startOffset, document.charsSequence, element.textRange.startOffset, usesSelf)
                .getOrElse { failed++; continue }
            for (edit in edits.sortedByDescending { it.range.startOffset }) document.replaceString(edit.range.startOffset, edit.range.endOffset, edit.text)
            manager.commitDocument(document)
            callFiles += callFile
        }
        for (file in callFiles) {
            if (Inko.pathOf(file.virtualFile)?.let(Inko::moduleName) == methodModule) continue
            val document = manager.getDocument(file) ?: continue
            for ((from, symbol) in imports) {
                if (file in throughTheModule && from == methodModule) continue
                if (InkoImports.parse(document.charsSequence).symbol(symbol) != null) continue
                InkoImportEdits.add(document, from, symbol)
            }
            manager.commitDocument(document)
        }
        if (keepMethod || reference != null || failed > 0 || implementing != null) return
        // Every call is gone: remove the method, and imports of it.
        val method = methodPointer.element ?: return
        for (usage in usages) {
            val element = usage.element?.takeIf { it.isValid && isImportUse(it) } ?: continue
            val document = manager.getDocument(element.containingFile) ?: continue
            val import = InkoImports.parse(document.charsSequence).at(element.textRange.startOffset) ?: continue
            InkoImportEdits.removeSymbol(document, import, element.text)
            manager.commitDocument(document)
        }
        methodPointer.element?.delete() ?: method.delete()
    }
}

/** The usual Inline dialog: all calls (removing or keeping the method), or just this one. */
class InkoInlineMethodDialog(project: Project, method: InkoDeclaration, private val reference: PsiElement?) :
    InlineOptionsDialog(project, true, method) {
    init {
        myInvokedOnReference = reference != null
        title = "Inline Method"
        init()
    }

    override fun getNameLabelText(): String = "Method `${(myElement as InkoDeclaration).name}`"

    override fun getInlineAllText(): String = "Inline all calls and remove the method"

    override fun getKeepTheDeclarationText(): String = "Inline all calls and keep the method"

    override fun getInlineThisText(): String = "Inline this call only and keep the method"

    override fun isInlineThis(): Boolean = reference != null

    override fun doAction() {
        val method = myElement as InkoDeclaration
        invokeRefactoring(InkoInlineMethodProcessor(project, method, if (isInlineThisOnly) reference else null, isKeepTheDeclaration || isInlineThisOnly))
    }
}

/** Inline Method (⌥⌘N / Ctrl+Alt+N) on a method or a call to it. */
class InkoInlineMethodHandler : InlineActionHandler() {
    override fun isEnabledForLanguage(language: Language): Boolean = language == InkoLanguage

    override fun canInlineElement(element: PsiElement): Boolean =
        element is InkoDeclaration && !element.isLocal && InkoCallHierarchy.isMethod(element)

    override fun inlineElement(project: Project, editor: Editor?, element: PsiElement) {
        val method = element as? InkoDeclaration ?: return
        if (!Cuttlebone.canSearch(method)) return Cuttlebone.showNeeded(project, editor, "Inline Method")
        InkoInlineMethod.unsupported(method.containingFile.text, method.textRange.startOffset)?.let { message ->
            if (editor != null) CommonRefactoringUtil.showErrorHint(project, editor, message, "Inline Method", null)
            else CommonRefactoringUtil.showErrorMessage("Inline Method", message, null, project)
            return
        }
        // Invoked on a call (not the method's own name) offers inlining just that call.
        val reference = editor?.let { TargetElementUtil.findReference(it) }?.element?.takeIf { it !is InkoDeclaration }
        if (ApplicationManager.getApplication().isUnitTestMode) {
            InkoInlineMethodProcessor(project, method, reference, keepMethod = reference != null).run()
            return
        }
        InkoInlineMethodDialog(project, method, reference).show()
    }
}
