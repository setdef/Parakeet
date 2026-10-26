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
        return (roots.mapNotNull { index.symbols(it) }
            .mapNotNull { InkoTraitLinks.of(token, it) }
            .filter { it.toImplementations }
            .flatMap { it.targets }
            .distinct()
            .mapNotNull { InkoResolver.toPsi(file, it) } + inTests(declaration, roots))
            .distinct()
    }

    /**
     * The methods implementing the trait method [declaration] in the projects' `test/` directories, which
     * `inko doc` doesn't document: `impl Trait for Type` blocks there, with `Trait` imported from its module.
     */
    private fun inTests(declaration: InkoDeclaration, roots: List<java.nio.file.Path>): List<PsiElement> {
        val file = declaration.containingFile
        val outline = file.inkoOutline
        val path = outline.enclosing(declaration.textRange.startOffset)
        val method = path.lastOrNull()?.takeIf { it.kind == InkoOutline.Kind.METHOD && it.nameOffset == declaration.textRange.startOffset } ?: return emptyList()
        val trait = path.getOrNull(path.size - 2)?.takeIf { it.kind == InkoOutline.Kind.TRAIT } ?: return emptyList()
        val module = Inko.pathOf(file.virtualFile)?.let(Inko::moduleName) ?: return emptyList()
        val manager = com.intellij.psi.PsiManager.getInstance(file.project)
        val tests = com.intellij.psi.search.FilenameIndex.getAllFilesByExt(file.project, Inko.EXTENSION, com.intellij.psi.search.GlobalSearchScope.projectScope(file.project))
            .filter { test -> Inko.pathOf(test)?.let { p -> roots.any { p.startsWith(it.resolve("test")) } } == true }
        return tests.flatMap { test ->
            val psi = manager.findFile(test) as? InkoFile ?: return@flatMap emptyList()
            if (!psi.text.contains("impl")) return@flatMap emptyList()
            val imports = InkoImports.parse(psi.text)
            val testOutline = psi.inkoOutline
            testOutline.roots.filter { node ->
                node.kind == InkoOutline.Kind.IMPL && testOutline.implTrait(node)?.let { imports.symbol(it) == (module to trait.label) } == true
            }.flatMap { impl -> impl.children.filter { it.kind == InkoOutline.Kind.METHOD && it.label == method.label } }
                .mapNotNull { PsiTreeUtil.getParentOfType(psi.findElementAt(it.nameOffset), InkoDeclaration::class.java, false) }
        }
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
