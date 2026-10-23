package com.setdef.parakeet

import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerBase
import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerFactoryBase
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Consumer

/** Highlights the other occurrences of the name at the caret that mean the same thing (and ⌘⇧F7 / Ctrl+Shift+F7). */
class InkoHighlightUsagesHandlerFactory : HighlightUsagesHandlerFactoryBase() {
    override fun createHighlightUsagesHandler(editor: Editor, file: PsiFile, target: PsiElement): HighlightUsagesHandlerBase<*>? {
        if (file !is InkoFile) return null
        val name = PsiTreeUtil.getParentOfType(target, InkoName::class.java, false) ?: return null
        return InkoHighlightUsagesHandler(editor, file, name)
    }
}

class InkoHighlightUsagesHandler(editor: Editor, file: PsiFile, private val name: InkoName) : HighlightUsagesHandlerBase<InkoName>(editor, file) {
    override fun getTargets(): List<InkoName> = listOf(name)

    override fun selectTargets(targets: List<InkoName>, selectionConsumer: Consumer<in List<InkoName>>) = selectionConsumer.consume(targets)

    override fun computeUsages(targets: List<InkoName>) {
        for ((occurrence, isDeclaration) in InkoUsages.inFile(name)) {
            (if (isDeclaration) myWriteUsages else myReadUsages) += occurrence.textRange
        }
    }
}

object InkoUsages {
    /** Names in [name]'s file that mean what it means, each marked whether it's the declaration. */
    fun inFile(name: InkoName): List<Pair<InkoName, Boolean>> {
        val file = name.containingFile ?: return emptyList()
        // A setter (`value=`) is called as `recv.value = x`, and that call means the setter.
        val texts = setOf(name.text, name.text.removeSuffix("="), "${name.text}=")
        val candidates = PsiTreeUtil.findChildrenOfType(file, InkoName::class.java).filter { it.text in texts }
        val symbol = symbolOf(name)
        return candidates.mapNotNull { candidate ->
            val other = if (candidate == name) symbol else symbolOf(candidate)
            if (!symbol.matches(other)) return@mapNotNull null
            candidate to (candidate.textRange.startOffset in other.declarations || declaresItself(candidate))
        }
    }

    /** What a name refers to: declaration offsets in this file (if known), and how it's used. */
    private class Symbol(val declarations: Set<Int>, val targets: Set<PsiElement>, val kind: String) {
        val isUnresolved: Boolean get() = declarations.isEmpty() && targets.isEmpty()

        fun matches(other: Symbol): Boolean = when {
            declarations.isNotEmpty() && other.declarations.isNotEmpty() -> declarations.intersect(other.declarations).isNotEmpty()
            // Without index data for one side (e.g. a new function), fall back to the same name used the same way.
            isUnresolved || other.isUnresolved -> kind == other.kind
            else -> targets.intersect(other.targets).isNotEmpty()
        }
    }

    private fun symbolOf(name: InkoName): Symbol {
        val file = name.containingFile
        val offset = name.textRange.startOffset
        val text = file.text
        // A local: the name declaring it, or a use of it.
        InkoLocals.declaredAt(text, offset)?.let { return Symbol(setOf(it.offset), emptySet(), "local") }
        (InkoNameContext.of(name, InkoImports.parse(text)) as? InkoNameContext.Local)?.let {
            return Symbol(setOf(it.local.offset), emptySet(), "local")
        }
        if (name.tokenType == InkoTokens.FIELD) {
            // Fields: the same name within the same type.
            val owner = file.inkoOutline.enclosing(offset).lastOrNull { it.kind == InkoOutline.Kind.TYPE || it.kind == InkoOutline.Kind.IMPL }
            return Symbol(emptySet(), emptySet(), "field:${owner?.let { file.inkoOutline.implTarget(it) ?: it.label }}")
        }
        val targets = InkoResolver.resolve(name).toSet()
        val declarations = targets.filter { it.containingFile == file }.map { it.textRange.startOffset }.toSet() +
            setOfNotNull(offset.takeIf { declaresItself(name) })
        val kind = if (name.previousToken().isToken(InkoTokens.DOT)) "member" else "name"
        return Symbol(declarations, targets, kind)
    }

    /** Whether [name] is being declared here (`fn name`, `type Name`, `trait Name`, `let NAME`). */
    private fun declaresItself(name: InkoName): Boolean {
        var token = name.previousToken()
        while (token.isToken(InkoTokens.KEYWORD)) {
            if (token!!.text in setOf("fn", "type", "trait", "let")) return true
            token = token.previousToken()
        }
        return false
    }
}
