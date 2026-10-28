package com.setdef.parakeet

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement
import javax.swing.Icon

/**
 * Gutter icons linking traits and their implementations: a trait and its methods point down to their
 * implementations, from Cuttlebone (the type database); `impl Trait for Type` and its methods point up to the
 * trait, from the `impl`'s header and the trait's declaration ([InkoTraitImpl]), since asking Cuttlebone for each
 * would be a request per method on every highlighting pass (the typing replay counts them). Go to Super Method,
 * on demand, asks Cuttlebone, and finds a type's traits too.
 */
class InkoTraitLineMarkerProvider : RelatedItemLineMarkerProvider() {
    override fun collectNavigationMarkers(element: PsiElement, result: MutableCollection<in RelatedItemLineMarkerInfo<*>>) {
        if (!element.isToken(InkoTokens.CONSTANT) && !element.isToken(InkoTokens.IDENTIFIER)) return
        val marker = InkoTraitLinks.of(element, gutter = true) ?: return
        if (marker.targets.isEmpty()) return
        result += NavigationGutterIconBuilder.create(marker.icon)
            .setTargets(marker.targets)
            .setTooltipText(marker.tooltip)
            .createLineMarkerInfo(element)
    }
}

/** What a trait marker on a name token links to. */
class InkoTraitLink(val icon: Icon, val tooltip: String, val targets: List<PsiElement>) {
    /** Whether this leads down to implementations (rather than up to a trait). */
    val toImplementations: Boolean get() = icon === AllIcons.Gutter.ImplementedMethod
}

object InkoTraitLinks {
    /**
     * The link for [token], from Cuttlebone's answers: none where it has none. A stale answer counts unless [fresh]
     * (Rename changes code from it). For the [gutter], links up to a trait come from the code ([InkoTraitImpl]), and
     * a type has none.
     */
    fun of(token: PsiElement, fresh: Boolean = false, gutter: Boolean = false): InkoTraitLink? {
        val name = token.parent as? InkoName ?: return null
        val file = token.containingFile ?: return null
        val queries = CuttleboneQueries.getInstance(file.project)
        fun targets(answer: CuttleboneQueries.Answer<List<org.eclipse.lsp4j.Location>>?) =
            answer?.value.orEmpty().mapNotNull(queries::target).filter { it != name }.distinct()

        // `impl Show for Helper`: the trait name links to the trait.
        if (token.previousToken().isKeyword("impl") && token.isToken(InkoTokens.CONSTANT) && hasFor(token)) {
            val trait = if (gutter) InkoTraitImpl.at(file, token.textRange.startOffset)?.trait?.let { InkoResolver.toPsi(file, it) }
            else InkoResolver.resolve(name).filterIsInstance<InkoDeclaration>().firstOrNull()
            if (trait == null) return null
            return InkoTraitLink(AllIcons.Gutter.ImplementingMethod, "Implements trait ${token.text}", listOf(trait))
        }
        if (name !is InkoDeclaration) return null
        val offset = name.textRange.startOffset
        val path = file.inkoOutline.enclosing(offset)
        return when (path.lastOrNull { it.nameOffset == offset }?.kind) {
            // `trait Show`: links to implementing types.
            InkoOutline.Kind.TRAIT -> targets(queries.implementations(file, offset)).let { types ->
                InkoTraitLink(AllIcons.Gutter.ImplementedMethod, "Implemented by ${types.joinToString { it.text }}", types)
            }
            // `type Helper`: links to the traits it implements.
            InkoOutline.Kind.TYPE -> if (gutter) null else targets(queries.supertypes(file, offset)).let { traits ->
                InkoTraitLink(AllIcons.Gutter.ImplementingMethod, "Implements ${traits.joinToString { it.text }}", traits)
            }
            // A method: in `impl Trait for Type`, links to the trait's method; in a trait, to implementations.
            InkoOutline.Kind.METHOD -> when (path.dropLast(1).lastOrNull()?.kind) {
                InkoOutline.Kind.IMPL -> (if (gutter) listOfNotNull(InkoTraitImpl.at(file, offset)?.traitMethod(name.text)?.let { InkoResolver.toPsi(file, it) })
                else targets(queries.declaration(file, offset, stale = !fresh))).let { methods ->
                    InkoTraitLink(AllIcons.Gutter.ImplementingMethod, "Implements ${methods.joinToString(transform = InkoCallHierarchy::label)}", methods)
                }
                InkoOutline.Kind.TRAIT -> targets(queries.implementations(file, offset)).let { methods ->
                    val owners = methods.map { InkoCallHierarchy.label(it).substringBeforeLast('.') }.distinct()
                    InkoTraitLink(AllIcons.Gutter.ImplementedMethod, "Implemented in ${owners.joinToString()}", methods)
                }
                else -> null
            }
            else -> null
        }
    }

    private fun hasFor(traitName: PsiElement): Boolean = traitName.nextToken().isKeyword("for") ||
        // `impl Show[T] for Helper[T]`: skip type arguments.
        generateSequence(traitName.nextToken()) { it.nextToken() }.takeWhile { !it.isToken(InkoTokens.LBRACE) }.any { it.isKeyword("for") }
}
