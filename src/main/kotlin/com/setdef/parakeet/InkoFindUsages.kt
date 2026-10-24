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
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.RefactoringActionHandler
import com.intellij.refactoring.changeSignature.ChangeSignatureHandler
import com.intellij.refactoring.rename.RenameInputValidator
import com.intellij.refactoring.rename.RenamePsiElementProcessor
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
 * Marks method calls whose receiver's type isn't known, which may mean a
 * same-named method on another type, so Find Usages and Rename's preview show
 * them apart (like RubyMine's and PyCharm's dynamic usages).
 */
class InkoUsageTypeProvider : UsageTypeProvider {
    override fun getUsageType(element: PsiElement): UsageType? {
        val name = PsiTreeUtil.getParentOfType(element, InkoName::class.java, false) ?: return null
        if (name is InkoDeclaration || !name.previousToken().isToken(InkoTokens.DOT)) return null
        // Asked of every usage found, so with Cuttlebone's cached answers only.
        return if (!CuttleboneQueries.cachedOnly { InkoResolver.resolution(name, exact = false) }.exact) POSSIBLE else null
    }

    companion object {
        val POSSIBLE = UsageType { "Possible usage (the receiver's type isn't known)" }
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
 * Renaming a method that's called on values always shows the preview, since
 * calls whose receiver type isn't known may mean a same-named method instead.
 */
class InkoInstanceMethodRenameProcessor : RenamePsiElementProcessor() {
    override fun canProcessElement(element: PsiElement): Boolean = element is InkoDeclaration && InkoDeclarations.isInstanceMethod(element)

    override fun forcesShowPreview(): Boolean = true
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
