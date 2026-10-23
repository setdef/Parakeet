package com.setdef.parakeet

import com.intellij.openapi.util.TextRange

/**
 * One `import` statement, following Inko's `import` (ast/src/parser.rs):
 * `import a.b`, `import a.b (X, Y as Z)`, or `import a.b (self)`, each maybe
 * with build tags (`if unix and amd64`), or `import extern "m"`, which links a
 * C library and imports no module ([isExtern], with an empty [module]).
 */
class InkoImport(
    val module: String,
    /** The whole statement, build tags included. */
    val range: TextRange,
    /** The text inside the parentheses, if any. */
    val symbolsRange: TextRange?,
    /** Local name to original name; `self` refers to the module itself (`fmt` to `self` for `import std.fmt (self)`). */
    val symbols: Map<String, String>,
    /** `if tag and tag`, if the import has build tags. */
    val tagsRange: TextRange? = null,
    val isExtern: Boolean = false,
) {
    /** The name this import makes the module itself available as, if any (e.g. `b` for `import a.b`). */
    val moduleAlias: String? = when {
        isExtern -> null
        symbolsRange == null -> module.substringAfterLast('.')
        else -> symbols.entries.firstOrNull { it.value == "self" }?.key
    }

    fun isInSymbols(offset: Int): Boolean = symbolsRange != null && offset >= symbolsRange.startOffset && offset <= symbolsRange.endOffset

    fun isInTags(offset: Int): Boolean = tagsRange?.containsOffset(offset) == true
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
        private val IMPORT = Regex(
            """(?m)^[ \t]*import\s+(?:extern\s+(?:'[^'\n]*'|"[^"\n]*")|([A-Za-z_][\w.]*)(?:\s*\(([^)]*)\))?(\s+if\s+[a-z_]\w*(?:\s+and\s+[a-z_]\w*)*)?)""",
        )
        private val AS = Regex("\\s+as\\s+")

        fun parse(text: CharSequence): InkoImports = InkoImports(IMPORT.findAll(text).map { match ->
            val symbolsGroup = match.groups[2]
            val module = match.groupValues[1]
            val symbols = symbolsGroup?.value?.split(',')?.mapNotNull { part ->
                val pieces = part.trim().split(AS)
                val original = pieces.first().trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                // `self` without `as` imports the module under its last name (compiler/src/type_check/imports.rs).
                val local = pieces.getOrNull(1)?.trim() ?: if (original == "self") module.substringAfterLast('.') else original
                local to original
            }?.toMap().orEmpty()
            val tags = match.groups[3]
            InkoImport(
                module = module,
                range = TextRange(match.range.first, match.range.last + 1),
                symbolsRange = symbolsGroup?.range?.let { TextRange(it.first, it.last + 1) },
                symbols = symbols,
                tagsRange = tags?.range?.let { TextRange(it.first + (tags.value.length - tags.value.trimStart().length), it.last + 1) },
                isExtern = match.groups[1] == null,
            )
        }.toList())
    }
}
