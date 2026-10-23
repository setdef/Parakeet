package com.setdef.parakeet

import com.intellij.codeInsight.CodeInsightActionHandler
import com.intellij.codeInsight.actions.CodeInsightAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import org.jetbrains.annotations.TestOnly
import com.setdef.parakeet.InkoDefinitionKind as Kind

/** The type (or `impl … for Type`) around an offset, for Generate (⌘N). */
class InkoGenerateTarget(val node: InkoOutline.Node, val type: String, val isImpl: Boolean) {
    companion object {
        fun at(text: CharSequence, offset: Int): InkoGenerateTarget? {
            val outline = InkoOutline.of(text)
            val node = outline.enclosing(offset).lastOrNull { it.kind == InkoOutline.Kind.TYPE || it.kind == InkoOutline.Kind.IMPL } ?: return null
            return when (node.kind) {
                InkoOutline.Kind.TYPE -> InkoGenerateTarget(node, node.label, false)
                else -> outline.implTarget(node)?.let { InkoGenerateTarget(node, it, true) }
            }
        }
    }
}

/**
 * Generate | Constructor: `fn static new` taking every field and building the
 * type with them, the way the standard library writes it:
 * `fn pub static new(a: A) -> Self { Self(a: a) }`. Inko needs every field to
 * build a value, so there's nothing to choose.
 */
object InkoConstructors {
    class Field(val name: String, val type: String)

    /** The type's fields, from `let @name: Type` (and `let pub @name: Type`). */
    fun fields(text: CharSequence, type: InkoOutline.Node): List<Field> = type.children.filter { it.kind == InkoOutline.Kind.FIELD }.mapNotNull { node ->
        val lineEnd = text.indexOf('\n', node.nameOffset).let { if (it < 0) text.length else it }
        val declaration = text.subSequence(node.nameOffset, lineEnd).toString()
        val fieldType = declaration.substringAfter(':', "").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        Field(node.label.removePrefix("@"), fieldType)
    }

    /** Why there's no constructor to generate for [type], or null if there is. */
    fun unavailable(text: CharSequence, type: InkoOutline.Node): String? = when {
        fields(text, type).isEmpty() -> "This type has no fields to build a constructor from."
        type.children.any { it.kind == InkoOutline.Kind.METHOD && it.label == "new" } -> "This type already has a 'new' method."
        else -> null
    }

    /** The method's text, at the type's member indentation, and where it goes (after the last field). */
    fun plan(text: CharSequence, type: InkoOutline.Node): Pair<Int, String>? {
        val fields = fields(text, type).takeIf { it.isNotEmpty() } ?: return null
        val lastField = type.children.last { it.kind == InkoOutline.Kind.FIELD }
        val insertAt = text.indexOf('\n', lastField.nameOffset).let { if (it < 0) text.length else it }
        val lineStart = text.lastIndexOf('\n', lastField.nameOffset) + 1
        val indent = text.subSequence(lineStart, lastField.nameOffset).takeWhile { it == ' ' || it == '\t' }
        val public = Regex("""\bpub\b""").containsMatchIn(text.subSequence(type.range.startOffset, type.nameOffset))
        val parameters = fields.joinToString(", ") { "${it.name}: ${it.type}" }
        val arguments = fields.joinToString(", ") { "${it.name}: ${it.name}" }
        val method = "\n\n${indent}fn ${if (public) "pub " else ""}static new($parameters) -> Self {\n$indent  Self($arguments)\n$indent}"
        return insertAt to method
    }

    private fun CharSequence.indexOf(char: Char, from: Int): Int {
        for (i in from until length) if (this[i] == char) return i
        return -1
    }

    private fun CharSequence.lastIndexOf(char: Char, from: Int): Int {
        var i = minOf(from, length - 1)
        while (i >= 0) { if (this[i] == char) return i; i-- }
        return -1
    }
}

class InkoGenerateConstructorAction : CodeInsightAction() {
    override fun isValidForFile(project: Project, editor: Editor, file: PsiFile): Boolean =
        file is InkoFile && InkoGenerateTarget.at(editor.document.charsSequence, editor.caretModel.offset)?.isImpl == false

    override fun getHandler(): CodeInsightActionHandler = object : CodeInsightActionHandler {
        override fun invoke(project: Project, editor: Editor, file: PsiFile) {
            val text = editor.document.charsSequence
            val target = InkoGenerateTarget.at(text, editor.caretModel.offset)?.takeIf { !it.isImpl } ?: return
            InkoConstructors.unavailable(text, target.node)?.let { return CommonRefactoringUtil.showErrorHint(project, editor, it, "Generate Constructor", null) }
            val (offset, method) = InkoConstructors.plan(text, target.node) ?: return
            editor.document.insertString(offset, method)
            PsiDocumentManager.getInstance(project).commitDocument(editor.document)
            // On the method's name.
            editor.caretModel.moveToOffset(offset + method.indexOf("new("))
        }
    }
}

/**
 * Generate | Implement Trait: picks a trait the type doesn't implement yet
 * (from this project, its dependencies, or std), imports it if needed, adds
 * `impl Trait for Type {}` after the type, and stubs its required methods.
 */
class InkoGenerateTraitImplAction : CodeInsightAction() {
    override fun isValidForFile(project: Project, editor: Editor, file: PsiFile): Boolean =
        file is InkoFile && InkoGenerateTarget.at(editor.document.charsSequence, editor.caretModel.offset) != null

    override fun getHandler(): CodeInsightActionHandler = object : CodeInsightActionHandler {
        // It shows a popup, then edits in its own command.
        override fun startInWriteAction(): Boolean = false

        override fun invoke(project: Project, editor: Editor, file: PsiFile) {
            val target = InkoGenerateTarget.at(editor.document.charsSequence, editor.caretModel.offset) ?: return
            val traits = InkoTraitImpls.candidates(file, target.type)
            if (traits.isEmpty()) return CommonRefactoringUtil.showErrorHint(project, editor, "No traits to implement for '${target.type}'.", "Implement Trait", null)
            val chosen: (InkoDefinition) -> Unit = { trait -> InkoTraitImpls.add(project, editor, file, target.type, trait) }
            if (ApplicationManager.getApplication().isUnitTestMode) {
                (chooser ?: { it.first() })(traits)?.let(chosen)
                return
            }
            JBPopupFactory.getInstance().createPopupChooserBuilder(traits)
                .setTitle("Implement Trait for ${target.type}")
                .setRenderer(textListCellRenderer { it?.let { trait -> "${trait.name}  (${trait.module})" } })
                .setNamerForFiltering { it.name }
                .setItemChosenCallback(chosen)
                .createPopup()
                .showInBestPositionFor(editor)
        }
    }

    companion object {
        /** Picks a trait in tests, where there's no popup. */
        @TestOnly
        var chooser: ((List<InkoDefinition>) -> InkoDefinition?)? = null
    }
}

object InkoTraitImpls {
    /** Traits [type] could implement: public ones (or this module's), not already implemented here or per `inko doc`. */
    fun candidates(file: PsiFile, type: String): List<InkoDefinition> {
        val path = Inko.pathOf(file.virtualFile) ?: return emptyList()
        val root = Inko.projectRoot(path)
        val symbols = InkoDocIndex.getInstance(file.project).symbols(root) ?: return emptyList()
        val module = symbols.moduleOf(path) ?: Inko.moduleName(path)
        val outline = InkoOutline.of(file.text)
        val implemented = outline.roots.filter { it.kind == InkoOutline.Kind.IMPL && outline.implTarget(it) == type }.mapNotNull(outline::implTrait).toSet() +
            symbols.named(type, Kind.TYPE).filter { it.module == module }.flatMap { it.related }.map { it.name }
        return symbols.ofKind(Kind.TRAIT)
            .filter { (it.public || it.module == module) && it.name !in implemented }
            .filter { !it.file.startsWith(root.resolve("test")) }
            .distinctBy { it.module to it.name }
            .sortedWith(compareBy({ InkoImportFix.origin(root, it.file) }, { it.name }, { it.module }))
    }

    /** Imports [trait] if needed, adds `impl Trait for Type {}` after the type, and fills in its required methods. */
    fun add(project: Project, editor: Editor, file: PsiFile, type: String, trait: InkoDefinition) {
        val document = editor.document
        val path = Inko.pathOf(file.virtualFile)
        val module = path?.let { InkoDocIndex.getInstance(project).symbols(Inko.projectRoot(it))?.moduleOf(it) ?: Inko.moduleName(it) }
        var implStart = -1
        WriteCommandAction.runWriteCommandAction(project, "Implement Trait", null, {
            val imports = InkoImports.parse(document.charsSequence)
            if (trait.module != module && imports.symbol(trait.name) == null) InkoImportEdits.add(document, trait.module, trait.name)
            PsiDocumentManager.getInstance(project).commitDocument(document)
            val text = document.charsSequence
            val outline = InkoOutline.of(text)
            // After the type, and after any impls already following it.
            val typeNode = outline.roots.firstOrNull { it.kind == InkoOutline.Kind.TYPE && it.label == type }
            val after = outline.roots.filter { it.kind == InkoOutline.Kind.IMPL && outline.implTarget(it) == type }.maxOfOrNull { it.range.endOffset }
            val end = maxOf(typeNode?.range?.endOffset ?: -1, after ?: -1).takeIf { it >= 0 } ?: text.length
            val inserted = "\n\nimpl ${trait.name} for $type {\n}"
            document.insertString(end, inserted)
            PsiDocumentManager.getInstance(project).commitDocument(document)
            implStart = end + inserted.length - 1
        }, file)
        if (implStart < 0) return
        editor.caretModel.moveToOffset(implStart)
        InkoImplementMethodsHandler().invoke(project, editor, file)
    }
}
