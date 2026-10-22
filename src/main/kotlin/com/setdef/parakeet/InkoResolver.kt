package com.setdef.parakeet

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.ResolveResult
import com.intellij.psi.util.PsiTreeUtil
import java.nio.file.Path
import com.setdef.parakeet.InkoDefinitionKind as Kind

class InkoReference(element: InkoName) :
    PsiPolyVariantReferenceBase<InkoName>(element, TextRange(0, element.textLength), false) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        InkoResolver.resolve(element).map { PsiElementResolveResult(it) }.toTypedArray()

    override fun getVariants(): Array<Any> = emptyArray()
}

/** An `import` statement: `import a.b (X, Y as Z)` or `import a.b`. */
private class InkoImport(val module: String, val range: TextRange, val symbolsRange: TextRange?, val symbols: Map<String, String>) {
    /** The name this import makes the module itself available as, if any (e.g. `b` for `import a.b`). */
    val moduleAlias: String? = when {
        symbolsRange == null -> module.substringAfterLast('.')
        else -> symbols.entries.firstOrNull { it.value == "self" }?.key
    }
}

/**
 * Resolves names using the `inko doc` index plus the file's imports. Locations
 * are exact; which of several same-named methods is meant can't be known
 * without types, so method calls may resolve to several candidates.
 */
object InkoResolver {
    private val IMPORT = Regex("""(?m)^[ \t]*import\s+([A-Za-z_][\w.]*)(?:\s*\(([^)]*)\))?""")
    // Keywords that, right before a lowercase name, mean the name is being defined (`fn pub static new`, `let mut x`).
    private val DEFINING_KEYWORDS = setOf("fn", "let", "mut", "as", "pub", "async", "static", "move", "extern", "inline")

    fun resolve(name: InkoName): List<PsiElement> {
        val path = Inko.pathOf(name.containingFile?.virtualFile) ?: return emptyList()
        val symbols = InkoDocIndex.getInstance(name.project).symbols(Inko.projectRoot(path)) ?: return emptyList()
        val context = Context(name, path, symbols)
        return context.definitions().distinct().mapNotNull { toPsi(name, it) }
    }

    private class Context(val name: InkoName, path: Path, val symbols: InkoSymbols) {
        val text: String = name.containingFile.text
        val word: String = name.text.removePrefix("@")
        val module: String? = symbols.moduleOf(path) ?: Inko.moduleName(path)
        val imports: List<InkoImport> = IMPORT.findAll(text).map { match ->
            val symbolsGroup = match.groups[2]
            val symbols = symbolsGroup?.value?.split(',')?.mapNotNull { part ->
                val pieces = part.trim().split(Regex("\\s+as\\s+"))
                val original = pieces.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                (pieces.getOrNull(1)?.trim() ?: original) to original
            }?.toMap().orEmpty()
            InkoImport(
                match.groupValues[1],
                TextRange(match.range.first, match.range.last + 1),
                symbolsGroup?.range?.let { TextRange(it.first, it.last + 1) },
                symbols,
            )
        }.toList()

        private val previous: PsiElement? = PsiTreeUtil.prevVisibleLeaf(name)
        private val next: PsiElement? = PsiTreeUtil.nextVisibleLeaf(name)

        private fun PsiElement?.isDot() = this?.node?.elementType == InkoTokens.DOT

        fun definitions(): List<InkoDefinition> {
            val offset = name.textRange.startOffset
            imports.firstOrNull { offset in it.range.startOffset until it.range.endOffset }?.let { return inImport(it, offset) }
            if (name.text.startsWith("@")) return field()
            if (previous.isDot()) return member()
            if (name.node.firstChildNode?.elementType == InkoTokens.CONSTANT) return constant()
            if (previous?.node?.elementType == InkoTokens.KEYWORD && previous.text in DEFINING_KEYWORDS) return emptyList()
            return identifier()
        }

        private fun inImport(import: InkoImport, offset: Int): List<InkoDefinition> {
            val symbolsRange = import.symbolsRange
            if (symbolsRange != null && offset >= symbolsRange.startOffset) {
                val original = import.symbols[word] ?: word
                return inModule(import.module, original)
            }
            // A segment of the module path: resolve the path up to and including it.
            val pathStart = text.indexOf(import.module, import.range.startOffset)
            val prefix = text.substring(pathStart, name.textRange.endOffset)
            return listOfNotNull(symbols.module(prefix))
        }

        private fun inModule(module: String, name: String): List<InkoDefinition> =
            symbols.named(name).filter {
                it.module == module && it.kind in setOf(Kind.TYPE, Kind.TRAIT, Kind.CONSTANT, Kind.MODULE_METHOD)
            }

        private fun field(): List<InkoDefinition> {
            val fields = symbols.named(word).filter { it.kind == Kind.FIELD }
            return fields.filter { it.module == module }.ifEmpty { fields }
        }

        private fun member(): List<InkoDefinition> {
            val receiver = PsiTreeUtil.prevVisibleLeaf(previous!!)
            val receiverType = receiver?.node?.elementType
            if (receiverType == InkoTokens.IDENTIFIER) {
                imports.firstOrNull { it.moduleAlias == receiver.text }?.let { return inModule(it.module, word) }
            }
            if (receiverType == InkoTokens.CONSTANT) {
                val owners = typeNamed(receiver.text).map { it.name }.toSet() + receiver.text
                val statics = symbols.named(word).filter { it.kind in setOf(Kind.STATIC_METHOD, Kind.CONSTRUCTOR) }
                return statics.filter { it.owner in owners }.ifEmpty { statics }
            }
            val methods = symbols.named(word).filter { it.kind == Kind.INSTANCE_METHOD }
            val inScope = typesInScope()
            return methods.filter { it.owner in inScope }.ifEmpty { methods }
        }

        private fun constant(): List<InkoDefinition> {
            typeNamed(word).takeIf { it.isNotEmpty() }?.let { return it }
            return symbols.named(word).filter { it.kind == Kind.CONSTRUCTOR }
        }

        private fun typeNamed(name: String): List<InkoDefinition> {
            val kinds = setOf(Kind.TYPE, Kind.TRAIT, Kind.CONSTANT)
            symbols.named(name).filter { it.kind in kinds && it.module == module }.takeIf { it.isNotEmpty() }?.let { return it }
            for (import in imports) {
                val original = import.symbols[name] ?: continue
                inModule(import.module, original).takeIf { it.isNotEmpty() }?.let { return it }
            }
            return symbols.named(name).filter { it.kind in setOf(Kind.TYPE, Kind.TRAIT) }
        }

        private fun identifier(): List<InkoDefinition> {
            if (next.isDot()) {
                imports.firstOrNull { it.moduleAlias == word }?.let { return listOfNotNull(symbols.module(it.module)) }
            }
            symbols.named(word).filter { it.kind == Kind.MODULE_METHOD && it.module == module }
                .takeIf { it.isNotEmpty() }?.let { return it }
            for (import in imports) {
                val original = import.symbols[word] ?: continue
                inModule(import.module, original).takeIf { it.isNotEmpty() }?.let { return it }
            }
            return emptyList()
        }

        /** Types defined in this module, imported by name, or mentioned anywhere in the file. */
        private fun typesInScope(): Set<String> = buildSet {
            PsiTreeUtil.findChildrenOfType(name.containingFile, InkoName::class.java)
                .filter { it.node.firstChildNode?.elementType == InkoTokens.CONSTANT }
                .forEach { add(it.text) }
            imports.forEach { addAll(it.symbols.values) }
        }
    }

    private fun toPsi(context: PsiElement, definition: InkoDefinition): PsiElement? {
        val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(definition.file) ?: return null
        val psiFile = PsiManager.getInstance(context.project).findFile(virtualFile) ?: return null
        if (definition.kind == Kind.MODULE) return psiFile
        val document = psiFile.viewProvider.document ?: return psiFile
        val lineIndex = (definition.line - 1).coerceIn(0, maxOf(document.lineCount - 1, 0))
        val lineStart = document.getLineStartOffset(lineIndex)
        val lineEnd = document.getLineEndOffset(lineIndex)
        val from = (lineStart + definition.column - 1).coerceIn(lineStart, lineEnd)
        val line = document.charsSequence.subSequence(from, lineEnd).toString()
        val nameOffset = Regex("""(?<![\w@])@?${Regex.escape(definition.name)}(?![\w?])""").find(line)?.range?.first
        val offset = from + (nameOffset ?: 0)
        val leaf = psiFile.findElementAt(offset) ?: return psiFile
        return PsiTreeUtil.getParentOfType(leaf, InkoName::class.java, false) ?: leaf
    }
}
