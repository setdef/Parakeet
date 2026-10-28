package com.setdef.parakeet

import com.intellij.codeInsight.generation.ClassMember
import com.intellij.codeInsight.generation.MemberChooserObject
import com.intellij.codeInsight.generation.MemberChooserObjectBase
import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.icons.AllIcons
import com.intellij.ide.util.MemberChooser
import com.intellij.lang.LanguageCodeInsightActionHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.setdef.parakeet.InkoDefinitionKind as Kind

/**
 * An `impl Trait for Type` around a position, with the trait it implements and
 * how the trait's type parameters map to the impl's arguments (`T` → `Int` in
 * `impl Equal[Int] for Thing`).
 */
class InkoTraitImpl(val impl: InkoOutline.Node, val trait: InkoDefinition, private val substitutions: Map<String, String>, private val symbols: InkoSymbols) {
    private val implemented: Set<String> = impl.children.filter { it.kind == InkoOutline.Kind.METHOD }.map { it.label }.toSet()

    /** The trait's methods the impl doesn't define: [required] ones (no default) or optional ones (a default to override). */
    fun missing(required: Boolean): List<InkoDefinition> =
        symbols.ownedBy(trait.name, Kind.INSTANCE_METHOD, Kind.STATIC_METHOD)
            .filter { it.module == trait.module && it.required == required && it.name !in implemented }
            .distinctBy { it.name }
            .sortedBy { it.line }

    /** A method definition for [method], with the impl's type arguments in place of the trait's parameters. */
    fun stub(method: InkoDefinition): String {
        var signature = method.signature?.takeIf { it.startsWith("fn") } ?: "fn ${method.name}"
        for ((parameter, argument) in substitutions) signature = signature.replace(Regex("\\b${Regex.escape(parameter)}\\b"), argument)
        return "$signature {\n  panic('not implemented')\n}"
    }

    companion object {
        /** The innermost `impl Trait for Type` around [offset] in [file], if its trait is known. */
        fun at(file: PsiFile, offset: Int): InkoTraitImpl? {
            val path = Inko.pathOf(file.virtualFile) ?: return null
            val text = file.viewProvider.document?.charsSequence?.toString() ?: file.text
            val outline = InkoOutline.of(text)
            val impl = outline.enclosing(offset).lastOrNull { it.kind == InkoOutline.Kind.IMPL } ?: return null
            val traitName = outline.implTrait(impl) ?: return null
            val symbols = InkoDefinitionIndex.getInstance(file.project).symbols(Inko.projectRoot(path)) ?: return null
            val trait = InkoTraitLinks.trait(symbols, InkoImports.parse(text), traitName) ?: return null
            return InkoTraitImpl(impl, trait, substitutions(trait, impl), symbols)
        }

        // `trait Equal[T]` and `impl Equal[Int] for X` give T → Int.
        private fun substitutions(trait: InkoDefinition, impl: InkoOutline.Node): Map<String, String> {
            val parameters = typeArguments(trait.signature.orEmpty().substringAfter(trait.name, ""))
            val arguments = typeArguments(impl.label.removePrefix("impl ").trim().substringAfter(trait.name, ""))
            if (parameters.size != arguments.size) return emptyMap()
            return parameters.zip(arguments).filter { (parameter, argument) -> parameter != argument }.toMap()
        }

        // The comma-separated items of a leading `[…]`, respecting nested brackets: `[A, Map[K, V]]` → [A, Map[K, V]].
        private fun typeArguments(source: String): List<String> {
            // Outline labels space tokens out (`impl Show [Int] for X`).
            val text = source.trimStart()
            if (!text.startsWith("[")) return emptyList()
            val items = mutableListOf<String>()
            var depth = 0
            val current = StringBuilder()
            for (c in text) {
                when (c) {
                    '[' -> { if (depth > 0) current.append(c); depth++ }
                    ']' -> {
                        depth--
                        if (depth == 0) {
                            items += current.toString().trim()
                            return items.filter { it.isNotEmpty() }
                        }
                        current.append(c)
                    }
                    ',' -> if (depth == 1) { items += current.toString().trim(); current.clear() } else current.append(c)
                    else -> current.append(c)
                }
            }
            return emptyList()
        }
    }
}

/** A trait method in the Implement/Override Methods chooser, grouped under its trait. */
class InkoMethodMember(val definition: InkoDefinition) :
    MemberChooserObjectBase(definition.signature ?: definition.name, AllIcons.Nodes.Method), ClassMember {
    override fun getParentNodeDelegate(): MemberChooserObject = MemberChooserObjectBase(definition.owner.orEmpty(), AllIcons.Nodes.Interface)

    override fun equals(other: Any?): Boolean = other is InkoMethodMember && other.definition == definition

    override fun hashCode(): Int = definition.hashCode()
}

/**
 * Implement Methods (⌃I) for a trait's required methods, and Override Methods
 * (⌃O) for those with a default, in the `impl Trait for Type` at the caret.
 * Stubs call `panic`, so they compile until filled in.
 */
open class InkoTraitMethodsHandler(private val required: Boolean) : LanguageCodeInsightActionHandler {
    override fun isValidFor(editor: Editor, file: PsiFile): Boolean =
        file is InkoFile && InkoTraitImpl.at(file, editor.caretModel.offset) != null

    override fun startInWriteAction(): Boolean = false

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        val impl = InkoTraitImpl.at(file, editor.caretModel.offset)
        val candidates = impl?.missing(required).orEmpty()
        if (impl == null || candidates.isEmpty()) {
            val message = if (required) "No methods to implement" else "No methods to override"
            if (!ApplicationManager.getApplication().isUnitTestMode) HintManager.getInstance().showErrorHint(editor, message)
            return
        }
        val chosen = choose(project, candidates) ?: return
        if (chosen.isNotEmpty()) insert(project, editor, file, impl, chosen)
    }

    // Tests can't show the chooser, so they take every candidate.
    private fun choose(project: Project, candidates: List<InkoDefinition>): List<InkoDefinition>? {
        if (ApplicationManager.getApplication().isUnitTestMode) return candidates
        val members = candidates.map(::InkoMethodMember).toTypedArray()
        val chooser = MemberChooser(members, false, true, project)
        chooser.title = if (required) "Select Methods to Implement" else "Select Methods to Override"
        chooser.selectElements(if (required) members else emptyArray())
        if (!chooser.showAndGet()) return null
        return chooser.selectedElements.orEmpty().map { it.definition }
    }

    private fun insert(project: Project, editor: Editor, file: PsiFile, impl: InkoTraitImpl, methods: List<InkoDefinition>) {
        val document = editor.document
        WriteCommandAction.runWriteCommandAction(project, if (required) "Implement Methods" else "Override Methods", null, {
            val close = impl.impl.range.endOffset - 1
            val text = document.charsSequence
            val lineStart = text.lastIndexOf('\n', close - 1) + 1
            val stubs = methods.joinToString("\n\n") { method -> impl.stub(method).lines().joinToString("\n") { "  $it" } }
            val hasMethods = impl.impl.children.isNotEmpty()
            val (at, inserted) = if (text.substring(lineStart, close).isBlank()) {
                // The `}` is on its own line: add the methods above it.
                lineStart to (if (hasMethods) "\n" else "") + stubs + "\n"
            } else {
                // `impl Trait for Type {}`: open the body up.
                close to "\n$stubs\n"
            }
            document.insertString(at, inserted)
            PsiDocumentManager.getInstance(project).commitDocument(document)
            // Put the caret on the first stub's body, ready to replace.
            val body = document.charsSequence.indexOf("panic('not implemented')", at)
            if (body >= 0) editor.caretModel.moveToOffset(body)
        }, file)
    }
}

class InkoImplementMethodsHandler : InkoTraitMethodsHandler(required = true)

class InkoOverrideMethodsHandler : InkoTraitMethodsHandler(required = false)

/** For the compiler's "the method 'x' must be implemented for 'Y'": run Implement Methods on that impl. */
class InkoImplementMethodsFix(private val offset: Int) : IntentionAction {
    override fun getText(): String = "Implement missing methods"

    override fun getFamilyName(): String = text

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        editor != null && file is InkoFile && InkoTraitImpl.at(file, offset)?.missing(required = true)?.isNotEmpty() == true

    override fun startInWriteAction(): Boolean = false

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file == null) return
        editor.caretModel.moveToOffset(offset)
        InkoImplementMethodsHandler().invoke(project, editor, file)
    }
}
