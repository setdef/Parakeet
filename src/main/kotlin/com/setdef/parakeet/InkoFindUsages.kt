package com.setdef.parakeet

import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.lang.refactoring.NamesValidator
import com.intellij.lang.refactoring.RefactoringSupportProvider
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Condition
import com.intellij.patterns.ElementPattern
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.RefactoringActionHandler
import com.intellij.refactoring.changeSignature.ChangeSignatureHandler
import com.intellij.refactoring.rename.RenameInputValidator
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.refactoring.rename.UnresolvableCollisionUsageInfo
import com.intellij.usageView.UsageInfo
import com.intellij.usages.impl.rules.UsageType
import com.intellij.usages.impl.rules.UsageTypeProvider
import com.intellij.util.ProcessingContext
import com.intellij.util.Processor

/** Find Usages (⌥F7 / Alt+F7) for Inko declarations and modules. */
class InkoFindUsagesProvider : FindUsagesProvider {
    // A new scanner each time: lexers aren't thread-safe.
    override fun getWordsScanner(): WordsScanner =
        DefaultWordsScanner(InkoLexer(), InkoTokens.NAMES, InkoTokens.COMMENTS, InkoTokens.STRINGS)

    override fun canFindUsagesFor(element: PsiElement): Boolean = (element is InkoDeclaration || element is InkoFile) && Cuttlebone.canSearch(element)

    override fun getHelpId(element: PsiElement): String? = null

    override fun getType(element: PsiElement): String = when (element) {
        is InkoDeclaration -> InkoDeclarations.kindOf(element)
        is InkoFile -> "module"
        else -> ""
    }

    override fun getDescriptiveName(element: PsiElement): String = (element as? PsiNamedElement)?.name.orEmpty()

    override fun getNodeText(element: PsiElement, useFullName: Boolean): String = getDescriptiveName(element)
}

/** Finds a module's uses in import paths, which name it without its `.inko`. */
class InkoModuleReferencesSearcher : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {
    override fun processQuery(parameters: ReferencesSearch.SearchParameters, consumer: Processor<in PsiReference>) {
        val file = parameters.elementToSearch as? InkoFile ?: return
        val module = file.virtualFile?.nameWithoutExtension ?: return
        parameters.optimizer.searchWord(module, parameters.effectiveSearchScope, UsageSearchContext.IN_CODE, true, file)
    }
}

/**
 * Finds a setter's calls: `fn value=` is called as `recv.value = x`, whose
 * name token is `value` (with any spacing before the `=`), so the word index
 * is searched for `value`, and each occurrence checked to resolve to the setter.
 */
class InkoSetterReferencesSearcher : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {
    override fun processQuery(parameters: ReferencesSearch.SearchParameters, consumer: Processor<in PsiReference>) {
        val setter = parameters.elementToSearch as? InkoDeclaration ?: return
        val name = setter.name.takeIf { it.endsWith("=") && it.length > 1 } ?: return
        parameters.optimizer.searchWord(name.removeSuffix("="), parameters.effectiveSearchScope, UsageSearchContext.IN_CODE, true, setter)
    }
}

/**
 * Finds a declaration's uses under another name: in a module that imports it as `Lexer as LexerTrait`, its
 * uses are spelled `LexerTrait`, so the word index is searched for the alias there too, and each occurrence
 * checked to resolve to the declaration. Rename leaves those alone ([InkoReference.handleElementRename]).
 */
class InkoAliasReferencesSearcher : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {
    override fun processQuery(parameters: ReferencesSearch.SearchParameters, consumer: Processor<in PsiReference>) {
        val declaration = parameters.elementToSearch as? InkoDeclaration ?: return
        if (declaration.isLocal) return
        val name = declaration.name
        val scope = parameters.effectiveSearchScope as? com.intellij.psi.search.GlobalSearchScope ?: return
        val manager = PsiManager.getInstance(declaration.project)
        com.intellij.psi.search.PsiSearchHelper.getInstance(declaration.project)
            .processCandidateFilesForText(scope, UsageSearchContext.IN_CODE, true, name) { file ->
                val psi = manager.findFile(file) as? InkoFile ?: return@processCandidateFilesForText true
                for (import in InkoImports.parse(psi.text).statements) {
                    for ((alias, original) in import.symbols) {
                        if (original == name && alias != name) {
                            parameters.optimizer.searchWord(alias, com.intellij.psi.search.LocalSearchScope(psi), UsageSearchContext.IN_CODE, true, declaration)
                        }
                    }
                }
                true
            }
    }
}

/**
 * Marks the usages Find Usages found without the compiler's answer, in code Cuttlebone can't check (it
 * doesn't parse there, or a declaration has an error), by Parakeet's own reading of the code, so they show
 * apart (like RubyMine's and PyCharm's dynamic usages). Wherever Cuttlebone answers there are none.
 */
class InkoUsageTypeProvider : UsageTypeProvider {
    override fun getUsageType(element: PsiElement): UsageType? {
        val name = PsiTreeUtil.getParentOfType(element, InkoName::class.java, false) ?: return null
        return if (InkoSearch.isPossible(name)) POSSIBLE else null
    }

    companion object {
        val POSSIBLE = UsageType { "Possible usage (Cuttlebone can't check the code there)" }
    }
}

/**
 * A parameter of a trait's method, or of a method implementing one: Inko requires an implementation's parameter
 * names to be the trait's (calls can name them), so renaming one alone is a conflict.
 */
class InkoTraitParameterRenameProcessor : RenamePsiElementProcessor() {
    override fun canProcessElement(element: PsiElement): Boolean = element is InkoDeclaration && traitMethod(element) != null

    override fun findExistingNameConflicts(element: PsiElement, newName: String, conflicts: com.intellij.util.containers.MultiMap<PsiElement, String>) {
        val parameter = element as? InkoDeclaration ?: return
        val (method, trait) = traitMethod(parameter) ?: return
        val what = if (trait == null) "a trait's method" else "which implements $trait's method"
        conflicts.putValue(parameter, "`${parameter.name}` is a parameter of `$method`, $what: Inko requires its parameter names, so it can't be renamed here alone.")
    }

    /** The method [parameter] is a parameter of, and the trait it implements (null in the trait itself), if it's a trait's method. */
    private fun traitMethod(parameter: InkoDeclaration): Pair<String, String?>? {
        if (!parameter.isLocal) return null
        val file = parameter.containingFile
        val offset = parameter.textRange.startOffset
        val outline = file.inkoOutline
        val path = outline.enclosing(offset)
        val method = path.lastOrNull { it.kind == InkoOutline.Kind.METHOD } ?: return null
        val signature = InkoSignatures.at(file.text, method.nameOffset) ?: return null
        if (!signature.headerRange.contains(offset) || signature.parameters.none { it.name == parameter.name }) return null
        val owner = path.getOrNull(path.indexOf(method) - 1) ?: return null
        return when {
            owner.kind == InkoOutline.Kind.TRAIT -> method.label to null
            owner.kind == InkoOutline.Kind.IMPL && outline.implTrait(owner) != null -> method.label to outline.implTrait(owner)
            else -> null
        }
    }
}

/** A C function (`fn extern`): its name is the C library's, which the linker looks for, so renaming it is a conflict. */
class InkoExternRenameProcessor : RenamePsiElementProcessor() {
    override fun canProcessElement(element: PsiElement): Boolean = element is InkoDeclaration && InkoDeclarations.isExtern(element)

    override fun findExistingNameConflicts(element: PsiElement, newName: String, conflicts: com.intellij.util.containers.MultiMap<PsiElement, String>) {
        val function = element as? InkoDeclaration ?: return
        conflicts.putValue(function, "`${function.name}` is a C function (`fn extern`), so its name is the one the C library gives it.")
    }
}

/** Which names Inko accepts: not keywords, and a single name token. */
class InkoNamesValidator : NamesValidator {
    override fun isKeyword(name: String, project: Project?): Boolean = name in InkoTokens.KEYWORDS

    override fun isIdentifier(name: String, project: Project?): Boolean {
        if (isKeyword(name, project)) return false
        val lexer = InkoLexer()
        lexer.start(name, 0, name.length, 0)
        return lexer.tokenType in InkoTokens.NAMES && lexer.tokenEnd == name.length
    }
}

/** A renamed declaration keeps its kind of name: types stay Capitalized, methods and locals lowercase. */
class InkoRenameInputValidator : RenameInputValidator {
    override fun getPattern(): ElementPattern<out PsiElement> = PlatformPatterns.psiElement(InkoDeclaration::class.java)

    override fun isInputValid(newName: String, element: PsiElement, context: ProcessingContext): Boolean {
        val declaration = element as? InkoDeclaration ?: return false
        val name = if (declaration.tokenType == InkoTokens.FIELD) newName.removePrefix("@") else newName
        if (name in InkoTokens.KEYWORDS) return false
        return when (declaration.tokenType) {
            InkoTokens.CONSTANT -> Regex("[A-Z][A-Za-z0-9_]*").matches(name)
            InkoTokens.FIELD -> Regex("[a-z_][a-z0-9_]*").matches(name)
            // A setter stays a setter: `value=` becomes `other=`.
            else -> if (declaration.name.endsWith("=")) Regex("[a-z_][A-Za-z0-9_]*=").matches(name) else Regex("[a-z_][A-Za-z0-9_]*\\??").matches(name)
        }
    }
}

/**
 * Renaming a trait's method renames the methods implementing it, and renaming one of those renames the
 * trait's method and the others: otherwise they'd no longer implement it. Calls are found by the compiler's
 * answers; one it can't check (in code that doesn't type check) isn't renamed, and the conflicts say where.
 *
 * Renaming a getter (`fn value`) or setter (`fn mut value=`) offers to rename the other beside it too, as
 * IntelliJ does for Java's: `c.value += 1` calls both. In one rename, every usage is found before anything
 * changes, from Cuttlebone's fresh answers; renaming them one after the other, the second would search code
 * the first left not type checking.
 */
class InkoInstanceMethodRenameProcessor : RenamePsiElementProcessor() {
    override fun canProcessElement(element: PsiElement): Boolean = element is InkoDeclaration && InkoDeclarations.isInstanceMethod(element)

    override fun prepareRenaming(element: PsiElement, newName: String, allRenames: MutableMap<PsiElement, String>) {
        val method = element as? InkoDeclaration ?: return
        renameWithTraitMethods(method, newName, allRenames)
        val other = otherAccessor(method) ?: return
        if (allRenames.keys.any { method.manager.areElementsEquivalent(it, other) } || InkoRenameVeto().value(other)) return
        val otherName = newName.removeSuffix("=").let { if (other.name.endsWith("=")) "$it=" else it }
        // Only where a dialog can be shown: a rename run from elsewhere renames what it was asked to.
        if (!com.intellij.openapi.application.ApplicationManager.getApplication().isDispatchThread) return
        val kind = if (other.name.endsWith("=")) "setter" else "getter"
        val answer = com.intellij.openapi.ui.Messages.showYesNoDialog(
            method.project,
            "Rename the $kind `${other.name}` to `$otherName` too? A compound assignment like `x.${method.name.removeSuffix("=")} += 1` calls both.",
            "Rename Accessors",
            com.intellij.openapi.ui.Messages.getQuestionIcon(),
        )
        if (answer == com.intellij.openapi.ui.Messages.YES) renameWithTraitMethods(other, otherName, allRenames)
    }

    /** The setter beside a getter (`value=` for `value`), or the getter beside a setter, in the same type or `impl`. */
    private fun otherAccessor(method: InkoDeclaration): InkoDeclaration? {
        val file = method.containingFile as? InkoFile ?: return null
        val offset = method.textRange.startOffset
        val path = InkoOutline.of(file.text).enclosing(offset)
        val index = path.indexOfLast { it.nameOffset == offset }.takeIf { it > 0 } ?: return null
        val wanted = if (method.name.endsWith("=")) method.name.removeSuffix("=") else "${method.name}="
        val sibling = path[index - 1].children.firstOrNull { it.kind == InkoOutline.Kind.METHOD && it.label == wanted } ?: return null
        val other = PsiTreeUtil.getParentOfType(file.findElementAt(sibling.nameOffset), InkoDeclaration::class.java, false) ?: return null
        return other.takeIf { it.name == wanted && InkoDeclarations.isInstanceMethod(it) }
    }

    /**
     * The methods renamed with [method]: the trait's method it implements (by Cuttlebone's fresh declaration), or
     * [method], and every method implementing that (its fresh implementations), and what Cuttlebone had no answer
     * for, if anything (for the conflicts).
     */
    private class TraitMethods(val trait: List<InkoDeclaration>, val all: List<PsiElement>, val unanswered: String?)

    private fun traitMethods(method: InkoDeclaration): TraitMethods = CuttleboneQueries.offTheEdt(method.project, "Finding Trait Methods") {
        val file = method.containingFile
        val offset = method.textRange.startOffset
        val outline = file.inkoOutline
        val container = outline.enclosing(offset).dropLast(1).lastOrNull()
        val queries = CuttleboneQueries.getInstance(method.project)
        val trait = if (container?.kind == InkoOutline.Kind.IMPL && outline.implTrait(container) != null) {
            val answer = queries.declaration(file, offset)?.takeIf { !it.editing }
                ?: return@offTheEdt TraitMethods(emptyList(), listOf(method), "which trait's method `${method.name}` implements")
            answer.value.orEmpty().mapNotNull(queries::target).filterIsInstance<InkoDeclaration>().filter { it != method }
        } else emptyList()
        val all = mutableListOf<PsiElement>()
        for (traitMethod in trait.ifEmpty { listOf(method) }) {
            all += traitMethod
            all += InkoTraitNavigation.implementations(traitMethod)
                ?: return@offTheEdt TraitMethods(trait, all + method, "the methods implementing `${traitMethod.name}`")
        }
        TraitMethods(trait, (all + method).distinct(), null)
    }

    private fun renameWithTraitMethods(method: InkoDeclaration, newName: String, allRenames: MutableMap<PsiElement, String>) {
        for (other in traitMethods(method).all) {
            if (other == method || !InkoRenameVeto().value(other)) allRenames[other] = newName
        }
    }

    override fun findExistingNameConflicts(element: PsiElement, newName: String, conflicts: com.intellij.util.containers.MultiMap<PsiElement, String>) {
        val method = element as? InkoDeclaration ?: return
        val related = traitMethods(method)
        related.unanswered?.let { conflicts.putValue(method, "Cuttlebone has no answer for $it (the project doesn't type check there), so they aren't renamed with it.") }
        // A method implementing a trait's method from outside the project, which can't be renamed with it.
        for (traitMethod in related.trait) {
            if (InkoRenameVeto().value(traitMethod)) {
                conflicts.putValue(method, "`${method.name}` implements a trait's method from outside the project, which can't be renamed, so it would no longer implement it.")
            }
        }
        InkoRenameConflicts.add(method, conflicts)
    }

    /**
     * A compound assignment (`c.value += 1`) calls the getter `value` and the setter `value=`, and renaming one
     * renames it, so unless the other is renamed too, it would call one that doesn't exist.
     */
    override fun findCollisions(element: PsiElement, newName: String, allRenames: MutableMap<out PsiElement, String>, result: MutableList<UsageInfo>) {
        val accessor = element as? InkoDeclaration ?: return
        fun PsiElement.isAnyOf(elements: Collection<PsiElement>) = elements.any { manager.areElementsEquivalent(this, it) }
        for (usage in result.toList()) {
            val name = usage.element as? InkoName ?: continue
            val setters = InkoResolver.resolve(name)
            val getters = InkoResolver.getters(name, setters)
            val others = when {
                getters.isEmpty() -> continue
                accessor.isAnyOf(setters) -> getters
                accessor.isAnyOf(getters) -> setters
                else -> continue
            }
            if (others.any { it.isAnyOf(allRenames.keys) }) continue
            val other = others.first().text
            val renamed = newName.removePrefix("@").removeSuffix("=").let { if (other.endsWith("=")) "$it=" else it }
            val (verb, would) = if (other.startsWith("@")) "reads" to "read" else "calls" to "call"
            val document = name.containingFile.viewProvider.document ?: continue
            val line = document.getLineNumber(name.textRange.startOffset)
            val site = document.getText(com.intellij.openapi.util.TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line))).trim()
            result += object : UnresolvableCollisionUsageInfo(name, accessor) {
                override fun getDescription(): String =
                    "`$site` at ${name.containingFile.name}:${line + 1} also $verb `$other`, which isn't renamed, so it would $would `${if (other.startsWith("@")) "@" else ""}$renamed` instead."
            }
        }
    }
}

/** What every rename of an Inko declaration lists before renaming: the uses its search couldn't decide. */
object InkoRenameConflicts {
    fun add(declaration: InkoDeclaration, conflicts: com.intellij.util.containers.MultiMap<PsiElement, String>) {
        val unchecked = InkoSearch.unchecked(declaration)
        for (name in unchecked) {
            val line = name.containingFile.viewProvider.document?.getLineNumber(name.textRange.startOffset)?.plus(1)
            conflicts.putValue(name, "Cuttlebone can't check the code at ${name.containingFile.name}${line?.let { ":$it" }.orEmpty()} (it doesn't type check), so `${name.text}` there isn't renamed.")
        }
        for ((name, why) in InkoSearch.undecidedConflicts(declaration)) if (name !in unchecked) conflicts.putValue(name, why)
    }
}

/** Renaming the Inko declarations no other processor of Parakeet's takes, with [InkoRenameConflicts]. */
class InkoRenameProcessor : RenamePsiElementProcessor() {
    override fun canProcessElement(element: PsiElement): Boolean = element is InkoDeclaration

    override fun findExistingNameConflicts(element: PsiElement, newName: String, conflicts: com.intellij.util.containers.MultiMap<PsiElement, String>) {
        InkoRenameConflicts.add(element as? InkoDeclaration ?: return, conflicts)
    }
}

/** Declarations outside the project (the standard library, dependencies) can't be renamed, nor, without Cuttlebone, ones its uses need it for. */
class InkoRenameVeto : Condition<PsiElement> {
    override fun value(element: PsiElement): Boolean {
        if (element !is InkoDeclaration && element !is InkoFile) return false
        if (!Cuttlebone.canSearch(element)) return true
        val file = element.containingFile?.virtualFile ?: return false
        return !ProjectFileIndex.getInstance(element.project).isInContent(file)
    }
}

/** Renames locals and parameters in the editor, and other declarations in the editor with a preview option. */
class InkoRefactoringSupportProvider : RefactoringSupportProvider() {
    override fun isInplaceRenameAvailable(element: PsiElement, context: PsiElement?): Boolean =
        element is InkoDeclaration && element.isLocal

    override fun isMemberInplaceRenameAvailable(element: PsiElement, context: PsiElement?): Boolean =
        element is InkoDeclaration && !element.isLocal

    override fun getIntroduceVariableHandler(): RefactoringActionHandler = InkoIntroduceVariableHandler()

    override fun getChangeSignatureHandler(): ChangeSignatureHandler = InkoChangeSignatureHandler()

    override fun getIntroduceParameterHandler(): RefactoringActionHandler = InkoIntroduceParameterHandler()

    override fun getIntroduceConstantHandler(): RefactoringActionHandler = InkoIntroduceConstantHandler()

    override fun getExtractMethodHandler(): RefactoringActionHandler = InkoExtractMethodHandler()

    // Safe Delete (⌘⌫ / Alt+Delete) warns about usages, then deletes the whole declaration. Not parameters:
    // removing one would mean editing every call.
    override fun isSafeDeleteAvailable(element: PsiElement): Boolean =
        element is InkoDeclaration && InkoDeclarations.extent(element) != null && Cuttlebone.canSearch(element)
}

/**
 * Safe Delete of an Inko declaration, as IntelliJ does it for any element, and with the uses its search couldn't
 * decide as conflicts ([InkoSearch.undecided]): one may be a use, so it isn't safe to delete.
 */
class InkoSafeDeleteProcessor : com.intellij.refactoring.safeDelete.SafeDeleteProcessorDelegate {
    override fun handlesElement(element: PsiElement): Boolean = element is InkoDeclaration

    override fun findUsages(element: PsiElement, allElementsToDelete: Array<out PsiElement>, result: MutableList<in UsageInfo>): com.intellij.refactoring.safeDelete.NonCodeUsageSearchInfo {
        com.intellij.refactoring.safeDelete.SafeDeleteProcessor.findGenericElementUsages(element, result, allElementsToDelete, element.useScope)
        return com.intellij.refactoring.safeDelete.NonCodeUsageSearchInfo(com.intellij.refactoring.safeDelete.SafeDeleteProcessor.getDefaultInsideDeletedCondition(allElementsToDelete), element)
    }

    override fun getElementsToSearch(element: PsiElement, allElementsToDelete: MutableCollection<out PsiElement>): Collection<PsiElement> = listOf(element)

    override fun getAdditionalElementsToDelete(element: PsiElement, allElementsToDelete: MutableCollection<out PsiElement>, askUser: Boolean): Collection<PsiElement>? = null

    override fun findConflicts(element: PsiElement, allElementsToDelete: Array<out PsiElement>): Collection<String> =
        (element as? InkoDeclaration)?.let { InkoSearch.undecidedConflicts(it).map { (_, why) -> why } }.orEmpty()

    override fun preprocessUsages(project: com.intellij.openapi.project.Project, usages: Array<out UsageInfo>): Array<UsageInfo> = arrayOf(*usages)

    override fun prepareForDeletion(element: PsiElement) {}

    override fun isToSearchInComments(element: PsiElement): Boolean = com.intellij.refactoring.RefactoringSettings.getInstance().SAFE_DELETE_SEARCH_IN_COMMENTS

    override fun setToSearchInComments(element: PsiElement, enabled: Boolean) {
        com.intellij.refactoring.RefactoringSettings.getInstance().SAFE_DELETE_SEARCH_IN_COMMENTS = enabled
    }

    override fun isToSearchForTextOccurrences(element: PsiElement): Boolean = com.intellij.refactoring.RefactoringSettings.getInstance().SAFE_DELETE_SEARCH_IN_NON_JAVA

    override fun setToSearchForTextOccurrences(element: PsiElement, enabled: Boolean) {
        com.intellij.refactoring.RefactoringSettings.getInstance().SAFE_DELETE_SEARCH_IN_NON_JAVA = enabled
    }
}
