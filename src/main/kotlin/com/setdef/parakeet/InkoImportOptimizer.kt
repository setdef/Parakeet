package com.setdef.parakeet

import com.intellij.lang.ImportOptimizer
import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

/**
 * Optimize Imports (⌃⌥O / Ctrl+Alt+O): removes the imports the compiler
 * reports as unused (Cuttlebone's diagnostics), then sorts the imports with `inko fmt` (which is how
 * Inko orders them). Only the import block is changed.
 */
class InkoImportOptimizer : ImportOptimizer {
    override fun supports(file: PsiFile): Boolean = file is InkoFile

    override fun processFile(file: PsiFile): Runnable {
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return Runnable {}
        val text = document.immutableCharSequence
        val plan = InkoImportCleanup.plan(file, text) ?: return Runnable {}
        return object : ImportOptimizer.CollectingInfoRunnable {
            override fun run() {
                // Only if nothing changed since the plan was made.
                if (document.immutableCharSequence.toString() != text.toString()) return
                document.replaceString(plan.range.startOffset, plan.range.endOffset, plan.replacement)
                PsiDocumentManager.getInstance(file.project).commitDocument(document)
            }

            override fun getUserNotificationInfo(): String? = when (plan.removed) {
                0 -> if (plan.sorted) "Sorted imports" else null
                1 -> "Removed 1 unused import" + if (plan.sorted) ", sorted imports" else ""
                else -> "Removed ${plan.removed} unused imports" + if (plan.sorted) ", sorted imports" else ""
            }
        }
    }
}

object InkoImportCleanup {
    /** Replace [range] of the file with [replacement]: [removed] unused imports gone, and [sorted] by `inko fmt`. */
    class Plan(val range: TextRange, val replacement: String, val removed: Int, val sorted: Boolean)

    private val UNUSED = Regex("""the symbol '([^']+)' is unused""")

    fun plan(file: PsiFile, text: CharSequence): Plan? {
        val statements = InkoImports.parse(text).statements
        if (statements.isEmpty()) return null
        // A scratch copy edited outside a write action, so nothing touches the real file until the plan is applied.
        val scratch = DocumentImpl(text.toString(), true)
        // Whole lines, from the first import's to the last one's (with its line break).
        val start = scratch.getLineStartOffset(scratch.getLineNumber(statements.first().range.startOffset))
        val lastLine = scratch.getLineNumber(statements.last().range.endOffset)
        val end = minOf(scratch.getLineEndOffset(lastLine) + 1, scratch.textLength)

        // Cuttlebone's diagnostics are for the document as it is, which [text] is.
        val unused = CuttleboneQueries.offTheEdt(file.project, "Finding Unused Imports") { CuttleboneQueries.getInstance(file.project).diagnostics(file) }?.value.orEmpty()
            .mapNotNull { InkoDiagnostic.of(scratch, it) }
            .filter { it.id == "unused-symbol" }
            .mapNotNull { diagnostic -> UNUSED.find(diagnostic.message)?.let { it.groupValues[1] to diagnostic.range.startOffset } }
            .filter { (_, offset) -> offset in start until end }
            // Back to front, so each edit leaves the earlier offsets in place.
            .sortedByDescending { it.second }
        for ((name, offset) in unused) {
            val import = InkoImports.parse(scratch.charsSequence).at(offset) ?: continue
            if (name in import.symbols) InkoImportEdits.removeSymbol(scratch, import, name) else InkoImportEdits.removeStatement(scratch, import)
        }

        val newEnd = end + (scratch.textLength - text.length)
        val block = scratch.charsSequence.subSequence(start, newEnd).toString()
        val sorted = sort(file, block)
        val replacement = sorted ?: block
        if (replacement == text.subSequence(start, end).toString()) return null
        return Plan(TextRange(start, end), replacement, unused.size, sorted != null && sorted != block)
    }

    /** [block] formatted by `inko fmt`, if it holds only imports, comments, and blank lines. */
    private fun sort(file: PsiFile, block: String): String? {
        if (block.isBlank()) return null
        // Lines inside an import statement (which may span lines), else blank or a comment.
        val document = DocumentImpl(block, true)
        val importLines = InkoImports.parse(block).statements
            .flatMap { document.getLineNumber(it.range.startOffset)..document.getLineNumber(it.range.endOffset) }
            .toSet()
        val onlyImports = block.lines().withIndex().all { (index, line) ->
            index in importLines || line.isBlank() || line.trimStart().startsWith("#")
        }
        if (!onlyImports) return null
        val result = InkoCli.getInstance(file.project).run(listOf("fmt", "-"), null, block)?.takeIf { it.succeeded } ?: return null
        // inko fmt ends the text with one line break; keep the block's own ending.
        val formatted = result.stdout.trimEnd('\n')
        return if (block.endsWith("\n")) "$formatted\n" else formatted
    }
}
