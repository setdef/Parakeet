package com.setdef.parakeet

import com.intellij.openapi.editor.Document
import java.nio.file.Path

/** Editing `import` statements. */
object InkoImportEdits {
    /** 0 for the project's own modules, 1 for dependencies, 2 for everything else (the standard library). */
    fun origin(root: Path, file: Path): Int = when {
        file.startsWith(root.resolve("dep")) -> 1
        file.startsWith(root) -> 0
        else -> 2
    }

    /**
     * Imports [name] from [module]: into an existing `import module (…)`, else as a new line after the last import.
     * `self` imports the module itself (`import module`), unless it already is.
     */
    fun add(document: Document, module: String, name: String) {
        val imports = InkoImports.parse(document.charsSequence)
        if (name == "self") {
            if (imports.moduleAliasedAs(module.substringAfterLast('.')) == module) return
            val line = "import $module"
            if (imports.statements.isEmpty()) document.insertString(0, "$line\n\n") else document.insertString(imports.statements.last().range.endOffset, "\n$line")
            return
        }
        // Not into a tagged import: the name would only be imported where the tags hold.
        val existing = imports.statements.firstOrNull { it.module == module && it.symbolsRange != null && it.tagsRange == null }
        when {
            existing != null -> {
                val symbols = existing.symbolsRange!!
                val inside = document.charsSequence.subSequence(symbols.startOffset, symbols.endOffset).toString()
                val written = inside.trimEnd()
                val replacement = when {
                    inside.isBlank() -> name
                    // Over several lines, as `inko fmt` writes a long one (each line ends with a comma): a line of its own.
                    written.endsWith(",") && '\n' in inside -> {
                        val lastLine = written.substringAfterLast('\n')
                        "$written\n${lastLine.takeWhile { it == ' ' || it == '\t' }}$name,${inside.substring(written.length)}"
                    }
                    written.endsWith(",") -> "$written $name"
                    else -> "$written, $name"
                }
                document.replaceString(symbols.startOffset, symbols.endOffset, replacement)
            }
            imports.statements.isNotEmpty() -> document.insertString(imports.statements.last().range.endOffset, "\nimport $module ($name)")
            else -> document.insertString(0, "import $module ($name)\n\n")
        }
    }

    /** Removes the symbol known locally as [local] from [import], or the whole statement if it's the last one. */
    fun removeSymbol(document: Document, import: InkoImport, local: String) {
        val remaining = import.symbols.filterKeys { it != local }
        val symbols = import.symbolsRange
        if (symbols == null || remaining.isEmpty()) return removeStatement(document, import)
        val text = remaining.entries.joinToString(", ") { (localName, original) ->
            val written = if (original == "self") import.module.substringAfterLast('.') else original
            if (localName == written) original else "$original as $localName"
        }
        document.replaceString(symbols.startOffset, symbols.endOffset, text)
    }

    /** Removes [import]'s whole line. */
    fun removeStatement(document: Document, import: InkoImport) {
        val line = document.getLineNumber(import.range.startOffset)
        val start = document.getLineStartOffset(line)
        val end = minOf(document.getLineEndOffset(document.getLineNumber(import.range.endOffset)) + 1, document.textLength)
        document.deleteString(start, end)
        // The last import at the top of the file takes the blank line after it along.
        val text = document.charsSequence
        if (start == 0 && text.startsWith("\n")) document.deleteString(0, 1)
    }
}
