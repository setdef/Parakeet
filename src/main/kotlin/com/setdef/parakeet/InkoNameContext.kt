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

    /** `value.name` */
    data class InstanceMember(val name: String) : InkoNameContext()

    /** `alias` in `alias.something`, where `alias` is an imported module. */
    data class ModuleReference(val module: String) : InkoNameContext()

    /** A capitalized name: a type, trait, constant, or enum constructor. */
    data class Constant(val name: String) : InkoNameContext()

    /** A lowercase name: a module method (or a local, which can't be resolved). */
    data class Identifier(val name: String) : InkoNameContext()

    /** A name being defined, e.g. after `fn` or `let`. */
    data object Definition : InkoNameContext()

    companion object {
        // Keywords that, right before a lowercase name, mean the name is being defined (`fn pub static new`, `let mut x`).
        private val DEFINING_KEYWORDS = arrayOf("fn", "let", "mut", "as", "pub", "async", "static", "move", "extern", "inline")

        fun of(name: InkoName, imports: InkoImports): InkoNameContext {
            val text = name.text
            val offset = name.textRange.startOffset
            imports.at(offset)?.let { import ->
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
                return InstanceMember(text)
            }
            if (name.tokenType == InkoTokens.CONSTANT) return Constant(text)
            if (previous.isKeyword(*DEFINING_KEYWORDS)) return Definition
            if (name.nextToken().isToken(InkoTokens.DOT)) {
                imports.moduleAliasedAs(text)?.let { return ModuleReference(it) }
            }
            return Identifier(text)
        }
    }
}
