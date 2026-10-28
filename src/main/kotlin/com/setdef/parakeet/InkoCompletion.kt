package com.setdef.parakeet

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import java.nio.file.Files
import com.setdef.parakeet.InkoDefinitionKind as Kind

/** Where completion was invoked, decided from the text before the caret. */
sealed class InkoCompletionContext {
    abstract val prefix: String

    /** `import std.st|` */
    data class ImportPath(override val prefix: String) : InkoCompletionContext()

    /** `import std.stdio (St|` */
    data class ImportedSymbol(val module: String, override val prefix: String) : InkoCompletionContext()

    /** `receiver.na|`; [receiverType] is the receiver token's type. */
    data class Member(val receiver: String, val receiverType: com.intellij.psi.tree.IElementType?, override val prefix: String) : InkoCompletionContext()

    /** `@na|` */
    data class Field(override val prefix: String) : InkoCompletionContext()

    /** Anything else. */
    data class Bare(override val prefix: String) : InkoCompletionContext()

    companion object {
        private val IMPORT_PATH = Regex("""^\s*import\s+([\w.]*)$""")
        private val IMPORT_SYMBOLS = Regex("""import\s+([\w.]+)\s*\(([^)]*)$""")
        // The partial name at the caret, which may be empty or just `@`.
        private val WORD = Regex("""@?(?:[A-Za-z_]\w*\??)?$""")

        fun of(before: CharSequence): InkoCompletionContext {
            val line = before.substring(before.lastIndexOf('\n') + 1)
            IMPORT_PATH.find(line)?.let { return ImportPath(it.groupValues[1]) }
            val word = WORD.find(before)?.value.orEmpty()
            IMPORT_SYMBOLS.find(before)?.let { return ImportedSymbol(it.groupValues[1], word) }
            if (word.startsWith("@")) return Field(word)
            val rest = before.subSequence(0, before.length - word.length)
            if (rest.endsWith(".")) {
                val receiver = lastToken(rest.subSequence(0, rest.length - 1))
                return Member(receiver?.second.orEmpty(), receiver?.first, word)
            }
            return Bare(word)
        }

        private fun lastToken(text: CharSequence): Pair<com.intellij.psi.tree.IElementType, String>? {
            val lexer = InkoLexer()
            lexer.start(text, 0, text.length, 0)
            var last: Pair<com.intellij.psi.tree.IElementType, String>? = null
            while (lexer.tokenType != null) {
                if (lexer.tokenType != InkoTokens.WHITE_SPACE) last = lexer.tokenType!! to text.substring(lexer.tokenStart, lexer.tokenEnd)
                lexer.advance()
            }
            return last
        }
    }
}

/**
 * Completion is Cuttlebone's (`textDocument/completion`): members after `.`, the names in scope with those to
 * import (choosing one adds its `import`), keywords, fields after `@`, and modules and symbols in an `import`.
 * Where Cuttlebone has no answer for the code being edited, Parakeet's own reading stands in for what typing needs:
 * locals and parameters in scope, keywords and the file's fields, and after `.` members by name.
 */
class InkoCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile as? InkoFile ?: return
        val before = file.viewProvider.document?.charsSequence?.subSequence(0, parameters.offset) ?: return
        val context = InkoCompletionContext.of(before)
        val queries = CuttleboneQueries.getInstance(file.project)
        // The list that pops up as a name is typed asks once typing pauses, as highlighting does: asking at each
        // keystroke cost the typing replay 107 completion requests for 223 keystrokes, where 18 were after `.`.
        if (parameters.isAutoPopup && context is InkoCompletionContext.Bare && !queries.awaitStill(file.viewProvider.document ?: return)) return
        val answer = queries.completion(file, parameters.offset) ?: return
        val completions = InkoCompletions(file).apply { offset = parameters.offset }
        if (!answer.editing) {
            val items = answer.value.orEmpty()
            // A field's label may have its `@` or not; the prefix is matched against the label.
            val prefix = if (context is InkoCompletionContext.Field && items.none { it.label.startsWith("@") }) context.prefix.removePrefix("@") else context.prefix
            val sink = result.withPrefixMatcher(prefix.substringAfterLast('.'))
            // Filtered as more is typed, not asked again. What more typing would add to import is only what didn't
            // make Cuttlebone's 100 for the shorter prefix.
            sink.addAllElements(items.map(completions::compiled))
            sink.stopHere()
            return
        }
        val sink = result.withPrefixMatcher(context.prefix)
        sink.addAllElements(completions.items(context).distinctBy { it.lookupString to it.allLookupStrings })
        sink.stopHere()
    }
}

private class InkoCompletions(private val file: PsiFile) {
    private val path = Inko.pathOf(file.virtualFile)
    private val symbols: InkoSymbols? = path?.let(Inko::projectRoot)
        ?.takeIf { Files.isDirectory(it.resolve("src")) || Files.isRegularFile(it.resolve(Inko.MANIFEST)) }
        ?.let { InkoDefinitionIndex.getInstance(file.project).symbols(it) }
    private val imports = InkoImports.parse(file.text)
    private val outline = file.inkoOutline

    /** The caret's offset, for finding locals in scope. */
    var offset: Int = 0

    /** Parakeet's own, where Cuttlebone has no answer for the code being edited. */
    fun items(context: InkoCompletionContext): List<LookupElement> = when (context) {
        is InkoCompletionContext.Field -> outline.all.filter { it.kind == InkoOutline.Kind.FIELD }.map { node ->
            LookupElementBuilder.create(node.label).withIcon(node.kind.icon)
        }
        is InkoCompletionContext.Member -> member(context)
        is InkoCompletionContext.Bare -> buildList {
            InkoTokens.KEYWORDS.forEach { add(LookupElementBuilder.create(it).bold()) }
            InkoLocals.visibleAt(file.text, offset).forEach { local ->
                add(LookupElementBuilder.create(local.name).withIcon(AllIcons.Nodes.Variable).withTypeText(local.type))
            }
        }
        // Cuttlebone answers in an `import` from its index of modules, without a check, so always.
        is InkoCompletionContext.ImportPath, is InkoCompletionContext.ImportedSymbol -> emptyList()
    }

    /** Members by name, from the definitions: of the receiver's type where it's written or obvious. */
    private fun member(context: InkoCompletionContext.Member): List<LookupElement> {
        val symbols = symbols ?: return emptyList()
        // A local named like a module hides it.
        if (context.receiverType == InkoTokens.IDENTIFIER && InkoLocals.visibleAt(file.text, offset).none { it.name == context.receiver }) {
            imports.moduleAliasedAs(context.receiver)?.let { module -> return symbols.topLevelIn(module).map(::definition) }
        }
        if (context.receiverType == InkoTokens.CONSTANT) {
            val type = imports.symbol(context.receiver)?.second ?: context.receiver
            return symbols.ownedBy(type, Kind.STATIC_METHOD, Kind.CONSTRUCTOR).map(::definition)
        }
        receiverType(context)?.let { type ->
            // A known receiver type narrows it to that type's methods and (public) fields.
            val owner = imports.symbol(type)?.second ?: type
            symbols.ownedBy(owner, Kind.INSTANCE_METHOD, Kind.FIELD).takeIf { it.isNotEmpty() }?.let { return it.map(::definition) }
        }
        // Without types, prefer methods of types this file mentions or imports.
        val inScope = outline.all.filter { it.kind == InkoOutline.Kind.TYPE || it.kind == InkoOutline.Kind.TRAIT }.map { it.label } +
            imports.symbolNames + Regex("""\b[A-Z]\w*""").findAll(file.text).map { it.value }
        val preferred = inScope.toSet().flatMap { symbols.ownedBy(it, Kind.INSTANCE_METHOD) }
        return preferred.ifEmpty { symbols.names.flatMap { symbols.named(it, Kind.INSTANCE_METHOD) } }.map(::definition)
    }

    /**
     * One of Cuttlebone's items: its label inserted, with what its `detail` says after the name, where it's from, and
     * the edits it comes with (an `import`) made when it's chosen. Grouped as Cuttlebone sorts them (`sortText`'s first
     * digit: locals, then the type's methods, the module's names, those to import, and keywords).
     */
    fun compiled(item: CompletionItem): LookupElement {
        val signature = item.detail
        val keyword = item.kind == CompletionItemKind.Keyword
        var element = LookupElementBuilder.create(item.label)
            .withIcon(if (keyword) null else icon(item.kind))
            .withTypeText(item.labelDetails?.description, true)
            .withTailText(signature?.substringAfter(" ${item.label}", "")?.takeIf { it.isNotBlank() }, true)
            .withBoldness(keyword)
        val imports = item.additionalTextEdits.orEmpty()
        if (imports.isNotEmpty()) element = element.withInsertHandler { context, _ ->
            val document = context.document
            val ranges = imports.mapNotNull { edit ->
                val start = CuttleboneQueries.offset(document, edit.range.start) ?: return@mapNotNull null
                val end = CuttleboneQueries.offset(document, edit.range.end) ?: return@mapNotNull null
                Triple(start, end, edit.newText)
            }
            // Before what was inserted (an import), so its positions still hold; from the end.
            for ((start, end, text) in ranges.filter { it.second <= context.startOffset }.sortedByDescending { it.first }) document.replaceString(start, end, text)
            PsiDocumentManager.getInstance(context.project).commitDocument(document)
        }
        val group = item.sortText?.firstOrNull()?.digitToIntOrNull() ?: return element
        return PrioritizedLookupElement.withPriority(element, -group.toDouble())
    }

    private fun icon(kind: CompletionItemKind?) = when (kind) {
        CompletionItemKind.Field -> AllIcons.Nodes.Field
        CompletionItemKind.Constant, CompletionItemKind.Value -> AllIcons.Nodes.Constant
        CompletionItemKind.Class -> AllIcons.Nodes.Class
        CompletionItemKind.Interface -> AllIcons.Nodes.Interface
        CompletionItemKind.Variable -> AllIcons.Nodes.Variable
        CompletionItemKind.Module -> InkoIcons.FILE
        CompletionItemKind.TypeParameter -> AllIcons.Nodes.Type
        CompletionItemKind.Function -> AllIcons.Nodes.Function
        else -> AllIcons.Nodes.Method
    }

    /** The receiver's type, when it's `self` or a local with a known type. */
    private fun receiverType(context: InkoCompletionContext.Member): String? = when {
        context.receiverType == InkoTokens.KEYWORD && context.receiver == "self" -> InkoLocals.selfType(file.text, offset)
        context.receiverType == InkoTokens.IDENTIFIER -> InkoLocals.visibleAt(file.text, offset).lastOrNull { it.name == context.receiver }?.type
        else -> null
    }

    private fun definition(definition: InkoDefinition): LookupElementBuilder =
        LookupElementBuilder.create(definition, definition.name)
            .withIcon(definition.icon)
            .withTypeText(listOfNotNull(definition.module, definition.owner).joinToString("."), true)
            // What follows the name in its signature, e.g. `(other: ref Self) -> Bool`.
            .withTailText(definition.signature?.substringAfter(" ${definition.name}", "")?.takeIf { it.isNotBlank() }, true)
}

/** Opens completion after typing `.` (members) or `@` (fields). */
class InkoCompletionAutoPopup : TypedHandlerDelegate() {
    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (file !is InkoFile || (charTyped != '.' && charTyped != '@')) return Result.CONTINUE
        AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
        return Result.STOP
    }
}

