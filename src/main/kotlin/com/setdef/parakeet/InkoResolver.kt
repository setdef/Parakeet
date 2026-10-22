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
}

/**
 * Resolves names to definitions from the `inko doc` index. Locations are exact;
 * which of several same-named methods is meant can't be known without types, so
 * method calls may resolve to several candidates.
 */
class InkoResolver private constructor(
    private val symbols: InkoSymbols,
    private val module: String?,
    private val imports: InkoImports,
    private val file: PsiFile,
) {
    fun definitions(context: InkoNameContext): List<InkoDefinition> = when (context) {
        is InkoNameContext.ImportPath -> listOfNotNull(symbols.module(context.module))
        is InkoNameContext.ImportedSymbol -> inModule(context.module, context.name)
        is InkoNameContext.ModuleMember -> inModule(context.module, context.name)
        is InkoNameContext.ModuleReference -> listOfNotNull(symbols.module(context.module))
        is InkoNameContext.Field -> preferringThisModule(symbols.named(context.name, Kind.FIELD))
        is InkoNameContext.StaticMember -> staticMember(context.type, context.name)
        is InkoNameContext.InstanceMember -> instanceMember(context.name)
        is InkoNameContext.Constant -> typesNamed(context.name).ifEmpty { symbols.named(context.name, Kind.CONSTRUCTOR) }
        is InkoNameContext.Identifier -> function(context.name)
        InkoNameContext.Definition -> emptyList()
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
    private fun instanceMember(name: String): List<InkoDefinition> {
        val methods = symbols.named(name, Kind.INSTANCE_METHOD)
        val inScope = PsiTreeUtil.findChildrenOfType(file, InkoName::class.java)
            .filter { it.tokenType == InkoTokens.CONSTANT }
            .map { it.text }
            .toSet() + imports.symbolNames
        return methods.filter { it.owner in inScope }.ifEmpty { methods }
    }

    private fun function(name: String): List<InkoDefinition> =
        symbols.named(name, Kind.MODULE_METHOD).filter { it.module == module }.ifEmpty { imported(name) }

    companion object {
        fun resolve(name: InkoName): List<PsiElement> = resolveDefinitions(name).map { it.second }

        /** What [name] refers to: each definition, with the element where its name is written. */
        fun resolveDefinitions(name: InkoName): List<Pair<InkoDefinition, PsiElement>> {
            val file = name.containingFile ?: return emptyList()
            val path = Inko.pathOf(file.virtualFile) ?: return emptyList()
            val symbols = InkoDocIndex.getInstance(name.project).symbols(Inko.projectRoot(path)) ?: return emptyList()
            val imports = InkoImports.parse(file.text)
            val resolver = InkoResolver(symbols, symbols.moduleOf(path) ?: Inko.moduleName(path), imports, file)
            return resolver.definitions(InkoNameContext.of(name, imports)).distinct().mapNotNull { definition ->
                toPsi(file, definition)?.let { definition to it }
            }
        }

        /** The element to navigate to: where the definition's name is defined, or its file for modules. */
        private fun toPsi(context: PsiFile, definition: InkoDefinition): PsiElement? {
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
