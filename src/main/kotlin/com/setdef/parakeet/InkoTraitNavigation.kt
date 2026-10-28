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
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor

/** Go to Implementation and Go to Super Method, from the same trait links as the gutter icons. */
object InkoTraitNavigation {
    /**
     * What implements the trait or trait method [declaration] declares: Cuttlebone's answer, from the type
     * database of the projects that can use it (a standard-library trait like `ToString` finds the project's
     * implementations), complete or none. Null without its answer.
     */
    fun implementations(declaration: InkoDeclaration): List<PsiElement>? {
        val queries = CuttleboneQueries.getInstance(declaration.project)
        val answer = queries.implementations(declaration.containingFile, declaration.textRange.startOffset)?.takeIf { !it.editing } ?: return null
        return answer.value.orEmpty().mapNotNull(queries::target).distinct()
    }

    /**
     * What the declaration around [offset] implements: an impl method's trait method, an impl's trait, or the
     * traits a type implements. Fresh answers only where [fresh] (Rename changes code from them).
     */
    fun supers(file: PsiFile, offset: Int, fresh: Boolean = false): List<PsiElement> {
        val outline = file.inkoOutline
        val node = outline.enclosing(offset).lastOrNull { it.kind != InkoOutline.Kind.FIELD } ?: return emptyList()
        if (node.kind !in setOf(InkoOutline.Kind.METHOD, InkoOutline.Kind.IMPL, InkoOutline.Kind.TYPE)) return emptyList()
        val token = file.findElementAt(node.nameOffset) ?: return emptyList()
        return InkoTraitLinks.of(token, fresh)?.takeIf { !it.toImplementations }?.targets.orEmpty()
    }
}

/** Go to Implementation (⌥⌘B / Ctrl+Alt+B) for traits and their methods. */
class InkoImplementationsSearch : QueryExecutorBase<PsiElement, DefinitionsScopedSearch.SearchParameters>(true) {
    override fun processQuery(parameters: DefinitionsScopedSearch.SearchParameters, consumer: Processor<in PsiElement>) {
        val declaration = parameters.element as? InkoDeclaration ?: return
        for (implementation in InkoTraitNavigation.implementations(declaration).orEmpty()) {
            if (!consumer.process(implementation)) return
        }
    }
}

/** Go to Super Method (⌘U / Ctrl+U): from an impl's method or the impl to the trait, or from a type to its traits. */
class InkoGotoSuperHandler : CodeInsightActionHandler {
    override fun startInWriteAction(): Boolean = false

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        val targets = CuttleboneQueries.offTheEdt(project, "Finding Traits") { InkoTraitNavigation.supers(file, editor.caretModel.offset) }
        when {
            targets.isEmpty() -> if (!ApplicationManager.getApplication().isUnitTestMode) {
                HintManager.getInstance().showErrorHint(editor, "No trait or trait method to go to")
            }
            targets.size == 1 -> (targets.single() as? Navigatable)?.navigate(true)
            else -> PsiTargetNavigator(targets.toTypedArray()).navigate(editor, "Choose Trait")
        }
    }
}
