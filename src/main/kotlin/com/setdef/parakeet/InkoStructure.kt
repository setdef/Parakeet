package com.setdef.parakeet

import com.intellij.icons.AllIcons
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.lang.Language
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.breadcrumbs.BreadcrumbsProvider
import javax.swing.Icon

/** The file's outline, recomputed when it changes. */
val PsiFile.inkoOutline: InkoOutline
    get() = CachedValuesManager.getCachedValue(this) { CachedValueProvider.Result.create(InkoOutline.of(text), this) }

val InkoOutline.Kind.icon: Icon
    get() = when (this) {
        InkoOutline.Kind.TYPE -> AllIcons.Nodes.Class
        InkoOutline.Kind.TRAIT -> AllIcons.Nodes.Interface
        InkoOutline.Kind.IMPL -> AllIcons.Nodes.AbstractClass
        InkoOutline.Kind.METHOD -> AllIcons.Nodes.Method
        InkoOutline.Kind.FIELD -> AllIcons.Nodes.Field
        InkoOutline.Kind.CONSTANT -> AllIcons.Nodes.Constant
    }

/** The [InkoName] where [node]'s name is written. */
fun PsiFile.nameElementOf(node: InkoOutline.Node): PsiElement? =
    findElementAt(node.nameOffset)?.let { PsiTreeUtil.getParentOfType(it, InkoName::class.java, false) ?: it }

class InkoStructureViewFactory : PsiStructureViewFactory {
    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? {
        if (psiFile !is InkoFile) return null
        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel = InkoStructureViewModel(psiFile, editor)
        }
    }
}

class InkoStructureViewModel(private val file: InkoFile, editor: Editor?) :
    StructureViewModelBase(file, editor, InkoFileElement(file)),
    StructureViewModel.ElementInfoProvider {

    // The PSI is flat, so the selected element comes from the outline rather than PSI parents.
    override fun getCurrentEditorElement(): Any? {
        val offset = editor?.caretModel?.offset ?: return null
        return file.inkoOutline.enclosing(offset).lastOrNull()?.let(file::nameElementOf)
    }

    override fun isAlwaysShowsPlus(element: StructureViewTreeElement): Boolean = false

    override fun isAlwaysLeaf(element: StructureViewTreeElement): Boolean = false
}

private class InkoFileElement(private val file: InkoFile) : StructureViewTreeElement, ItemPresentation {
    override fun getValue(): Any = file
    override fun getPresentation(): ItemPresentation = this
    override fun getPresentableText(): String = file.name
    override fun getIcon(unused: Boolean): Icon = InkoIcons.FILE
    override fun getChildren(): Array<StructureViewTreeElement> =
        file.inkoOutline.roots.map { InkoOutlineElement(file, it) }.toTypedArray()
    override fun navigate(requestFocus: Boolean) = file.navigate(requestFocus)
    override fun canNavigate(): Boolean = file.canNavigate()
    override fun canNavigateToSource(): Boolean = file.canNavigateToSource()
}

private class InkoOutlineElement(private val file: InkoFile, private val node: InkoOutline.Node) : StructureViewTreeElement, ItemPresentation {
    override fun getValue(): Any = file.nameElementOf(node) ?: node
    override fun getPresentation(): ItemPresentation = this
    override fun getPresentableText(): String = node.label
    override fun getIcon(unused: Boolean): Icon = node.kind.icon
    override fun getChildren(): Array<StructureViewTreeElement> = node.children.map { InkoOutlineElement(file, it) }.toTypedArray()
    override fun navigate(requestFocus: Boolean) {
        val virtualFile = file.virtualFile ?: return
        OpenFileDescriptor(file.project, virtualFile, node.nameOffset).navigate(requestFocus)
    }
    override fun canNavigate(): Boolean = file.virtualFile != null
    override fun canNavigateToSource(): Boolean = canNavigate()
}

/** Breadcrumbs (and sticky lines) for the declarations around the caret. */
class InkoBreadcrumbsProvider : BreadcrumbsProvider {
    override fun getLanguages(): Array<Language> = arrayOf(InkoLanguage)

    // The declaration [element] names: an InkoName, or an operator method's first token (`==`).
    private fun nodeFor(element: PsiElement): InkoOutline.Node? {
        val file = element.containingFile ?: return null
        if (element is PsiFile) return null
        return file.inkoOutline.all.firstOrNull { it.nameOffset == element.textRange.startOffset && file.nameElementOf(it) == element }
    }

    override fun acceptElement(element: PsiElement): Boolean = nodeFor(element) != null

    override fun getElementInfo(element: PsiElement): String = nodeFor(element)?.label ?: element.text

    override fun getElementIcon(element: PsiElement): Icon? = nodeFor(element)?.kind?.icon

    // The PSI is flat, so each crumb's parent is the next declaration out, from the outline.
    override fun getParent(element: PsiElement): PsiElement? {
        val file = element.containingFile ?: return null
        if (element is PsiFile) return null
        val enclosing = file.inkoOutline.enclosing(element.textOffset)
        val own = nodeFor(element)
        val parent = if (own != null) enclosing.getOrNull(enclosing.indexOf(own) - 1) else enclosing.lastOrNull()
        // Never the element itself, which would send the breadcrumbs walking up forever.
        return parent?.let(file::nameElementOf)?.takeIf { it != element } ?: file
    }
}
