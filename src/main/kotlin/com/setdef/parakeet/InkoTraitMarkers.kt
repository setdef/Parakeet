package com.setdef.parakeet

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import javax.swing.Icon
import com.setdef.parakeet.InkoDefinitionKind as Kind

/**
 * Gutter icons linking traits and their implementations, from the `inko doc`
 * index: `impl Trait for Type` and its methods point up to the trait; a
 * trait and its methods point down to implementations; a type points to the
 * traits it implements.
 */
class InkoTraitLineMarkerProvider : RelatedItemLineMarkerProvider() {
    override fun collectNavigationMarkers(element: PsiElement, result: MutableCollection<in RelatedItemLineMarkerInfo<*>>) {
        if (!element.isToken(InkoTokens.CONSTANT) && !element.isToken(InkoTokens.IDENTIFIER)) return
        val file = element.containingFile ?: return
        val marker = InkoTraitLinks.of(element) ?: return
        val targets = marker.targets.mapNotNull { InkoResolver.toPsi(file, it) }.distinct()
        if (targets.isEmpty()) return
        result += NavigationGutterIconBuilder.create(marker.icon)
            .setTargets(targets)
            .setTooltipText(marker.tooltip)
            .createLineMarkerInfo(element)
    }
}

/** What a trait marker on a name token links to. */
class InkoTraitLink(val icon: Icon, val tooltip: String, val targets: List<InkoDefinition>)

object InkoTraitLinks {
    fun of(token: PsiElement): InkoTraitLink? {
        val file = token.containingFile ?: return null
        val path = Inko.pathOf(file.virtualFile) ?: return null
        val symbols = InkoDocIndex.getInstance(file.project).symbols(Inko.projectRoot(path)) ?: return null
        val imports = InkoImports.parse(file.text)
        val previous = token.previousToken()
        val name = token.text

        // `impl Show for Helper`: the trait name links to the trait.
        if (previous.isKeyword("impl") && token.isToken(InkoTokens.CONSTANT) && hasFor(token)) {
            val trait = trait(symbols, imports, name) ?: return null
            return InkoTraitLink(AllIcons.Gutter.ImplementingMethod, "Implements trait $name", listOf(trait))
        }
        // `trait Show`: links to implementing types.
        if (token.isToken(InkoTokens.CONSTANT) && declares(token, "trait")) {
            val trait = symbols.definedIn(path, name).firstOrNull { it.kind == Kind.TRAIT } ?: return null
            val types = trait.related.flatMap { ref -> symbols.named(ref.name, Kind.TYPE).filter { it.module == ref.module } }
            return InkoTraitLink(AllIcons.Gutter.ImplementedMethod, "Implemented by ${trait.related.joinToString { it.name }}", types)
        }
        // `type Helper`: links to the traits it implements.
        if (token.isToken(InkoTokens.CONSTANT) && declares(token, "type")) {
            val type = symbols.definedIn(path, name).firstOrNull { it.kind == Kind.TYPE } ?: return null
            val traits = type.related.flatMap { ref -> symbols.named(ref.name, Kind.TRAIT).filter { it.module == ref.module } }
            return InkoTraitLink(AllIcons.Gutter.ImplementingMethod, "Implements ${type.related.joinToString { it.name }}", traits)
        }
        // A method: in `impl Trait for Type`, links to the trait's method; in a trait, to implementations.
        if (token.isToken(InkoTokens.IDENTIFIER) && declares(token, "fn")) {
            val outline = file.inkoOutline
            val container = outline.enclosing(token.textRange.startOffset).dropLast(1).lastOrNull() ?: return null
            return when (container.kind) {
                InkoOutline.Kind.IMPL -> {
                    val traitName = outline.implTrait(container) ?: return null
                    val trait = trait(symbols, imports, traitName) ?: return null
                    val method = symbols.ownedBy(trait.name, Kind.INSTANCE_METHOD).filter { it.name == name && it.module == trait.module }
                    method.takeIf { it.isNotEmpty() }?.let { InkoTraitLink(AllIcons.Gutter.ImplementingMethod, "Implements $traitName.$name", it) }
                }
                InkoOutline.Kind.TRAIT -> {
                    val trait = symbols.definedIn(path, container.label).firstOrNull { it.kind == Kind.TRAIT } ?: return null
                    val ownMethod = symbols.ownedBy(trait.name, Kind.INSTANCE_METHOD).filter { it.name == name && it.module == trait.module }
                    val implementations = trait.related.flatMap { ref -> symbols.ownedBy(ref.name, Kind.INSTANCE_METHOD).filter { it.name == name } }
                        // Default methods are listed under each type too, but still live in the trait.
                        .filter { impl -> ownMethod.none { it.file == impl.file && it.line == impl.line } }
                    implementations.takeIf { it.isNotEmpty() }?.let {
                        InkoTraitLink(AllIcons.Gutter.ImplementedMethod, "Implemented in ${it.mapNotNull { m -> m.owner }.distinct().joinToString()}", it)
                    }
                }
                else -> null
            }
        }
        return null
    }

    private fun hasFor(traitName: PsiElement): Boolean = traitName.nextToken().isKeyword("for") ||
        // `impl Show[T] for Helper[T]`: skip type arguments.
        generateSequence(traitName.nextToken()) { it.nextToken() }.takeWhile { !it.isToken(InkoTokens.LBRACE) }.any { it.isKeyword("for") }

    /** Whether [name] is declared by [keyword], allowing modifiers between (`type pub async Main`, `fn pub static new`). */
    private fun declares(name: PsiElement, keyword: String): Boolean {
        var token = name.previousToken()
        while (token.isToken(InkoTokens.KEYWORD)) {
            if (token!!.text == keyword) return true
            if (token.text in setOf("fn", "type", "trait", "let", "impl", "for")) return false
            token = token.previousToken()
        }
        return false
    }

    /** The trait named [name] as seen from this file: imported, else any. */
    private fun trait(symbols: InkoSymbols, imports: InkoImports, name: String): InkoDefinition? {
        imports.symbol(name)?.let { (module, original) ->
            symbols.named(original, Kind.TRAIT).firstOrNull { it.module == module }?.let { return it }
        }
        return symbols.named(name, Kind.TRAIT).firstOrNull()
    }
}
