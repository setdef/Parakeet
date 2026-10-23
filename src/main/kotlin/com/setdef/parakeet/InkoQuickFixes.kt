package com.setdef.parakeet

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

/** Quick fixes for `inko check` diagnostics, by ID. */
object InkoQuickFixes {
    private val UNUSED = Regex("""the symbol '([^']+)' is unused""")
    private val MISSING_FIELD = Regex("""the field '([^']+)' must be assigned a value""")
    private val MISSING_TRAIT = Regex("""the trait '([^']+)' isn't implemented for type '([^']+)'""")

    /** Fixes for [diagnostic], which covers [range] in [file]. */
    fun forDiagnostic(file: PsiFile, diagnostic: InkoDiagnostic, range: TextRange): List<IntentionAction> = when (diagnostic.id) {
        "invalid-symbol" -> InkoImportFix.forDiagnostic(file, diagnostic)
        "unused-symbol" -> unusedSymbol(file, diagnostic, range)
        "unused-result" -> listOf(DiscardResultFix(range.startOffset))
        "invalid-implementation" ->
            if ("must be implemented" in diagnostic.message) listOf(InkoImplementMethodsFix(range.startOffset)) else emptyList()
        "missing-field" -> MISSING_FIELD.find(diagnostic.message)?.let { listOf(AddMissingFieldFix(it.groupValues[1], range)) }.orEmpty()
        "missing-trait" -> MISSING_TRAIT.find(diagnostic.message)?.let { listOf(ImplementMissingTraitFix(it.groupValues[1], it.groupValues[2], range.startOffset)) }.orEmpty()
        "unreachable" -> listOf(RemoveUnreachableFix(range.startOffset))
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

/** `Point(x: 1)` missing `y` → `Point(x: 1, y: |)`, with the caret where the value goes. */
class AddMissingFieldFix(private val field: String, private val range: TextRange) : IntentionAction {
    override fun getText(): String = "Add field '$field'"

    override fun getFamilyName(): String = "Add missing field"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = editor != null && file is InkoFile

    override fun startInWriteAction(): Boolean = true

    override fun invoke(project: Project, editor: Editor?, file: PsiFile) {
        val document = editor?.document ?: return
        val stream = InkoTokenStream(document.charsSequence)
        // The call's closing parenthesis ends the diagnostic's range.
        val close = stream.tokens.indexOfLast { it.type == InkoTokens.RPAREN && it.end <= range.endOffset && it.start >= range.startOffset }
        val open = stream.match(close).takeIf { close > 0 && it >= 0 } ?: return
        val empty = close == open + 1
        val insertion = if (empty) "$field: " else ", $field: "
        val at = if (empty) stream.tokens[open].end else stream.tokens[close - 1].end
        document.insertString(at, insertion)
        editor.caretModel.moveToOffset(at + insertion.length)
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }
}

/**
 * Removes code the compiler says can't run: an unreachable `case` arm, or
 * everything from an unreachable statement to the end of its block (the
 * compiler only flags the first statement after a `return` or `throw`).
 */
class RemoveUnreachableFix(private val offset: Int) : InkoDocumentFix() {
    override fun getText(): String = "Remove unreachable code"

    override fun getFamilyName(): String = text

    override fun edit(file: PsiFile, document: Document) {
        val text = document.charsSequence
        val stream = InkoTokenStream(text)
        val first = stream.indexAt(offset).takeIf { it < stream.tokens.size } ?: return
        val last = if (stream.tokens[first].isKeyword("case")) {
            stream.statementEnd(first)
        } else {
            val block = stream.enclosingBlock(offset)
            val close = if (block < 0) -1 else stream.match(block)
            if (close < 0) stream.tokens.size - 1 else close - 1
        }
        val start = text.lastIndexOf('\n', stream.tokens[first].start - 1) + 1
        val end = text.indexOf('\n', stream.tokens[last].end).let { if (it < 0) text.length else it + 1 }
        document.deleteString(start, end)
    }
}

/**
 * For "the trait 'Format' isn't implemented for type 'Box'" (a trait another
 * one requires): adds `impl Format for Box` after the impl that needs it and
 * runs Implement Methods in it.
 */
class ImplementMissingTraitFix(private val trait: String, private val type: String, private val offset: Int) : IntentionAction {
    override fun getText(): String = "Implement '$trait' for '$type'"

    override fun getFamilyName(): String = "Implement missing trait"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = editor != null && file is InkoFile

    override fun startInWriteAction(): Boolean = false

    override fun invoke(project: Project, editor: Editor?, file: PsiFile) {
        val document = editor?.document ?: return
        val impl = InkoOutline.of(document.charsSequence).enclosing(offset).lastOrNull { it.kind == InkoOutline.Kind.IMPL } ?: return
        val inserted = "\n\nimpl $trait for $type {\n}"
        WriteCommandAction.runWriteCommandAction(project, text, null, {
            document.insertString(impl.range.endOffset, inserted)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }, file)
        // Inside the new impl's braces, then fill it in.
        editor.caretModel.moveToOffset(impl.range.endOffset + inserted.length - 1)
        InkoImplementMethodsHandler().invoke(project, editor, file)
    }
}
