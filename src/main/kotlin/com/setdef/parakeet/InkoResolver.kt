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
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        InkoResolver.resolve(element).map { PsiElementResolveResult(it) }.toTypedArray()

    // Searches ask this of every occurrence of a name: Cuttlebone's cached answers only (CuttleboneQueries.cachedOnly).
    override fun isReferenceTo(element: PsiElement): Boolean = CuttleboneQueries.cachedOnly { super.isReferenceTo(element) }

    override fun getVariants(): Array<Any> = emptyArray()

    // A renamed module file (`util.inko`) renames the module in import paths (`util`).
    // A setter's call (`recv.value = x`) names it without its `=`.
    override fun handleElementRename(newElementName: String): PsiElement {
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
        fun resolve(name: InkoName): List<PsiElement> =
            localDeclaration(name)?.let(::listOf) ?: resolveDefinitions(name).map { it.second }

        /** Where a local variable or parameter is declared, found from the file alone (no index needed). */
        private fun localDeclaration(name: InkoName): PsiElement? {
            if (name.tokenType != InkoTokens.IDENTIFIER) return null
            val file = name.containingFile ?: return null
            val local = (InkoNameContext.of(name, InkoImports.parse(file.text)) as? InkoNameContext.Local)?.local ?: return null
            val leaf = file.findElementAt(local.offset) ?: return null
            return PsiTreeUtil.getParentOfType(leaf, InkoName::class.java, false) ?: leaf
        }

        /**
         * What [name] refers to: each definition, with the element where its name
         * is written. Cuttlebone's answer when it has a fresh one (and [askCuttlebone]), else the heuristic.
         */
        fun resolveDefinitions(name: InkoName, askCuttlebone: Boolean = true): List<Pair<InkoDefinition, PsiElement>> {
            val file = name.containingFile ?: return emptyList()
            val path = Inko.pathOf(file.virtualFile) ?: return emptyList()
            val symbols = InkoDocIndex.getInstance(name.project).symbols(Inko.projectRoot(path)) ?: return emptyList()
            val imports = InkoImports.parse(file.text)
            val context = InkoNameContext.of(name, imports)
            if (askCuttlebone) compiled(name, file, context, symbols)?.let { return listOf(it) }
            val resolver = InkoResolver(symbols, symbols.moduleOf(path) ?: Inko.moduleName(path), imports, file, name.textRange.startOffset)
            return resolver.definitions(context).distinct().mapNotNull { definition ->
                toPsi(file, definition)?.let { definition to it }
            }
        }

        /**
         * The definition Cuttlebone's `textDocument/definition` gives for [name], as
         * Parakeet's own definition there, for the names the heuristic guesses at
         * (members, calls, constants, fields). Null when there's no fresh answer (a stale one never counts),
         * it's empty, or it points somewhere Parakeet doesn't know a definition.
         */
        private fun compiled(name: InkoName, file: PsiFile, context: InkoNameContext, symbols: InkoSymbols): Pair<InkoDefinition, PsiElement>? {
            if (!isGuessed(context)) return null
            val queries = CuttleboneQueries.getInstance(name.project)
            val location = queries.definition(file, name.textRange.startOffset)?.value?.firstOrNull() ?: return null
            val target = queries.target(location) ?: return null
            return definitionAt(target, symbols)?.let { it to target }
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
