package com.setdef.parakeet

import com.intellij.openapi.util.TextRange

/** One `import` statement: `import a.b`, `import a.b (X, Y as Z)`, or `import a.b (self)`. */
class InkoImport(
    val module: String,
    val range: TextRange,
    /** The text inside the parentheses, if any. */
    val symbolsRange: TextRange?,
    /** Local name to original name; `self` refers to the module itself. */
    val symbols: Map<String, String>,
) {
    /** The name this import makes the module itself available as, if any (e.g. `b` for `import a.b`). */
    val moduleAlias: String? = when {
        symbolsRange == null -> module.substringAfterLast('.')
        else -> symbols.entries.firstOrNull { it.value == "self" }?.key
    }

    fun isInSymbols(offset: Int): Boolean = symbolsRange != null && offset >= symbolsRange.startOffset
}

/** The `import` statements of an Inko file, and what names they bring into scope. */
class InkoImports(private val imports: List<InkoImport>) {
    val statements: List<InkoImport> get() = imports

    /** The import statement containing [offset], if any. */
    fun at(offset: Int): InkoImport? = imports.firstOrNull { it.range.contains(offset) }

    /** The module that [alias] refers to, e.g. `a.b` for `b` after `import a.b`. */
    fun moduleAliasedAs(alias: String): String? = imports.firstOrNull { it.moduleAlias == alias }?.module

    /** The module and original name of an imported symbol known locally as [name]. */
    fun symbol(name: String): Pair<String, String>? =
        imports.firstNotNullOfOrNull { import -> import.symbols[name]?.let { import.module to it } }

    /** Original names of every symbol imported by name. */
    val symbolNames: Set<String> get() = imports.flatMap { it.symbols.values }.toSet()

    companion object {
        private val IMPORT = Regex("""(?m)^[ \t]*import\s+([A-Za-z_][\w.]*)(?:\s*\(([^)]*)\))?""")
        private val AS = Regex("\\s+as\\s+")

        fun parse(text: CharSequence): InkoImports = InkoImports(IMPORT.findAll(text).map { match ->
            val symbolsGroup = match.groups[2]
            val symbols = symbolsGroup?.value?.split(',')?.mapNotNull { part ->
                val pieces = part.trim().split(AS)
                val original = pieces.first().trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                (pieces.getOrNull(1)?.trim() ?: original) to original
            }?.toMap().orEmpty()
            InkoImport(
                module = match.groupValues[1],
                range = TextRange(match.range.first, match.range.last + 1),
                symbolsRange = symbolsGroup?.range?.let { TextRange(it.first, it.last + 1) },
                symbols = symbols,
            )
        }.toList())
    }
}
