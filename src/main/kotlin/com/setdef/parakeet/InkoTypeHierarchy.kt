package com.setdef.parakeet

import com.intellij.ide.hierarchy.HierarchyBrowser
import com.intellij.ide.hierarchy.HierarchyNodeDescriptor
import com.intellij.ide.hierarchy.HierarchyProvider
import com.intellij.ide.hierarchy.HierarchyTreeStructure
import com.intellij.ide.hierarchy.TypeHierarchyBrowserBase
import com.intellij.ide.util.treeView.AlphaComparator
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ui.util.CompositeAppearance
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import javax.swing.JPanel
import javax.swing.JTree

/**
 * Inko's type hierarchy, from Cuttlebone's (from the type database): there's no inheritance, only traits. A type's
 * supertypes are the traits it implements; a trait's are the traits it requires (`trait Debug: ToString`). A
 * trait's subtypes are the traits requiring it and the types implementing it.
 */
object InkoTypeHierarchy {
    /** The declaration of the type or trait [element] declares or refers to. */
    fun declaration(element: PsiElement): InkoDeclaration? {
        val name = element as? InkoName ?: PsiTreeUtil.getParentOfType(element, InkoName::class.java, false) ?: return null
        val declaration = name as? InkoDeclaration ?: InkoResolver.resolve(name).filterIsInstance<InkoDeclaration>().firstOrNull() ?: return null
        return declaration.takeIf { InkoDeclarations.kindOf(it) in setOf("type", "trait") }
    }

    fun related(element: PsiElement, supertypes: Boolean): List<InkoDeclaration> {
        val declaration = declaration(element) ?: return emptyList()
        val queries = CuttleboneQueries.getInstance(element.project)
        val file = declaration.containingFile
        val offset = declaration.textRange.startOffset
        val answer = if (supertypes) queries.supertypes(file, offset) else queries.subtypes(file, offset)
        return answer?.value.orEmpty().mapNotNull { queries.target(it) as? InkoDeclaration }.distinct()
    }

    fun isTrait(element: PsiElement): Boolean = declaration(element)?.let(InkoDeclarations::kindOf) == "trait"

    /** The module [element] is declared in. */
    fun module(element: PsiElement): String? = Inko.pathOf(element.containingFile?.virtualFile)?.let(Inko::moduleName)
}

class InkoHierarchyNodeDescriptor(project: Project, parent: NodeDescriptor<*>?, element: PsiElement, isBase: Boolean) :
    HierarchyNodeDescriptor(project, parent, element, isBase) {
    override fun update(): Boolean {
        val changed = super.update()
        val element = psiElement ?: return changed
        val text = CompositeAppearance()
        text.ending.addText(element.text)
        InkoTypeHierarchy.module(element)?.let { text.ending.addComment(it) }
        myHighlightedText = text
        icon = (if (InkoTypeHierarchy.isTrait(element)) InkoOutline.Kind.TRAIT else InkoOutline.Kind.TYPE).icon
        return changed
    }
}

/** One of the three views: the base and everything under it, its supertypes, or its subtypes. */
class InkoTypeHierarchyTreeStructure(project: Project, base: PsiElement, private val supertypes: Boolean) :
    HierarchyTreeStructure(project, InkoHierarchyNodeDescriptor(project, null, base, true)) {
    override fun buildChildren(descriptor: HierarchyNodeDescriptor): Array<Any> {
        val element = descriptor.psiElement ?: return emptyArray()
        // Stop at cycles (a trait requiring itself, indirectly).
        val seen = generateSequence(descriptor) { it.parentDescriptor as? HierarchyNodeDescriptor }.mapNotNull { it.psiElement }.toSet()
        return InkoTypeHierarchy.related(element, supertypes)
            .filter { it !in seen }
            .map { InkoHierarchyNodeDescriptor(myProject, descriptor, it, false) }
            .toTypedArray()
    }
}

/** Navigate | Type Hierarchy (⌃H / Ctrl+H) for Inko types and traits. */
class InkoTypeHierarchyBrowser(project: Project, element: PsiElement) : TypeHierarchyBrowserBase(project, element) {
    override fun isInterface(element: PsiElement): Boolean = InkoTypeHierarchy.isTrait(element)

    override fun canBeDeleted(element: PsiElement): Boolean = false

    override fun getQualifiedName(element: PsiElement): String =
        listOfNotNull(InkoTypeHierarchy.module(element), element.text).joinToString(".")

    override fun getElementFromDescriptor(descriptor: HierarchyNodeDescriptor): PsiElement? = descriptor.psiElement

    override fun createTrees(trees: MutableMap<in String, in JTree>) =
        createTreeAndSetupCommonActions(trees, IdeActions.GROUP_TYPE_HIERARCHY_POPUP)

    override fun createLegendPanel(): JPanel? = null

    override fun isApplicableElement(element: PsiElement): Boolean = InkoTypeHierarchy.declaration(element) != null

    override fun createHierarchyTreeStructure(type: String, element: PsiElement): HierarchyTreeStructure? = when (type) {
        getSupertypesHierarchyType() -> InkoTypeHierarchyTreeStructure(myProject, element, supertypes = true)
        // Without inheritance, a type's hierarchy is what's under it: a trait's subtypes.
        getSubtypesHierarchyType(), getTypeHierarchyType() -> InkoTypeHierarchyTreeStructure(myProject, element, supertypes = false)
        else -> null
    }

    override fun getComparator(): Comparator<NodeDescriptor<*>> = AlphaComparator.getInstance()
}

class InkoTypeHierarchyProvider : HierarchyProvider {
    override fun getTarget(dataContext: DataContext): PsiElement? {
        val editor = CommonDataKeys.EDITOR.getData(dataContext) ?: return null
        val file = CommonDataKeys.PSI_FILE.getData(dataContext) as? InkoFile ?: return null
        val name = PsiTreeUtil.getParentOfType(file.findElementAt(editor.caretModel.offset), InkoName::class.java, false) ?: return null
        return InkoTypeHierarchy.declaration(name)
    }

    override fun createHierarchyBrowser(target: PsiElement): HierarchyBrowser = InkoTypeHierarchyBrowser(target.project, target)

    // Traits open on what implements them; types on the traits they implement.
    override fun browserActivated(hierarchyBrowser: HierarchyBrowser) {
        val browser = hierarchyBrowser as InkoTypeHierarchyBrowser
        browser.changeView(if (browser.isInterface) TypeHierarchyBrowserBase.getSubtypesHierarchyType() else TypeHierarchyBrowserBase.getSupertypesHierarchyType())
    }
}
