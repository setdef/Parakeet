package com.setdef.parakeet

/** What a name means where it appears, decided from the tokens around it and the file's imports. */
sealed class InkoNameContext {
    /** A segment of an import's module path; [module] is the path up to and including it. */
    data class ImportPath(val module: String) : InkoNameContext()

    /** A symbol listed in an import's parentheses. */
    data class ImportedSymbol(val module: String, val name: String) : InkoNameContext()

    /** `@name` */
    data class Field(val name: String) : InkoNameContext()

    /** `alias.name`, where `alias` is an imported module. */
    data class ModuleMember(val module: String, val name: String) : InkoNameContext()

    /** `Type.name` */
    data class StaticMember(val type: String, val name: String) : InkoNameContext()

    /** `value.name`; [receiverType] is the receiver's type when it's known (a typed local, or `self`). */
    data class InstanceMember(val name: String, val receiverType: String? = null) : InkoNameContext()

    /** A local variable or parameter in scope. */
    data class Local(val local: InkoLocal) : InkoNameContext()

    /** `alias` in `alias.something`, where `alias` is an imported module. */
    data class ModuleReference(val module: String) : InkoNameContext()

    /** A capitalized name: a type, trait, constant, or enum constructor. */
    data class Constant(val name: String) : InkoNameContext()

    /** A lowercase name that isn't a local: a method on `self` (inside a type), or a module method. */
    data class Identifier(val name: String) : InkoNameContext()

    /** A build tag in an import (`unix` in `import a (B) if unix`), which names no symbol. */
    data object BuildTag : InkoNameContext()

    /** A name being defined, e.g. after `fn` or `let`. */
    data object Definition : InkoNameContext()

    companion object {
        // Keywords that, right before a lowercase name, mean the name is being defined (`fn pub static new`, `let mut x`).
        private val DEFINING_KEYWORDS = arrayOf("fn", "let", "mut", "as", "pub", "async", "static", "move", "extern", "inline", "noinline")

        fun of(name: InkoName, imports: InkoImports): InkoNameContext {
            val text = name.text
            val offset = name.textRange.startOffset
            imports.at(offset)?.let { import ->
                if (import.isInTags(offset)) return BuildTag
                if (import.isInSymbols(offset)) return ImportedSymbol(import.module, import.symbols[text] ?: text)
                val file = name.containingFile.text
                return ImportPath(file.substring(file.indexOf(import.module, import.range.startOffset), name.textRange.endOffset))
            }
            if (name.tokenType == InkoTokens.FIELD) return Field(text.removePrefix("@"))

            val previous = name.previousToken()
            if (previous.isToken(InkoTokens.DOT)) {
                val receiver = previous!!.previousToken()
                if (receiver.isToken(InkoTokens.IDENTIFIER)) {
                    imports.moduleAliasedAs(receiver!!.text)?.let { return ModuleMember(it, text) }
                }
                if (receiver.isToken(InkoTokens.CONSTANT)) return StaticMember(receiver!!.text, text)
                return InstanceMember(text, receiver?.let { receiverType(it) })
            }
            if (name.tokenType == InkoTokens.CONSTANT) return Constant(text)
            if (previous.isKeyword(*DEFINING_KEYWORDS)) return Definition
            if (name.nextToken().isToken(InkoTokens.DOT)) {
                imports.moduleAliasedAs(text)?.let { return ModuleReference(it) }
            }
            InkoLocals.visibleAt(name.containingFile.text, offset).lastOrNull { it.name == text }?.let { return Local(it) }
            return Identifier(text)
        }

        /** The type of a receiver before `.`: a local with a known type, or `self`. */
        private fun receiverType(receiver: com.intellij.psi.PsiElement): String? {
            val file = receiver.containingFile.text
            val offset = receiver.textRange.startOffset
            return when {
                receiver.isKeyword("self") -> InkoLocals.selfType(file, offset)
                receiver.isToken(InkoTokens.IDENTIFIER) -> InkoLocals.visibleAt(file, offset).lastOrNull { it.name == receiver.text }?.type
                else -> null
            }
        }
    }
}
