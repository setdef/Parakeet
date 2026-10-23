package com.setdef.parakeet

import com.intellij.codeInsight.codeVision.CodeVisionAnchorKind
import com.intellij.codeInsight.codeVision.CodeVisionRelativeOrdering
import com.intellij.codeInsight.hints.codeVision.ReferencesCodeVisionProvider
import com.intellij.ide.hierarchy.CallHierarchyBrowserBase
import com.intellij.ide.hierarchy.HierarchyBrowser
import com.intellij.ide.hierarchy.HierarchyNodeDescriptor
import com.intellij.ide.hierarchy.HierarchyProvider
import com.intellij.ide.hierarchy.HierarchyTreeStructure
import com.intellij.ide.util.treeView.AlphaComparator
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ui.util.CompositeAppearance
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.PopupHandler
import javax.swing.JTree
import com.setdef.parakeet.InkoDefinitionKind as Kind

/** Who calls a method (its usages, by the method they're in), and what it calls (resolved from its body). */
object InkoCallHierarchy {
    /** The method (or function) declared at [declaration], if that's what it declares. */
    fun isMethod(declaration: InkoDeclaration): Boolean = InkoDeclarations.kindOf(declaration) in setOf("method", "function")

    /** The method whose body contains [element]. */
    fun enclosingMethod(element: PsiElement): InkoDeclaration? {
        val file = element.containingFile ?: return null
        val node = file.inkoOutline.enclosing(element.textRange.startOffset).lastOrNull { it.kind == InkoOutline.Kind.METHOD } ?: return null
        return PsiTreeUtil.getParentOfType(file.findElementAt(node.nameOffset), InkoDeclaration::class.java, false)
    }

    fun callers(method: InkoDeclaration): List<InkoDeclaration> =
        ReferencesSearch.search(method).findAll().mapNotNull { enclosingMethod(it.element) }.distinct()

    fun callees(method: InkoDeclaration): List<PsiElement> {
        val file = method.containingFile
        val body = file.inkoOutline.enclosing(method.textRange.startOffset).lastOrNull { it.nameOffset == method.textRange.startOffset }?.range
            ?: return emptyList()
        return PsiTreeUtil.findChildrenOfType(file, InkoName::class.java)
            .filter { it !is InkoDeclaration && body.contains(it.textRange) && it.textRange.startOffset > method.textRange.endOffset }
            .flatMap { name -> InkoResolver.resolveDefinitions(name) }
            .filter { (definition, _) -> definition.kind in setOf(Kind.MODULE_METHOD, Kind.STATIC_METHOD, Kind.INSTANCE_METHOD) }
            .map { it.second }
            .distinct()
    }

    /** "Owner.name" for a method in a type, trait, or impl; just the name for a function. */
    fun label(method: PsiElement): String {
        val file = method.containingFile ?: return method.text
        val outline = file.inkoOutline
        val path = outline.enclosing(method.textRange.startOffset)
        val owner = path.dropLast(1).lastOrNull()?.let { if (it.kind == InkoOutline.Kind.IMPL) outline.implTarget(it) else it.label }
        return listOfNotNull(owner, method.text).joinToString(".")
    }
}

class InkoCallNodeDescriptor(project: Project, parent: NodeDescriptor<*>?, element: PsiElement, isBase: Boolean) :
    HierarchyNodeDescriptor(project, parent, element, isBase) {
    override fun update(): Boolean {
        val changed = super.update()
        val element = psiElement ?: return changed
        val text = CompositeAppearance()
        text.ending.addText(InkoCallHierarchy.label(element))
        element.containingFile?.virtualFile?.let { Inko.pathOf(it) }?.let(Inko::moduleName)?.let { text.ending.addComment(it) }
        myHighlightedText = text
        return changed
    }
}

class InkoCallTreeStructure(project: Project, base: PsiElement, private val callers: Boolean) :
    HierarchyTreeStructure(project, InkoCallNodeDescriptor(project, null, base, true)) {
    override fun buildChildren(descriptor: HierarchyNodeDescriptor): Array<Any> {
        val method = descriptor.psiElement as? InkoDeclaration ?: return emptyArray()
        // Recursion shows once, not forever.
        val seen = generateSequence(descriptor) { it.parentDescriptor as? HierarchyNodeDescriptor }.drop(1).mapNotNull { it.psiElement }.toSet()
        if (method in seen) return emptyArray()
        val related = if (callers) InkoCallHierarchy.callers(method) else InkoCallHierarchy.callees(method)
        return related.map { InkoCallNodeDescriptor(myProject, descriptor, it, false) }.toTypedArray()
    }
}

/** Navigate | Call Hierarchy (⌃⌥H / Ctrl+Alt+H) for Inko methods and functions. */
class InkoCallHierarchyBrowser(project: Project, method: PsiElement) : CallHierarchyBrowserBase(project, method) {
    override fun getElementFromDescriptor(descriptor: HierarchyNodeDescriptor): PsiElement? = descriptor.psiElement

    override fun createTrees(trees: MutableMap<in String, in JTree>) {
        for (type in listOf(getCalleeType(), getCallerType())) {
            val tree = createTree(false)
            PopupHandler.installPopupMenu(tree, IdeActions.GROUP_CALL_HIERARCHY_POPUP, ActionPlaces.CALL_HIERARCHY_VIEW_POPUP)
            trees[type] = tree
        }
    }

    override fun isApplicableElement(element: PsiElement): Boolean = element is InkoDeclaration && InkoCallHierarchy.isMethod(element)

    override fun createHierarchyTreeStructure(type: String, element: PsiElement): HierarchyTreeStructure? = when (type) {
        getCallerType() -> InkoCallTreeStructure(myProject, element, callers = true)
        getCalleeType() -> InkoCallTreeStructure(myProject, element, callers = false)
        else -> null
    }

    override fun getComparator(): Comparator<NodeDescriptor<*>> = AlphaComparator.getInstance()
}

class InkoCallHierarchyProvider : HierarchyProvider {
    override fun getTarget(dataContext: DataContext): PsiElement? {
        val editor = CommonDataKeys.EDITOR.getData(dataContext) ?: return null
        val file = CommonDataKeys.PSI_FILE.getData(dataContext) as? InkoFile ?: return null
        val name = PsiTreeUtil.getParentOfType(file.findElementAt(editor.caretModel.offset), InkoName::class.java, false)
        // A method's name (declared or called), else the method the caret is in.
        if (name is InkoDeclaration && InkoCallHierarchy.isMethod(name)) return name
        if (name != null) {
            InkoResolver.resolve(name).filterIsInstance<InkoDeclaration>().firstOrNull { InkoCallHierarchy.isMethod(it) }?.let { return it }
        }
        return InkoCallHierarchy.enclosingMethod(file.findElementAt(editor.caretModel.offset) ?: return null)
    }

    override fun createHierarchyBrowser(target: PsiElement): HierarchyBrowser = InkoCallHierarchyBrowser(target.project, target)

    override fun browserActivated(hierarchyBrowser: HierarchyBrowser) =
        (hierarchyBrowser as InkoCallHierarchyBrowser).changeView(CallHierarchyBrowserBase.getCallerType())
}

/**
 * "N usages" above Inko declarations, in the IDE's shared Usages code vision
 * (on by default, like Java's and Kotlin's; Settings | Editor | Inlay Hints).
 */
class InkoUsagesCodeVisionProvider : ReferencesCodeVisionProvider() {
    override val id: String = "inko.references"

    override val relativeOrderings: List<CodeVisionRelativeOrdering> = emptyList()

    override val defaultAnchor: CodeVisionAnchorKind = CodeVisionAnchorKind.Default

    override fun acceptsFile(file: PsiFile): Boolean = file is InkoFile

    override fun acceptsElement(element: PsiElement): Boolean = element is InkoDeclaration && !element.isLocal

    override fun getHint(element: PsiElement, file: PsiFile): String? {
        val count = ReferencesSearch.search(element).findAll().size
        return when (count) {
            0 -> "no usages"
            1 -> "1 usage"
            else -> "$count usages"
        }
    }
}
