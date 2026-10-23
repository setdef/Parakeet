package com.setdef.parakeet

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.editorActions.CopyPastePostProcessor
import com.intellij.codeInsight.editorActions.TextBlockTransferableData
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.Serializable
import javax.swing.JComponent

/**
 * An import the copied code relies on: [local] (as the code writes it) is
 * [name] from [module]. A [name] of `self` means the module itself, used as
 * `local.something`.
 */
data class InkoNeededImport(val module: String, val name: String, val local: String) : Serializable {
    override fun toString(): String = when {
        name == "self" -> "import $module"
        name == local -> "import $module ($name)"
        else -> "import $module ($name as $local)"
    }
}

class InkoImportsTransferable(val imports: List<InkoNeededImport>) : TextBlockTransferableData, Serializable {
    override fun getFlavor(): DataFlavor = FLAVOR

    companion object {
        val FLAVOR = DataFlavor(InkoImportsTransferable::class.java, "Inko imports")
    }
}

/**
 * Imports on paste: copying Inko code remembers which imported (or
 * module-level) names it uses, and pasting it into another module adds the
 * imports that module lacks. Follows Settings | Editor | General | Auto Import,
 * "Insert imports on paste" (always, ask, or never).
 */
class InkoPasteImportsProcessor : CopyPastePostProcessor<InkoImportsTransferable>() {
    override fun collectTransferableData(file: PsiFile, editor: Editor, startOffsets: IntArray, endOffsets: IntArray): List<InkoImportsTransferable> {
        if (file !is InkoFile) return emptyList()
        val ranges = startOffsets.indices.map { TextRange(startOffsets[it], endOffsets[it]) }
        val needed = InkoPasteImports.needed(file, ranges)
        return if (needed.isEmpty()) emptyList() else listOf(InkoImportsTransferable(needed))
    }

    override fun extractTransferableData(content: Transferable): List<InkoImportsTransferable> {
        if (!content.isDataFlavorSupported(InkoImportsTransferable.FLAVOR)) return emptyList()
        return listOfNotNull(content.getTransferData(InkoImportsTransferable.FLAVOR) as? InkoImportsTransferable)
    }

    override fun processTransferableData(
        project: Project,
        editor: Editor,
        bounds: RangeMarker,
        caretOffset: Int,
        indented: Ref<in Boolean>,
        values: List<InkoImportsTransferable>,
    ) {
        val document = editor.document
        PsiDocumentManager.getInstance(project).commitDocument(document)
        val file = PsiDocumentManager.getInstance(project).getPsiFile(document) as? InkoFile ?: return
        val missing = InkoPasteImports.missing(file, values.flatMap { it.imports })
        if (missing.isEmpty()) return
        val chosen = when (CodeInsightSettings.getInstance().ADD_IMPORTS_ON_PASTE) {
            CodeInsightSettings.YES -> missing
            CodeInsightSettings.NO -> return
            else -> if (ApplicationManager.getApplication().isUnitTestMode) missing else InkoRestoreImportsDialog(project, missing).chosen() ?: return
        }
        if (chosen.isEmpty()) return
        WriteCommandAction.runWriteCommandAction(project, "Restore Imports", null, {
            chosen.forEach { InkoPasteImports.add(document, it) }
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }, file)
    }
}

object InkoPasteImports {
    /** The imports the names in [ranges] of [file] rely on. */
    fun needed(file: PsiFile, ranges: List<TextRange>): List<InkoNeededImport> {
        val module = Inko.pathOf(file.virtualFile)?.let(Inko::moduleName)
        val imports = InkoImports.parse(file.text)
        val topLevel = file.inkoOutline.roots.filter { it.kind != InkoOutline.Kind.IMPL }.map { it.label }.toSet()
        val result = mutableListOf<InkoNeededImport>()
        for (name in PsiTreeUtil.findChildrenOfType(file, InkoName::class.java)) {
            if (ranges.none { it.contains(name.textRange) }) continue
            if (name is InkoDeclaration || name.previousToken().isToken(InkoTokens.DOT)) continue
            if (imports.at(name.textRange.startOffset) != null) continue
            if (InkoNameContext.of(name, imports) is InkoNameContext.Local) continue
            val text = name.text
            val imported = imports.symbol(text)
            when {
                imported != null -> result += InkoNeededImport(imported.first, imported.second, text)
                name.nextToken().isToken(InkoTokens.DOT) && imports.moduleAliasedAs(text) != null ->
                    result += InkoNeededImport(imports.moduleAliasedAs(text)!!, "self", text)
                module != null && text in topLevel -> result += InkoNeededImport(module, text, text)
            }
        }
        return result.distinct()
    }

    /** Of [needed], those [file] doesn't already have (or define, or is the module of). */
    fun missing(file: PsiFile, needed: List<InkoNeededImport>): List<InkoNeededImport> {
        val module = Inko.pathOf(file.virtualFile)?.let(Inko::moduleName)
        val imports = InkoImports.parse(file.text)
        val topLevel = file.inkoOutline.roots.filter { it.kind != InkoOutline.Kind.IMPL }.map { it.label }.toSet()
        return needed.distinct().filter { import ->
            when {
                import.module == module -> false
                import.name == "self" -> imports.moduleAliasedAs(import.local) == null
                else -> imports.symbol(import.local) == null && import.local !in topLevel
            }
        }
    }

    fun add(document: Document, import: InkoNeededImport) {
        if (import.name != "self") {
            InkoImportEdits.add(document, import.module, if (import.name == import.local) import.name else "${import.name} as ${import.local}")
            return
        }
        val imports = InkoImports.parse(document.charsSequence)
        val line = if (import.local == import.module.substringAfterLast('.')) "import ${import.module}" else "import ${import.module} (self as ${import.local})"
        when {
            imports.statements.isNotEmpty() -> document.insertString(imports.statements.last().range.endOffset, "\n$line")
            else -> document.insertString(0, "$line\n\n")
        }
    }
}

/** Asks which imports to add on paste, all ticked, like IntelliJ's Java "Select Import to Restore". */
class InkoRestoreImportsDialog(project: Project, private val imports: List<InkoNeededImport>) : DialogWrapper(project, true) {
    private val list = CheckBoxList<InkoNeededImport>().apply { imports.forEach { addItem(it, it.toString(), true) } }

    init {
        title = "Select Imports to Restore"
        init()
    }

    override fun createCenterPanel(): JComponent = JBScrollPane(list).apply { preferredSize = JBUI.size(400, 200) }

    /** The ticked imports, or null when cancelled. */
    fun chosen(): List<InkoNeededImport>? = if (showAndGet()) imports.filter { list.isItemSelected(it) } else null
}
