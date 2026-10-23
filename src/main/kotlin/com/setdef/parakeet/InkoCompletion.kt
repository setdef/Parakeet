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
 * Basic completion: keywords, local variables, and names in scope, module paths
 * and symbols in imports, members after `.` (narrowed to the receiver's type
 * when it's known), and fields after `@`. Names come from the file's own tokens
 * (so unsaved declarations count) and the `inko doc` index.
 */
class InkoCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile as? InkoFile ?: return
        val before = file.viewProvider.document?.charsSequence?.subSequence(0, parameters.offset) ?: return
        val context = InkoCompletionContext.of(before)
        val sink = result.withPrefixMatcher(context.prefix)
        val completions = InkoCompletions(file).apply { offset = parameters.offset }
        val items = completions.items(context).distinctBy { it.lookupString to it.allLookupStrings }
        sink.addAllElements(items)
        if (context is InkoCompletionContext.Bare) {
            sink.addAllElements(completions.unimported(taken = items.map { it.lookupString }.toSet()))
        } else {
            sink.stopHere()
        }
    }
}

private class InkoCompletions(private val file: PsiFile) {
    private val path = Inko.pathOf(file.virtualFile)
    private val symbols: InkoSymbols? = path?.let(Inko::projectRoot)
        ?.takeIf { Files.isDirectory(it.resolve("src")) || Files.isRegularFile(it.resolve(Inko.MANIFEST)) }
        ?.let { InkoDocIndex.getInstance(file.project).symbols(it) }
    private val module: String? = path?.let { symbols?.moduleOf(it) ?: Inko.moduleName(it) }
    private val imports = InkoImports.parse(file.text)
    private val outline = file.inkoOutline

    /** The caret's offset, for finding locals in scope. */
    var offset: Int = 0

    fun items(context: InkoCompletionContext): List<LookupElement> = when (context) {
        is InkoCompletionContext.ImportPath -> symbols?.moduleNames.orEmpty().map { name ->
            LookupElementBuilder.create(name).withIcon(InkoIcons.FILE)
        }
        is InkoCompletionContext.ImportedSymbol ->
            symbols?.topLevelIn(context.module).orEmpty().map(::definition) + LookupElementBuilder.create("self").bold()
        is InkoCompletionContext.Field -> outline.all.filter { it.kind == InkoOutline.Kind.FIELD }.map { node ->
            LookupElementBuilder.create(node.label).withIcon(node.kind.icon)
        }
        is InkoCompletionContext.Member -> member(context)
        is InkoCompletionContext.Bare -> bare()
    }

    private fun member(context: InkoCompletionContext.Member): List<LookupElement> {
        val symbols = symbols ?: return emptyList()
        if (context.receiverType == InkoTokens.IDENTIFIER) {
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

    /** The receiver's type, when it's `self` or a local with a known type. */
    private fun receiverType(context: InkoCompletionContext.Member): String? = when {
        context.receiverType == InkoTokens.KEYWORD && context.receiver == "self" -> InkoLocals.selfType(file.text, offset)
        context.receiverType == InkoTokens.IDENTIFIER -> InkoLocals.visibleAt(file.text, offset).lastOrNull { it.name == context.receiver }?.type
        else -> null
    }

    private fun bare(): List<LookupElement> = buildList {
        InkoTokens.KEYWORDS.forEach { add(LookupElementBuilder.create(it).bold()) }
        InkoLocals.visibleAt(file.text, offset).forEach { local ->
            add(LookupElementBuilder.create(local.name).withIcon(AllIcons.Nodes.Variable).withTypeText(local.type))
        }
        outline.all.filter { it.kind != InkoOutline.Kind.FIELD && it.kind != InkoOutline.Kind.IMPL }.forEach { node ->
            add(LookupElementBuilder.create(node.label).withIcon(node.kind.icon))
        }
        for (import in imports.statements) {
            import.moduleAlias?.let { add(LookupElementBuilder.create(it).withIcon(InkoIcons.FILE).withTypeText(import.module)) }
            import.symbols.forEach { (local, original) ->
                if (original == "self") return@forEach
                val definition = symbols?.topLevelIn(import.module)?.firstOrNull { it.name == original }
                add(if (definition != null && local == original) definition(definition) else LookupElementBuilder.create(local).withTypeText(import.module))
            }
        }
        module?.let { symbols?.topLevelIn(it)?.forEach { definition -> add(definition(definition)) } }
    }

    /**
     * Public types, traits, constants, and module methods from other modules
     * that aren't imported yet (and aren't already offered as [taken]), below
     * what's in scope. Choosing one adds its `import`.
     */
    fun unimported(taken: Set<String>): List<LookupElement> {
        val symbols = symbols ?: return emptyList()
        val root = path?.let(Inko::projectRoot) ?: return emptyList()
        val inTest = path.startsWith(root.resolve("test"))
        return symbols.names.asSequence()
            .filter { it !in taken && imports.symbol(it) == null }
            .flatMap { symbols.named(it, Kind.TYPE, Kind.TRAIT, Kind.CONSTANT, Kind.MODULE_METHOD).asSequence() }
            .filter { it.public && it.module != module }
            // Test modules can't be imported from src/.
            .filter { inTest || !it.file.startsWith(root.resolve("test")) }
            .distinctBy { it.module to it.name }
            .map { definition ->
                val item = definition(definition).withInsertHandler { context, _ ->
                    InkoImportEdits.add(context.document, definition.module, definition.name)
                    PsiDocumentManager.getInstance(context.project).commitDocument(context.document)
                }
                // Below names in scope; the project's own modules first, then dependencies, then std.
                PrioritizedLookupElement.withPriority(item, -1.0 - InkoImportFix.origin(root, definition.file))
            }
            .toList()
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

