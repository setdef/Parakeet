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

    override fun getVariants(): Array<Any> = emptyArray()

    // A renamed module file (`util.inko`) renames the module in import paths (`util`).
    override fun handleElementRename(newElementName: String): PsiElement =
        element.rename(newElementName.removeSuffix(".${Inko.EXTENSION}"))

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
        is InkoNameContext.InstanceMember -> instanceMember(context.name, context.receiverType)
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

        /** What [name] refers to: each definition, with the element where its name is written. */
        fun resolveDefinitions(name: InkoName): List<Pair<InkoDefinition, PsiElement>> {
            val file = name.containingFile ?: return emptyList()
            val path = Inko.pathOf(file.virtualFile) ?: return emptyList()
            val symbols = InkoDocIndex.getInstance(name.project).symbols(Inko.projectRoot(path)) ?: return emptyList()
            val imports = InkoImports.parse(file.text)
            val resolver = InkoResolver(symbols, symbols.moduleOf(path) ?: Inko.moduleName(path), imports, file, name.textRange.startOffset)
            return resolver.definitions(InkoNameContext.of(name, imports)).distinct().mapNotNull { definition ->
                toPsi(file, definition)?.let { definition to it }
            }
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
