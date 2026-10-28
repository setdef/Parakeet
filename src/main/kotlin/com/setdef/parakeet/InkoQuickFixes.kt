package com.setdef.parakeet

import com.intellij.codeInsight.daemon.QuickFixActionRegistrar
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import org.eclipse.lsp4j.CodeAction

/**
 * Cuttlebone's quick fixes for the compiler's diagnostics (`textDocument/codeAction`), as lazy quick fixes: IntelliJ
 * asks for them in the background after highlighting, for the diagnostics on screen, rather than every pass asking
 * for every diagnostic's. Each fix costs Cuttlebone a check of the documents with it applied.
 */
object InkoQuickFixes {
    /** The diagnostic IDs Cuttlebone has fixes for (docs/lsp.md, "textDocument/codeAction"). */
    private val FIXABLE = setOf(
        "invalid-symbol", "unused-symbol", "unused-result", "invalid-assign", "missing-field", "invalid-implementation", "missing-trait", "unreachable",
    )

    fun fixable(diagnostic: InkoDiagnostic): Boolean = diagnostic.id in FIXABLE

    /** Registers Cuttlebone's fixes for [diagnostic] in [file], as the documents are now. */
    fun register(file: PsiFile, diagnostic: InkoDiagnostic, registrar: QuickFixActionRegistrar) {
        val document = file.viewProvider.document ?: return
        val stamp = document.modificationStamp
        val actions = CuttleboneQueries.getInstance(file.project).codeActions(file, diagnostic.range)?.value ?: return
        for (action in actions) {
            // The range's fixes are those of every diagnostic there: only this one's.
            val fixes = action.diagnostics.orEmpty().any { fixed ->
                fixed.code?.let { if (it.isLeft) it.left else it.right.toString() } == diagnostic.id &&
                    CuttleboneQueries.offset(document, fixed.range.start) == diagnostic.range.startOffset
            }
            if (fixes && action.edit?.changes?.isNotEmpty() == true) registrar.register(CuttleboneFix(action, file, stamp))
        }
    }
}

/**
 * One of Cuttlebone's fixes: its edits, to whichever files they're for. Only for the text it was asked about: once
 * the file changes, it's no longer offered.
 */
class CuttleboneFix(private val action: CodeAction, file: PsiFile, private val stamp: Long) : IntentionAction, PriorityAction {
    private val document = file.viewProvider.document

    override fun getText(): String = action.title

    override fun getFamilyName(): String = action.title

    override fun getPriority(): PriorityAction.Priority = if (action.isPreferred == true) PriorityAction.Priority.HIGH else PriorityAction.Priority.NORMAL

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        file is InkoFile && file.viewProvider.document === document && document?.modificationStamp == stamp

    override fun startInWriteAction(): Boolean = false

    // The edits are Cuttlebone's, for the real documents: there's nothing to apply to a preview's copy.
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val changes = action.edit?.changes.orEmpty().mapNotNull { (uri, edits) ->
            val target = VfsUtil.findFileByURL(java.net.URI(uri).toURL()) ?: return@mapNotNull null
            FileDocumentManager.getInstance().getDocument(target)?.let { it to edits }
        }
        val files = changes.mapNotNull { (document, _) -> FileDocumentManager.getInstance().getFile(document)?.let(PsiManager.getInstance(project)::findFile) }
        WriteCommandAction.writeCommandAction(project, *files.toTypedArray()).withName(action.title).run<RuntimeException> {
            for ((document, edits) in changes) {
                // From the end, so each edit's position is still where Cuttlebone said.
                val ranges = edits.mapNotNull { edit ->
                    val start = CuttleboneQueries.offset(document, edit.range.start) ?: return@mapNotNull null
                    val end = CuttleboneQueries.offset(document, edit.range.end) ?: return@mapNotNull null
                    Triple(start, end, edit.newText)
                }
                for ((start, end, text) in ranges.sortedByDescending { it.first }) document.replaceString(start, end, text)
                PsiDocumentManager.getInstance(project).commitDocument(document)
            }
        }
    }
}
