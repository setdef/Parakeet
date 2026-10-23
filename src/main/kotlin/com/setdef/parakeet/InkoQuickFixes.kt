package com.setdef.parakeet

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

/** Quick fixes for `inko check` diagnostics, by ID. */
object InkoQuickFixes {
    private val UNUSED = Regex("""the symbol '([^']+)' is unused""")

    /** Fixes for [diagnostic], which covers [range] in [file]. */
    fun forDiagnostic(file: PsiFile, diagnostic: InkoDiagnostic, range: TextRange): List<IntentionAction> = when (diagnostic.id) {
        "invalid-symbol" -> InkoImportFix.forDiagnostic(file, diagnostic)
        "unused-symbol" -> unusedSymbol(file, diagnostic, range)
        "unused-result" -> listOf(DiscardResultFix(range.startOffset))
        else -> emptyList()
    }

    private fun unusedSymbol(file: PsiFile, diagnostic: InkoDiagnostic, range: TextRange): List<IntentionAction> {
        val name = UNUSED.find(diagnostic.message)?.groupValues?.get(1) ?: return emptyList()
        val import = InkoImports.parse(file.text).at(range.startOffset)
        return when {
            import != null -> listOf(RemoveUnusedImportFix(name, range.startOffset))
            // The compiler ignores unused names starting with `_`.
            file.text.regionMatches(range.startOffset, name, 0, name.length) -> listOf(PrefixUnderscoreFix(name, range.startOffset))
            else -> emptyList()
        }
    }
}

/** A fix that edits the file's document. */
abstract class InkoDocumentFix : IntentionAction {
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = file is InkoFile

    override fun startInWriteAction(): Boolean = true

    override fun invoke(project: Project, editor: Editor?, file: PsiFile) {
        val document = file.viewProvider.document ?: return
        edit(file, document)
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }

    protected abstract fun edit(file: PsiFile, document: Document)
}

/** Removes an unused symbol from its import, or the whole import when nothing else is used from it. */
class RemoveUnusedImportFix(private val name: String, private val offset: Int) : InkoDocumentFix() {
    override fun getText(): String = "Remove unused import '$name'"

    override fun getFamilyName(): String = "Remove unused import"

    override fun edit(file: PsiFile, document: Document) {
        val import = InkoImports.parse(document.charsSequence).at(offset) ?: return
        if (name in import.symbols) InkoImportEdits.removeSymbol(document, import, name) else InkoImportEdits.removeStatement(document, import)
    }
}

/** Renames an unused local to `_name`, which the compiler doesn't warn about. */
class PrefixUnderscoreFix(private val name: String, private val offset: Int) : InkoDocumentFix() {
    override fun getText(): String = "Rename to _$name"

    override fun getFamilyName(): String = "Prefix unused name with _"

    override fun edit(file: PsiFile, document: Document) {
        document.insertString(offset, "_")
    }
}

/** Discards an unused result with `let _ =`, as the standard library does. */
class DiscardResultFix(private val offset: Int) : InkoDocumentFix() {
    override fun getText(): String = "Discard the result with let _ ="

    override fun getFamilyName(): String = text

    override fun edit(file: PsiFile, document: Document) {
        document.insertString(offset, "let _ = ")
    }
}
