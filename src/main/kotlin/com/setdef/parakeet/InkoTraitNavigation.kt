package com.setdef.parakeet

import com.intellij.codeInsight.CodeInsightActionHandler
import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.navigation.PsiTargetNavigator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.util.Processor

/** Go to Implementation and Go to Super Method, from the same trait links as the gutter icons. */
object InkoTraitNavigation {
    /**
     * Implementations of the trait or trait method [declaration] names. Looked up
     * in every Inko project's index, so a standard-library trait like `ToString`
     * finds the project's own implementations.
     */
    fun implementations(declaration: InkoDeclaration): List<PsiElement> {
        val file = declaration.containingFile ?: return emptyList()
        val path = Inko.pathOf(file.virtualFile) ?: return emptyList()
        val index = InkoDocIndex.getInstance(file.project)
        val roots = InkoProjectRoots.find(file.project).ifEmpty { listOf(Inko.projectRoot(path)) }
        val token = declaration.firstChild ?: return emptyList()
        return roots.mapNotNull { index.symbols(it) }
            .mapNotNull { InkoTraitLinks.of(token, it) }
            .filter { it.toImplementations }
            .flatMap { it.targets }
            .distinct()
            .mapNotNull { InkoResolver.toPsi(file, it) }
            .distinct()
    }

    /**
     * What the declaration around [offset] implements: an impl method's trait
     * method, an impl's trait, or the traits a type implements.
     */
    fun supers(file: PsiFile, offset: Int): List<PsiElement> {
        val outline = file.inkoOutline
        val node = outline.enclosing(offset).lastOrNull { it.kind != InkoOutline.Kind.FIELD } ?: return emptyList()
        if (node.kind !in setOf(InkoOutline.Kind.METHOD, InkoOutline.Kind.IMPL, InkoOutline.Kind.TYPE)) return emptyList()
        val token = file.findElementAt(node.nameOffset) ?: return emptyList()
        val link = InkoTraitLinks.of(token)?.takeIf { !it.toImplementations } ?: return emptyList()
        return link.targets.mapNotNull { InkoResolver.toPsi(file, it) }.distinct()
    }
}

/** Go to Implementation (⌥⌘B / Ctrl+Alt+B) for traits and their methods. */
class InkoImplementationsSearch : QueryExecutorBase<PsiElement, DefinitionsScopedSearch.SearchParameters>(true) {
    override fun processQuery(parameters: DefinitionsScopedSearch.SearchParameters, consumer: Processor<in PsiElement>) {
        val declaration = parameters.element as? InkoDeclaration ?: return
        for (implementation in InkoTraitNavigation.implementations(declaration)) {
            if (!consumer.process(implementation)) return
        }
    }
}

/** Go to Super Method (⌘U / Ctrl+U): from an impl's method or the impl to the trait, or from a type to its traits. */
class InkoGotoSuperHandler : CodeInsightActionHandler {
    override fun startInWriteAction(): Boolean = false

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        val targets = InkoTraitNavigation.supers(file, editor.caretModel.offset)
        when {
            targets.isEmpty() -> if (!ApplicationManager.getApplication().isUnitTestMode) {
                HintManager.getInstance().showErrorHint(editor, "No trait or trait method to go to")
            }
            targets.size == 1 -> (targets.single() as? Navigatable)?.navigate(true)
            else -> PsiTargetNavigator(targets.toTypedArray()).navigate(editor, "Choose Trait")
        }
    }
}
