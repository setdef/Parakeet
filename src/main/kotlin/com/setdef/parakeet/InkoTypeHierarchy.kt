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
import com.setdef.parakeet.InkoDefinitionKind as Kind

/**
 * Inko's type hierarchy (from `inko doc`): there's no inheritance, only traits.
 * A type's supertypes are the traits it implements; a trait's are the traits it
 * requires (`trait Debug: ToString`). A trait's subtypes are the types
 * implementing it and the traits requiring it.
 */
object InkoTypeHierarchy {
    /** The type or trait [element] declares or refers to. */
    fun definition(element: PsiElement): InkoDefinition? {
        val name = element as? InkoName ?: PsiTreeUtil.getParentOfType(element, InkoName::class.java, false) ?: return null
        if (name is InkoDeclaration) {
            val path = Inko.pathOf(name.containingFile.virtualFile) ?: return null
            val symbols = symbols(name) ?: return null
            return symbols.definedIn(path, name.name).firstOrNull { it.kind == Kind.TYPE || it.kind == Kind.TRAIT }
        }
        return InkoResolver.resolveDefinitions(name).map { it.first }.firstOrNull { it.kind == Kind.TYPE || it.kind == Kind.TRAIT }
    }

    private fun symbols(context: PsiElement): InkoSymbols? {
        val path = Inko.pathOf(context.containingFile?.virtualFile) ?: return null
        return InkoDocIndex.getInstance(context.project).symbols(Inko.projectRoot(path))
    }

    /** The traits a trait's signature requires: `Debug: ToString + Format[T]` gives ToString and Format. */
    fun requiredTraitNames(trait: InkoDefinition): List<String> {
        val signature = trait.signature ?: return emptyList()
        val requirements = signature.substringAfter(':', "").substringBefore('{').trim()
        if (requirements.isEmpty()) return emptyList()
        return requirements.split('+').map { it.trim().substringBefore('[').trim() }.filter { it.isNotEmpty() && it.first().isUpperCase() }
    }

    fun supertypes(symbols: InkoSymbols, definition: InkoDefinition): List<InkoDefinition> = when (definition.kind) {
        Kind.TYPE -> definition.related.flatMap { ref -> symbols.named(ref.name, Kind.TRAIT).filter { it.module == ref.module } }
        Kind.TRAIT -> requiredTraitNames(definition).flatMap { name ->
            symbols.named(name, Kind.TRAIT).let { traits -> traits.filter { it.module == definition.module }.ifEmpty { traits } }.take(1)
        }
        else -> emptyList()
    }

    fun subtypes(symbols: InkoSymbols, definition: InkoDefinition): List<InkoDefinition> {
        if (definition.kind != Kind.TRAIT) return emptyList()
        val types = definition.related.flatMap { ref -> symbols.named(ref.name, Kind.TYPE).filter { it.module == ref.module } }
        val traits = symbols.ofKind(Kind.TRAIT).filter { trait -> supertypes(symbols, trait).any { it == definition } }
        return traits + types
    }

    fun elementOf(context: PsiElement, definition: InkoDefinition): PsiElement? =
        InkoResolver.toPsi(context.containingFile, definition)

    fun related(element: PsiElement, supertypes: Boolean): List<PsiElement> {
        val definition = definition(element) ?: return emptyList()
        val symbols = symbols(element) ?: return emptyList()
        val related = if (supertypes) supertypes(symbols, definition) else subtypes(symbols, definition)
        return related.mapNotNull { elementOf(element, it) }
    }

    fun isTrait(element: PsiElement): Boolean = definition(element)?.kind == Kind.TRAIT
}

class InkoHierarchyNodeDescriptor(project: Project, parent: NodeDescriptor<*>?, element: PsiElement, isBase: Boolean) :
    HierarchyNodeDescriptor(project, parent, element, isBase) {
    override fun update(): Boolean {
        val changed = super.update()
        val element = psiElement ?: return changed
        val definition = InkoTypeHierarchy.definition(element)
        val text = CompositeAppearance()
        text.ending.addText(definition?.name ?: element.text)
        definition?.module?.let { text.ending.addComment(it) }
        myHighlightedText = text
        icon = definition?.icon
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
        InkoTypeHierarchy.definition(element)?.let { "${it.module}.${it.name}" } ?: element.text

    override fun getElementFromDescriptor(descriptor: HierarchyNodeDescriptor): PsiElement? = descriptor.psiElement

    override fun createTrees(trees: MutableMap<in String, in JTree>) =
        createTreeAndSetupCommonActions(trees, IdeActions.GROUP_TYPE_HIERARCHY_POPUP)

    override fun createLegendPanel(): JPanel? = null

    override fun isApplicableElement(element: PsiElement): Boolean = InkoTypeHierarchy.definition(element) != null

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
        if (name is InkoDeclaration) return name.takeIf { InkoTypeHierarchy.definition(it) != null }
        val definition = InkoTypeHierarchy.definition(name) ?: return null
        return InkoTypeHierarchy.elementOf(name, definition)
    }

    override fun createHierarchyBrowser(target: PsiElement): HierarchyBrowser = InkoTypeHierarchyBrowser(target.project, target)

    // Traits open on what implements them; types on the traits they implement.
    override fun browserActivated(hierarchyBrowser: HierarchyBrowser) {
        val browser = hierarchyBrowser as InkoTypeHierarchyBrowser
        browser.changeView(if (browser.isInterface) TypeHierarchyBrowserBase.getSubtypesHierarchyType() else TypeHierarchyBrowserBase.getSupertypesHierarchyType())
    }
}
