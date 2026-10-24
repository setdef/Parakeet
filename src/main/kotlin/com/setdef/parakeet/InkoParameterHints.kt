package com.setdef.parakeet

import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.OwnBypassCollector
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import org.eclipse.lsp4j.InlayHintKind

/**
 * Parameter-name hints before positional arguments (`greet(name: 'Alice')`),
 * from Cuttlebone, which leaves out arguments already named or whose text is
 * the parameter's name.
 */
class InkoParameterHintsProvider : InlayHintsProvider {
    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? =
        if (file is InkoFile) Collector() else null

    private class Collector : OwnBypassCollector {
        override fun collectHintsForFile(file: PsiFile, sink: InlayTreeSink) {
            val tooltip = InkoParameterHints.tooltip(file)
            for ((offset, name) in InkoParameterHints.inFile(file)) {
                sink.addPresentation(InlineInlayPosition(offset, false, 0), null, tooltip, HintFormat.default) { text("$name:", null) }
            }
        }
    }
}

/** The types the compiler infers for `let` bindings and closure arguments that don't write one, from Cuttlebone. */
class InkoTypeHintsProvider : InlayHintsProvider {
    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? =
        if (file is InkoFile) Collector() else null

    private class Collector : OwnBypassCollector {
        override fun collectHintsForFile(file: PsiFile, sink: InlayTreeSink) {
            val tooltip = InkoParameterHints.tooltip(file)
            for ((offset, label) in InkoParameterHints.types(file)) {
                sink.addPresentation(InlineInlayPosition(offset, true, 0), null, tooltip, HintFormat.default) { text(label, null) }
            }
        }
    }
}

object InkoParameterHints {
    /**
     * [file]'s parameter-name hints, as (offset, name): Cuttlebone's fresh or stale answer (one request for
     * the whole file). Without one, none: hints only inform, and a guessed name would mislead.
     */
    fun inFile(file: PsiFile): List<Pair<Int, String>> =
        cuttlebone(file).orEmpty().filter { it.third == InlayHintKind.Parameter }.map { it.first to it.second.removeSuffix(":") }

    /** [file]'s inferred types, as (offset, `: Type`): only Cuttlebone knows them. */
    fun types(file: PsiFile): List<Pair<Int, String>> =
        cuttlebone(file).orEmpty().filter { it.third == InlayHintKind.Type }.map { it.first to it.second }

    /**
     * The tooltip for [file]'s hints: when Cuttlebone's are stale (from the last version that type checked,
     * where the code doesn't now), that they're possibly outdated. Else none.
     */
    fun tooltip(file: PsiFile): String? =
        CuttleboneQueries.OUTDATED.takeIf { CuttleboneQueries.getInstance(file.project).inlayHints(file)?.stale == true }

    /** Cuttlebone's hints for [file] as (offset, label, kind), or null without a fresh or stale answer. */
    private fun cuttlebone(file: PsiFile): List<Triple<Int, String, InlayHintKind?>>? {
        val hints = CuttleboneQueries.getInstance(file.project).inlayHints(file)?.value ?: return null
        val document = file.viewProvider.document ?: return null
        return hints.mapNotNull { hint ->
            val offset = CuttleboneQueries.offset(document, hint.position) ?: return@mapNotNull null
            val label = if (hint.label.isLeft) hint.label.left else hint.label.right.joinToString("") { it.value }
            Triple(offset, label, hint.kind)
        }
    }
}
