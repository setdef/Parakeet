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

    /**
     * `value.name`; [receiverType] is the receiver's type when it's known (a typed local, or `self`). With
     * [assigned], it's `value.name = x`, which Inko's parser reads as a call of the setter `name=`
     * (ast/src/parser.rs, `call_with_receiver` and `assign_setter`). With [compound] too, it's `value.name += x`,
     * which calls the getter `name` and then the setter `name=` (`binary_assign_setter`).
     */
    data class InstanceMember(val name: String, val receiverType: String? = null, val assigned: Boolean = false, val compound: Boolean = false) : InkoNameContext()

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
                if (receiver.isToken(InkoTokens.IDENTIFIER) && !isLocal(receiver!!)) {
                    imports.moduleAliasedAs(receiver.text)?.let { return ModuleMember(it, text) }
                }
                if (receiver.isToken(InkoTokens.CONSTANT)) return StaticMember(receiver!!.text, text)
                val compound = isCompoundAssigned(name)
                return InstanceMember(text, receiver?.let { receiverType(it) }, compound || isAssigned(name), compound)
            }
            if (name.tokenType == InkoTokens.CONSTANT) return Constant(text)
            if (previous.isKeyword(*DEFINING_KEYWORDS)) return Definition
            // A named argument's label (`Page(module: module)`) names a parameter or field, and `name(…)` calls a
            // method (Inko calls a local closure with `.call`): neither is a local of that name.
            if (!isLabel(name) && !isCall(name)) {
                InkoLocals.visibleAt(name.containingFile.text, offset).lastOrNull { it.name == text }?.let { return Local(it) }
            }
            if (name.nextToken().isToken(InkoTokens.DOT)) {
                imports.moduleAliasedAs(text)?.let { return ModuleReference(it) }
            }
            return Identifier(text)
        }

        /** Whether [name] is a named argument's label: `(name: value` or `, name: value`, not `name := value`. */
        private fun isLabel(name: com.intellij.psi.PsiElement): Boolean {
            val previous = name.previousToken()
            if (!previous.isToken(InkoTokens.LPAREN) && !previous.isToken(InkoTokens.COMMA)) return false
            val colon = name.nextToken()?.takeIf { it.isToken(InkoTokens.OPERATOR) && it.text == ":" } ?: return false
            return !isReplaced(name) && colon.nextToken()?.let { it.isToken(InkoTokens.OPERATOR) && it.text == ":" } != true
        }

        /** Whether `(` follows [name] right away: `name(…)` calls a method. */
        private fun isCall(name: com.intellij.psi.PsiElement): Boolean =
            name.nextToken()?.let { it.isToken(InkoTokens.LPAREN) && it.textRange.startOffset == name.textRange.endOffset } == true

        /** Whether [name] is a local or parameter in scope there, which hides a module of that name. */
        private fun isLocal(name: com.intellij.psi.PsiElement): Boolean =
            InkoLocals.visibleAt(name.containingFile.text, name.textRange.startOffset).any { it.name == name.text }

        /** Whether a plain `=` (not `==` or `=>`) follows [name]: `recv.name = x`. */
        fun isAssigned(name: com.intellij.psi.PsiElement): Boolean {
            val next = name.nextToken()?.takeIf { it.isToken(InkoTokens.OPERATOR) && it.text == "=" } ?: return false
            val after = next.nextToken()
            return !(after.isToken(InkoTokens.OPERATOR) && after!!.textRange.startOffset == next.textRange.endOffset && after.text in setOf("=", ">"))
        }

        /** Whether a compound assignment (`+=`, `>>>=`, …) follows [name]: `recv.name += x`. */
        fun isCompoundAssigned(name: com.intellij.psi.PsiElement): Boolean {
            var operator = ""
            var token = name.nextToken()
            var end = -1
            while (token.isToken(InkoTokens.OPERATOR) && (end < 0 || token!!.textRange.startOffset == end) && operator.length < 4) {
                operator += token!!.text
                if (operator in InkoTokens.COMPOUND_ASSIGNMENTS) return true
                end = token.textRange.endOffset
                token = token.nextToken()
            }
            return false
        }

        /** Whether `:=` follows [name]: `x := y` assigns `x` and gives its old value. */
        fun isReplaced(name: com.intellij.psi.PsiElement): Boolean {
            val colon = name.nextToken()?.takeIf { it.isToken(InkoTokens.OPERATOR) && it.text == ":" } ?: return false
            val next = colon.nextToken()
            return next.isToken(InkoTokens.OPERATOR) && next!!.text == "=" && next.textRange.startOffset == colon.textRange.endOffset
        }

        /** Whether [name] is assigned here, as the compiler counts it: `=`, `:=`, or a compound assignment. */
        fun isAnyAssignment(name: com.intellij.psi.PsiElement): Boolean = isAssigned(name) || isReplaced(name) || isCompoundAssigned(name)

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
