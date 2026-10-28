package com.setdef.parakeet

import com.intellij.ide.actions.QualifiedNameProvider
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import java.nio.file.Path

/**
 * Copy Reference (⌥⇧⌘C / Ctrl+Alt+Shift+C): `app.util` for a module,
 * `app.util.Helper` for a type, `app.util.Helper.new` for a method, and
 * `app.util.helper` for a module method, as the Inko docs name things.
 */
class InkoQualifiedNameProvider : QualifiedNameProvider {
    override fun adjustElementToCopy(element: PsiElement): PsiElement? =
        element as? InkoFile ?: PsiTreeUtil.getParentOfType(element, InkoDeclaration::class.java, false)

    override fun getQualifiedName(element: PsiElement): String? = InkoQualifiedNames.of(element)

    // Only copying is supported; going from a pasted reference back to its element isn't.
    override fun qualifiedNameToElement(fqn: String, project: Project): PsiElement? = null
}

object InkoQualifiedNames {
    fun of(element: PsiElement): String? {
        val file = element.containingFile ?: return null
        val path = Inko.pathOf(file.virtualFile) ?: return null
        val module = Inko.moduleName(path) ?: libraryModule(file, path) ?: return null
        if (element is InkoFile) return module
        val declaration = element as? InkoDeclaration ?: return null
        // Locals and parameters have no name outside their method.
        if (declaration.isLocal) return null
        val offset = declaration.textRange.startOffset
        val outline = file.inkoOutline
        val owner = outline.enclosing(offset).lastOrNull { it.nameOffset != offset && it.kind != InkoOutline.Kind.METHOD }?.let { node ->
            if (node.kind == InkoOutline.Kind.IMPL) outline.implTarget(node) else node.label
        }
        return listOfNotNull(module, owner, declaration.name).joinToString(".")
    }

    // Files outside a project's src/ and test/ (the standard library, dependencies) are named by the index.
    private fun libraryModule(file: PsiFile, path: Path): String? {
        // Finding project roots needs the IDE's file index, which isn't usable while indexing.
        if (DumbService.isDumb(file.project)) return null
        val index = InkoDefinitionIndex.getInstance(file.project)
        return InkoProjectRoots.find(file.project).firstNotNullOfOrNull { index.symbols(it)?.moduleOf(path) }
    }
}
