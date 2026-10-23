package com.setdef.parakeet

import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.usageView.UsageInfo
import com.intellij.usageView.UsageViewBundle
import com.intellij.usageView.UsageViewDescriptor
import com.intellij.util.containers.MultiMap

/**
 * One parameter of a changed signature: an existing parameter ([oldIndex])
 * renamed, retyped, or moved, or a new one ([oldIndex] = -1) that every call
 * passes [defaultValue], since Inko has no default arguments.
 */
data class InkoParameterChange(val oldIndex: Int, val name: String, val type: String, val defaultValue: String = "")

class InkoSignatureChange(val name: String, val parameters: List<InkoParameterChange>, val returnType: String?)

object InkoChangeSignature {
    /** Why [declaration]'s signature can't be changed here, or null if it can. */
    fun unsupported(declaration: InkoDeclaration): String? {
        val file = declaration.containingFile
        val text = file.text
        val offset = declaration.textRange.startOffset
        val outline = file.inkoOutline
        val path = outline.enclosing(offset)
        val node = path.lastOrNull { it.nameOffset == offset }
        if (node?.kind != InkoOutline.Kind.METHOD) return "Put the caret on a method's name, or on a call to it."
        val owner = path.dropLast(1).lastOrNull()
        // Changing a trait's method means changing every implementation the same way, which isn't supported.
        if (owner?.kind == InkoOutline.Kind.TRAIT) return "Methods in traits can't be changed: every implementation would need the same change."
        if (owner?.kind == InkoOutline.Kind.IMPL && outline.implTrait(owner) != null) {
            return "`${declaration.name}` implements ${outline.implTrait(owner)}'s method, so its signature has to match the trait's."
        }
        val file0 = file.virtualFile ?: return "Only methods in saved files can be changed."
        if (!ProjectFileIndex.getInstance(declaration.project).isInContent(file0)) return "Only methods in this project can be changed."
        return if (InkoSignatures.at(text, offset) == null) "`${declaration.name}`'s signature couldn't be read." else null
    }

    /** Problems with [change] itself (names, types, values), or null. */
    fun invalid(change: InkoSignatureChange): String? {
        if (!Regex("[a-z_][A-Za-z0-9_]*[?=]?").matches(change.name) || change.name in InkoTokens.KEYWORDS) return "`${change.name}` isn't a valid method name."
        val names = mutableSetOf<String>()
        for (parameter in change.parameters) {
            if (!Regex("[a-z_][A-Za-z0-9_]*").matches(parameter.name) || parameter.name in InkoTokens.KEYWORDS) return "`${parameter.name}` isn't a valid parameter name."
            if (!names.add(parameter.name)) return "There's more than one parameter named `${parameter.name}`."
            if (parameter.type.isBlank()) return "Enter a type for `${parameter.name}`."
            if (parameter.oldIndex < 0 && parameter.defaultValue.isBlank()) {
                return "Enter the value calls pass for the new parameter `${parameter.name}` (Inko has no default arguments)."
            }
        }
        return null
    }

    /**
     * A call's new arguments: each old argument follows its parameter (by
     * position, or by name), new parameters get their value, and removed ones
     * are dropped. Named arguments stay named (with the new name), and since
     * named arguments must come last, everything after one is named too.
     */
    fun newArguments(call: InkoCallSite, old: List<InkoParameter>, change: InkoSignatureChange): Result<List<String>> {
        fun fail(message: String) = Result.failure<List<String>>(IllegalStateException(message))
        val byParameter = mutableMapOf<Int, InkoArgument>()
        call.arguments.forEachIndexed { position, argument ->
            val index = if (argument.name == null) position else old.indexOfFirst { it.name == argument.name }
            if (index !in old.indices) return fail(if (argument.name == null) "it passes more arguments than the method has parameters" else "it names `${argument.name}`, which isn't a parameter")
            byParameter[index] = argument
        }
        var named = false
        return Result.success(change.parameters.map { parameter ->
            val value = if (parameter.oldIndex >= 0) {
                val argument = byParameter[parameter.oldIndex] ?: return fail("it doesn't pass `${old[parameter.oldIndex].name}`")
                if (argument.name != null) named = true
                argument.value
            } else {
                parameter.defaultValue.trim()
            }
            if (named) "${parameter.name}: $value" else value
        })
    }

    /** A call rewritten: `name`, or `name(…)` when it has arguments (Inko leaves out empty parentheses). */
    fun renderCall(call: InkoCallSite, name: String, arguments: List<String>): String =
        name + call.typeArguments + if (arguments.isEmpty()) "" else arguments.joinToString(", ", "(", ")")

    /** Whether a use of a method might mean a same-named method on another type (the receiver's type isn't known). */
    fun isUncertain(element: PsiElement): Boolean {
        val name = PsiTreeUtil.getParentOfType(element, InkoName::class.java, false) ?: return false
        return name !is InkoDeclaration && InkoResolver.resolve(name).size > 1
    }
}

/**
 * Change Signature for an Inko method: rewrites its header, renames parameters
 * in its body, and updates every call. With [replacements] and [replacement]
 * (Introduce Parameter), also replaces those ranges in the body.
 */
class InkoChangeSignatureProcessor(
    project: Project,
    private val method: InkoDeclaration,
    private val change: InkoSignatureChange,
    private val replacements: List<TextRange> = emptyList(),
    private val replacement: String = "",
    private val commandName: String = "Change Signature",
) : BaseRefactoringProcessor(project) {
    private val signature: InkoSignature = InkoSignatures.at(method.containingFile.text, method.textRange.startOffset)
        ?: throw IllegalArgumentException("Not a method: ${method.text}")

    override fun getCommandName(): String = commandName

    override fun createUsageViewDescriptor(usages: Array<out UsageInfo>): UsageViewDescriptor = object : UsageViewDescriptor {
        override fun getElements(): Array<PsiElement> = arrayOf(method)
        override fun getProcessedElementsHeader(): String = "Method to change"
        override fun getCodeReferencesText(usagesCount: Int, filesCount: Int): String =
            "Calls to update ${UsageViewBundle.getReferencesString(usagesCount, filesCount)}"
        override fun getCommentReferencesText(usagesCount: Int, filesCount: Int): String? = null
    }

    override fun findUsages(): Array<UsageInfo> =
        ReferencesSearch.search(method, GlobalSearchScope.projectScope(myProject)).findAll()
            .map { UsageInfo(it) }
            .toTypedArray()

    // Calls whose receiver's type isn't known might be another type's method: show them so they can be excluded.
    override fun isPreviewUsages(usages: Array<out UsageInfo>): Boolean =
        super.isPreviewUsages(usages) || usages.any { usage -> usage.element?.let(InkoChangeSignature::isUncertain) == true }

    override fun preprocessUsages(refUsages: Ref<Array<UsageInfo>>): Boolean {
        val conflicts = MultiMap<PsiElement, String>()
        InkoChangeSignature.invalid(change)?.let { conflicts.putValue(method, it) }
        val file = method.containingFile

        // Parameters that are removed but still used.
        for ((index, parameter) in signature.parameters.withIndex()) {
            if (change.parameters.any { it.oldIndex == index }) continue
            val declaration = parameterDeclaration(index) ?: continue
            if (InkoUsages.inFile(declaration).any { (name, isDeclaration) -> !isDeclaration && name != declaration && replacements.none { it.contains(name.textRange) } }) {
                conflicts.putValue(declaration, "Parameter `${parameter.name}` is removed, but the method's body still uses it.")
            }
        }
        // A new or renamed parameter that a local in the body would shadow (or be shadowed by).
        val body = methodRange()
        val locals = InkoLocals.declarations(file.text).filter { body.contains(it.offset) && it.offset > signature.headerRange.endOffset }
        for (parameter in change.parameters) {
            val renamedFrom = signature.parameters.getOrNull(parameter.oldIndex)?.name
            if (parameter.name != renamedFrom && locals.any { it.name == parameter.name }) {
                conflicts.putValue(method, "The method's body already has a variable named `${parameter.name}`.")
            }
        }
        // A new method name that's already taken next to it.
        if (change.name != signature.name) {
            val siblings = file.inkoOutline.enclosing(method.textRange.startOffset).dropLast(1).lastOrNull()?.children ?: file.inkoOutline.roots
            if (siblings.any { it.kind == InkoOutline.Kind.METHOD && it.label == change.name }) {
                conflicts.putValue(method, "There's already a method named `${change.name}` here.")
            }
        }
        // Calls that can't be rewritten.
        for (usage in refUsages.get()) {
            val element = usage.element ?: continue
            if (isImportUse(element)) continue
            val text = element.containingFile.text
            val call = InkoSignatures.callAt(text, element.textRange.startOffset) ?: continue
            InkoChangeSignature.newArguments(call, signature.parameters, change).onFailure {
                val document = PsiDocumentManager.getInstance(myProject).getDocument(element.containingFile)
                val line = document?.getLineNumber(element.textRange.startOffset)?.plus(1)
                conflicts.putValue(element, "The call in ${element.containingFile.name}${line?.let { ":$it" }.orEmpty()} can't be updated: ${it.message}.")
            }
        }
        return showConflicts(conflicts, refUsages.get())
    }

    override fun performRefactoring(usages: Array<out UsageInfo>) {
        val manager = PsiDocumentManager.getInstance(myProject)
        // Within a file, later calls first: an inner call (`add(a, add(a, 0))`) starts after the outer one, and
        // rewriting it leaves the outer call's name in place.
        val pointers = usages.mapNotNull { it.element }
            .sortedWith(compareBy<PsiElement>({ it.containingFile.virtualFile?.path }, { -it.textRange.startOffset }))
            .map { SmartPointerManager.createPointer(it) }
        val file = method.containingFile
        val document = manager.getDocument(file) ?: return
        manager.doPostponedOperationsAndUnblockDocument(document)

        // The method itself: its header, renamed parameters' uses, and replaced expressions.
        val edits = mutableListOf<Pair<TextRange, String>>()
        edits += signature.headerRange to signature.render(change.name, newParameters(), change.returnType)
        replacements.forEach { edits += it to replacement }
        for (parameter in change.parameters) {
            if (parameter.oldIndex < 0) continue
            val old = signature.parameters[parameter.oldIndex]
            if (old.name == parameter.name) continue
            val declaration = parameterDeclaration(parameter.oldIndex) ?: continue
            for ((name, isDeclaration) in InkoUsages.inFile(declaration)) {
                // The declaration is part of the header, which is rewritten as a whole.
                if (isDeclaration || name == declaration || replacements.any { it.contains(name.textRange) }) continue
                edits += name.textRange to parameter.name
            }
        }
        apply(document, edits)
        manager.commitDocument(document)

        // Each call, one at a time (calls can be nested in each other's arguments).
        for (pointer in pointers) {
            val element = pointer.element ?: continue
            val name = PsiTreeUtil.getParentOfType(element, InkoName::class.java, false) ?: continue
            val callDocument = manager.getDocument(name.containingFile) ?: continue
            manager.doPostponedOperationsAndUnblockDocument(callDocument)
            if (isImportUse(name)) {
                callDocument.replaceString(name.textRange.startOffset, name.textRange.endOffset, change.name)
            } else {
                val call = InkoSignatures.callAt(callDocument.charsSequence, name.textRange.startOffset) ?: continue
                val arguments = InkoChangeSignature.newArguments(call, signature.parameters, change).getOrNull() ?: continue
                callDocument.replaceString(call.range.startOffset, call.range.endOffset, InkoChangeSignature.renderCall(call, change.name, arguments))
            }
            manager.commitDocument(callDocument)
        }
    }

    private fun newParameters(): List<InkoParameter> = change.parameters.map { parameter ->
        val prefix = signature.parameters.getOrNull(parameter.oldIndex)?.prefix.orEmpty()
        InkoParameter(parameter.name, parameter.type.trim(), prefix)
    }

    /** The name declaring the method's [index]th parameter. */
    private fun parameterDeclaration(index: Int): InkoDeclaration? {
        val name = signature.parameters.getOrNull(index)?.name ?: return null
        return PsiTreeUtil.findChildrenOfType(method.containingFile, InkoDeclaration::class.java).firstOrNull {
            it.name == name && signature.headerRange.contains(it.textRange) && it != method
        }
    }

    private fun methodRange(): TextRange =
        method.containingFile.inkoOutline.enclosing(method.textRange.startOffset).lastOrNull { it.nameOffset == method.textRange.startOffset }?.range
            ?: method.textRange

    private fun isImportUse(element: PsiElement): Boolean =
        InkoImports.parse(element.containingFile.text).at(element.textRange.startOffset) != null

    private fun apply(document: Document, edits: List<Pair<TextRange, String>>) {
        for ((range, text) in edits.sortedByDescending { it.first.startOffset }) document.replaceString(range.startOffset, range.endOffset, text)
    }
}
