package com.setdef.parakeet

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.ResolveResult
import com.intellij.psi.util.PsiTreeUtil
import com.setdef.parakeet.InkoDefinitionKind as Kind

class InkoReference(element: InkoName) :
    PsiPolyVariantReferenceBase<InkoName>(element, TextRange(0, element.textLength), false) {
    // Only an exact resolution is valid: resolve() (Rename at the caret, for one) then has a single target.
    // Navigation still offers the others, from a stale answer or Parakeet's reading of code being edited.
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val resolution = InkoResolver.resolution(element, exact = false)
        return resolution.targets.map { PsiElementResolveResult(it, resolution.exact) }.toTypedArray()
    }

    // Searches ask this of every occurrence of a name. Where the target depends on types, by the compiler's
    // answers (InkoSearch); locals, imports and module paths, and a declaration's own name, by the code alone.
    override fun isReferenceTo(element: PsiElement): Boolean {
        val name = this.element
        if (element !is InkoDeclaration || name is InkoDeclaration) return super.isReferenceTo(element)
        val file = name.containingFile ?: return false
        if (!InkoResolver.isGuessed(InkoNameContext.of(name, InkoImports.parse(file.text)))) return super.isReferenceTo(element)
        return InkoSearch.isReferenceTo(name, element)
    }

    override fun getVariants(): Array<Any> = emptyArray()

    // A renamed module file (`util.inko`) renames the module in import paths (`util`).
    // A setter's call (`recv.value = x`) names it without its `=`.
    // A name imported under an alias (`Helper as Assistant`) keeps it: only the name it imports changes.
    override fun handleElementRename(newElementName: String): PsiElement {
        val text = element.text
        if (element.tokenType != InkoTokens.FIELD && InkoImports.parse(element.containingFile.text).symbol(text)?.second?.let { it != text && it != "self" } == true) {
            return element
        }
        val name = newElementName.removeSuffix(".${Inko.EXTENSION}")
        return element.rename(if (!element.text.endsWith("=")) name.removeSuffix("=") else name)
    }

    // A moved module file changes its module path: an import names it anew; `util.helper` (by its last name) stays.
    override fun bindToElement(target: PsiElement): PsiElement {
        val file = target as? InkoFile ?: return element
        val module = Inko.pathOf(file.virtualFile)?.let(Inko::moduleName) ?: return element
        InkoModuleImports.rewrite(element, module)
        return element
    }
}

/**
 * Resolves names to definitions from the `inko doc` index (locals come from the
 * file itself). Locations are exact. Types aren't inferred, so a method call
 * resolves to the receiver type's method when that type is written or obvious
 * (a typed local, or `self`), and otherwise to every candidate it can't rule out.
 */
class InkoResolver private constructor(
    private val symbols: InkoSymbols,
    private val module: String?,
    private val imports: InkoImports,
    private val file: PsiFile,
    /** Where the name being resolved is. */
    private val offset: Int,
) {
    fun definitions(context: InkoNameContext): List<InkoDefinition> = when (context) {
        is InkoNameContext.ImportPath -> listOfNotNull(symbols.module(context.module))
        is InkoNameContext.ImportedSymbol -> inModule(context.module, context.name)
        is InkoNameContext.ModuleMember -> inModule(context.module, context.name)
        is InkoNameContext.ModuleReference -> listOfNotNull(symbols.module(context.module))
        is InkoNameContext.Field -> field(context.name)
        is InkoNameContext.StaticMember -> staticMember(context.type, context.name)
        is InkoNameContext.InstanceMember ->
            // `recv.name = x` calls the setter `name=`, or assigns the field when there's no setter.
            if (context.assigned) instanceMember("${context.name}=", context.receiverType).ifEmpty { fieldsNamed(context.name, context.receiverType) }
            else instanceMember(context.name, context.receiverType)
        is InkoNameContext.Local -> emptyList() // Resolved from the file itself; see [localDeclaration].
        is InkoNameContext.Constant -> typesNamed(context.name).ifEmpty { symbols.named(context.name, Kind.CONSTRUCTOR) }
        is InkoNameContext.Identifier -> function(context.name)
        InkoNameContext.Definition, InkoNameContext.BuildTag -> emptyList()
    }

    private fun inModule(module: String, name: String): List<InkoDefinition> =
        symbols.named(name, Kind.TYPE, Kind.TRAIT, Kind.CONSTANT, Kind.MODULE_METHOD).filter { it.module == module }

    private fun preferringThisModule(definitions: List<InkoDefinition>): List<InkoDefinition> =
        definitions.filter { it.module == module }.ifEmpty { definitions }

    private fun imported(name: String): List<InkoDefinition> =
        imports.symbol(name)?.let { (module, original) -> inModule(module, original) }.orEmpty()

    /** Types, traits, or constants named [name]: this module's, then imported ones, then any. */
    private fun typesNamed(name: String): List<InkoDefinition> =
        symbols.named(name, Kind.TYPE, Kind.TRAIT, Kind.CONSTANT).filter { it.module == module }
            .ifEmpty { imported(name) }
            .ifEmpty { symbols.named(name, Kind.TYPE, Kind.TRAIT) }

    private fun staticMember(type: String, name: String): List<InkoDefinition> {
        val owners = typesNamed(type).map { it.name }.toSet() + type
        val statics = symbols.named(name, Kind.STATIC_METHOD, Kind.CONSTRUCTOR)
        return statics.filter { it.owner in owners }.ifEmpty { statics }
    }

    /** Without types, any type's method could match; prefer types the file mentions or imports. */
    private fun instanceMember(name: String, receiverType: String?): List<InkoDefinition> {
        if (receiverType != null) {
            // A known receiver type narrows it to that type's methods, and (public) fields.
            val owner = imports.symbol(receiverType)?.second ?: receiverType
            symbols.named(name, Kind.INSTANCE_METHOD, Kind.FIELD).filter { it.owner == owner }.takeIf { it.isNotEmpty() }?.let { return it }
        }
        val methods = symbols.named(name, Kind.INSTANCE_METHOD)
        val inScope = PsiTreeUtil.findChildrenOfType(file, InkoName::class.java)
            .filter { it.tokenType == InkoTokens.CONSTANT }
            .map { it.text }
            .toSet() + imports.symbolNames
        return methods.filter { it.owner in inScope }.ifEmpty { methods }
    }

    private fun fieldsNamed(name: String, receiverType: String?): List<InkoDefinition> {
        val fields = symbols.named(name, Kind.FIELD)
        val owner = receiverType?.let { imports.symbol(it)?.second ?: it } ?: return fields
        return fields.filter { it.owner == owner }.ifEmpty { fields }
    }

    /** `@name` means the enclosing type's field (fields are private to their type). */
    private fun field(name: String): List<InkoDefinition> {
        val fields = preferringThisModule(symbols.named(name, Kind.FIELD))
        val owner = InkoLocals.selfType(file.text, offset) ?: return fields
        return fields.filter { it.owner == owner }.ifEmpty { fields }
    }

    /**
     * A bare `name(…)`: inside a type (or impl, or trait), Inko calls the
     * receiver's own method first (a static one from static methods), then
     * falls back to module methods.
     */
    private fun function(name: String): List<InkoDefinition> {
        selfMethod(name).takeIf { it.isNotEmpty() }?.let { return it }
        return symbols.named(name, Kind.MODULE_METHOD).filter { it.module == module }.ifEmpty { imported(name) }
    }

    private fun selfMethod(name: String): List<InkoDefinition> {
        val text = file.text
        val owner = InkoLocals.selfType(text, offset)?.let { imports.symbol(it)?.second ?: it } ?: return emptyList()
        val method = file.inkoOutline.enclosing(offset).lastOrNull { it.kind == InkoOutline.Kind.METHOD }
        val inStatic = method != null && Regex("\\bstatic\\b").containsMatchIn(text.subSequence(method.range.startOffset, method.nameOffset))
        return symbols.named(name, if (inStatic) Kind.STATIC_METHOD else Kind.INSTANCE_METHOD).filter { it.owner == owner }
    }

    companion object {
        /**
         * What [name] refers to, for what only reads or shows: navigation, documentation, hierarchies.
         * A fresh answer, else a stale one, else in code being edited, Parakeet's own reading ([resolution]).
         */
        fun resolve(name: InkoName): List<PsiElement> = resolution(name, exact = false).targets

        /** What [name] refers to by a fresh answer only, or the code alone: for what changes code ([resolution]). */
        fun resolveExactly(name: InkoName): List<PsiElement> = resolution(name, exact = true).targets

        /** [resolve]'s (or with [exact], [resolveExactly]'s) targets that Parakeet knows the definition of. */
        fun resolveDefinitions(name: InkoName, exact: Boolean = false): List<Pair<InkoDefinition, PsiElement>> =
            resolution(name, exact).definitions.mapNotNull { (definition, target) -> definition?.let { it to target } }

        /** Where a local variable or parameter is declared, found from the file alone (no index needed). */
        private fun localDeclaration(name: InkoName): PsiElement? {
            if (name.tokenType != InkoTokens.IDENTIFIER) return null
            val file = name.containingFile ?: return null
            val local = (InkoNameContext.of(name, InkoImports.parse(file.text)) as? InkoNameContext.Local)?.local ?: return null
            val leaf = file.findElementAt(local.offset) ?: return null
            return PsiTreeUtil.getParentOfType(leaf, InkoName::class.java, false) ?: leaf
        }

        /** What a name refers to: each target, with Parakeet's definition there if it knows one, and whether it's [exact]. */
        class Resolution(val definitions: List<Pair<InkoDefinition?, PsiElement>>, val exact: Boolean) {
            val targets: List<PsiElement> get() = definitions.map { it.second }
        }

        private val NOTHING = Resolution(emptyList(), exact = true)

        /**
         * What [name] refers to. Locals, imports and module paths follow from the code alone, so they're
         * [Resolution.exact]. Names whose targets depend on types (members, calls, constants, fields) need
         * Cuttlebone's `textDocument/definition`: a fresh answer is exact. Without [exact], a stale one counts
         * too, and in code being edited ([CuttleboneQueries.Answer.editing]) Parakeet's own reading stands in;
         * neither is exact, and what changes code never uses them. Without an answer, nothing.
         */
        fun resolution(name: InkoName, exact: Boolean): Resolution {
            localDeclaration(name)?.let { return Resolution(listOf(null to it), exact = true) }
            val file = name.containingFile ?: return NOTHING
            val path = Inko.pathOf(file.virtualFile) ?: return NOTHING
            val symbols = InkoDocIndex.getInstance(name.project).symbols(Inko.projectRoot(path))
            val imports = InkoImports.parse(file.text)
            val context = InkoNameContext.of(name, imports)
            if (!isGuessed(context)) return Resolution(symbols?.let { heuristic(name, file, path, it, imports, context) }.orEmpty(), exact = true)
            val queries = CuttleboneQueries.getInstance(name.project)
            val answer = queries.definition(file, name.textRange.startOffset, stale = !exact) ?: return NOTHING
            if (!answer.editing) {
                // Where Parakeet doesn't know a definition (one `inko doc` hasn't seen yet), the target still counts.
                val targets = answer.value.orEmpty().take(1).mapNotNull(queries::target).map { target -> symbols?.let { definitionAt(target, it) } to target }
                return Resolution(targets, exact = !answer.stale)
            }
            if (exact || symbols == null) return NOTHING
            return Resolution(heuristic(name, file, path, symbols, imports, context), exact = false)
        }

        private fun heuristic(name: InkoName, file: PsiFile, path: java.nio.file.Path, symbols: InkoSymbols, imports: InkoImports, context: InkoNameContext): List<Pair<InkoDefinition?, PsiElement>> {
            val resolver = InkoResolver(symbols, symbols.moduleOf(path) ?: Inko.moduleName(path), imports, file, name.textRange.startOffset)
            return resolver.definitions(context).distinct().mapNotNull { definition ->
                toPsi(file, definition)?.let { definition to it }
            }
        }

        /**
         * The getter a compound assignment to a member (`c.value += 1`) also calls, besides [setters], what it
         * resolves to (as Cuttlebone's definition there is the setter): the compiler calls the getter `value`, and
         * then the setter `value=` with its result plus 1 (compiler/src/hir.rs, `binary_assign_setter`). It's the
         * setter's type's method of that name, else its field. Empty for any other name.
         */
        fun getters(name: InkoName, setters: List<PsiElement>): List<PsiElement> {
            if (setters.isEmpty()) return emptyList()
            val file = name.containingFile ?: return emptyList()
            val imports = InkoImports.parse(file.text)
            val context = InkoNameContext.of(name, imports) as? InkoNameContext.InstanceMember ?: return emptyList()
            if (!context.compound) return emptyList()
            val path = Inko.pathOf(file.virtualFile) ?: return emptyList()
            val symbols = InkoDocIndex.getInstance(name.project).symbols(Inko.projectRoot(path)) ?: return emptyList()
            val resolver = InkoResolver(symbols, symbols.moduleOf(path) ?: Inko.moduleName(path), imports, file, name.textRange.startOffset)
            return setters.flatMap { setter ->
                // Without a setter, it assigns the field, which it also reads.
                if (setter.text.startsWith("@")) return@flatMap listOf(setter)
                val owner = definitionAt(setter, symbols)?.owner
                val beside = owner?.let {
                    symbols.named(context.name, Kind.INSTANCE_METHOD).filter { it.owner == owner }
                        .ifEmpty { symbols.named(context.name, Kind.FIELD).filter { it.owner == owner } }
                }.orEmpty()
                beside.ifEmpty { resolver.instanceMember(context.name, context.receiverType) }.mapNotNull { toPsi(file, it) }
            }.distinct()
        }

        /** The kinds of names whose targets the heuristic guesses at, which Cuttlebone is asked about. */
        fun isGuessed(context: InkoNameContext): Boolean = when (context) {
            is InkoNameContext.InstanceMember, is InkoNameContext.StaticMember, is InkoNameContext.Identifier,
            is InkoNameContext.Constant, is InkoNameContext.Field -> true
            else -> false
        }

        /** Parakeet's definition whose name is written at [target] (a declaration's name, or a module's file). */
        fun definitionAt(target: PsiElement, symbols: InkoSymbols): InkoDefinition? {
            val targetFile = target.containingFile ?: return null
            val path = Inko.pathOf(targetFile.virtualFile) ?: return null
            if (target is PsiFile) return symbols.moduleOf(path)?.let(symbols::module)
            val name = target.text.removePrefix("@").removeSuffix("=")
            return (symbols.definedIn(path, name) + symbols.definedIn(path, "$name="))
                .firstOrNull { toPsi(targetFile, it) == target }
        }

        /** The element to navigate to: where the definition's name is defined, or its file for modules. */
        fun toPsi(context: PsiFile, definition: InkoDefinition): PsiElement? {
            val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(definition.file) ?: return null
            val psiFile = PsiManager.getInstance(context.project).findFile(virtualFile) ?: return null
            if (definition.kind == Kind.MODULE) return psiFile
            val document = psiFile.viewProvider.document ?: return psiFile
            return InkoDefinitionSite.find(psiFile, definition, Inko.offsetOf(document, definition.line, definition.column))
        }
    }
}

/**
 * Finds where a definition's name is written. `inko doc` locations can start
 * at a doc comment, or (for built-in types like `String`) at the top of the
 * file, so search from the location for the name right after a defining
 * keyword (`type`, `trait`, `fn`, `let`, `case`, with any modifiers between).
 */
object InkoDefinitionSite {
    private val DEFINING_KEYWORDS = setOf("type", "trait", "fn", "let", "case")

    fun find(file: PsiFile, definition: InkoDefinition, from: Int): PsiElement {
        val text = if (definition.kind == Kind.FIELD) "@${definition.name}" else definition.name
        val candidates = PsiTreeUtil.findChildrenOfType(file, InkoName::class.java).filter { it.text == text && isDefined(it) }
        return candidates.firstOrNull { it.textOffset >= from }
            ?: candidates.firstOrNull()
            ?: file.findElementAt(from)
            ?: file
    }

    private fun isDefined(name: InkoName): Boolean {
        var token = name.previousToken()
        while (token.isToken(InkoTokens.KEYWORD)) {
            if (token!!.text in DEFINING_KEYWORDS) return true
            token = token.previousToken()
        }
        return false
    }
}
