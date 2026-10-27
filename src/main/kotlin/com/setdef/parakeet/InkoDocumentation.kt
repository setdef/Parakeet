package com.setdef.parakeet

import com.intellij.icons.AllIcons
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.markdown.utils.doc.DocMarkdownToHtmlConverter
import com.intellij.model.Pointer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.PsiDocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.util.PsiTreeUtil
import java.nio.file.Files
import javax.swing.Icon
import com.setdef.parakeet.InkoDefinitionKind as Kind

/** Quick documentation (F1, or hovering) for Inko names, from the doc comments `inko doc` reports. */
class InkoDocumentationTargetProvider : PsiDocumentationTargetProvider {
    override fun documentationTarget(element: PsiElement, originalElement: PsiElement?): DocumentationTarget? {
        val definition = definitionFor(element, originalElement) ?: return null
        // On the declaration itself there's no use to give a type for.
        val original = originalElement?.takeIf { it.containingFile is InkoFile && !(it.containingFile == element.containingFile && element.textRange.contains(it.textRange)) }
        return InkoDocumentationTarget(element, definition, original)
    }

    /**
     * The definition of [element] (the target of navigation), found either by
     * resolving the name the caret was on ([originalElement]) or, on a
     * declaration itself, by looking up the definition written there.
     */
    private fun definitionFor(element: PsiElement, originalElement: PsiElement?): InkoDefinition? {
        val original = originalElement?.let { PsiTreeUtil.getParentOfType(it, InkoName::class.java, false) }
        if (original != null) {
            val resolved = InkoResolver.resolveDefinitions(original)
            resolved.firstOrNull { (_, target) -> target == element || target == element.parent }?.let { return it.first }
            if (resolved.isNotEmpty() && element is PsiFile) return resolved.first().first
        }
        val name = PsiTreeUtil.getParentOfType(element, InkoName::class.java, false) ?: return null
        val path = Inko.pathOf(name.containingFile?.virtualFile) ?: return null
        val root = Inko.projectRoot(path)
        // Only index real projects; a file elsewhere (e.g. the standard library) is reached by resolving instead.
        if (!Files.isDirectory(root.resolve("src"))) return null
        val symbols = InkoDocIndex.getInstance(element.project).symbols(root) ?: return null
        val document = name.containingFile.viewProvider.document ?: return null
        val line = document.getLineNumber(name.textOffset) + 1
        return symbols.definedIn(path, name.text.removePrefix("@")).filter { it.line <= line }.maxByOrNull { it.line }
    }
}

/** [original] is where the caret (or mouse) was, if not on the definition itself: its inferred type is shown too. */
class InkoDocumentationTarget(
    private val element: PsiElement,
    private val definition: InkoDefinition,
    private val original: PsiElement? = null,
) : DocumentationTarget {
    override fun createPointer(): Pointer<out DocumentationTarget> {
        val pointer = SmartPointerManager.createPointer(element)
        val originalPointer = original?.let { SmartPointerManager.createPointer(it) }
        val definition = definition
        return Pointer { pointer.element?.let { InkoDocumentationTarget(it, definition, originalPointer?.element) } }
    }

    override fun computePresentation(): TargetPresentation =
        TargetPresentation.builder(definition.name).icon(definition.icon).locationText(definition.module, null).presentation()

    override fun computeDocumentationHint(): String = header()

    // ⇧F1 (External Documentation) opens standard library definitions on docs.inko-lang.org, for the project's Inko.
    override fun computeDocumentation(): DocumentationResult {
        val documentation = DocumentationResult.documentation(html())
        val url = InkoStdDocs.url(definition, InkoCli.getInstance(element.project).version()) ?: return documentation
        return documentation.externalUrl(url)
    }

    fun html(): String = buildString {
        append(header(inferredType(), inferredTypeIsStale()))
        if (definition.documentation.isNotBlank()) {
            append(DocumentationMarkup.CONTENT_START)
            append(DocMarkdownToHtmlConverter.convert(element.project, definition.documentation))
            append(DocumentationMarkup.CONTENT_END)
        }
    }

    /**
     * The compiler's type for the expression at [original] (from Cuttlebone's
     * hover), e.g. `Result[ref Int, OutOfBounds]` for a call: what this use
     * gives, with type arguments filled in, which the signature can't say.
     */
    fun inferredType(): String? = hover()?.value?.let(InkoHover::type)

    /** Whether [inferredType] is from the last version that type checked, so is shown as possibly outdated. */
    private fun inferredTypeIsStale(): Boolean = hover()?.stale == true

    private fun hover(): CuttleboneQueries.Answer<org.eclipse.lsp4j.Hover>? {
        val original = original ?: return null
        val file = original.containingFile as? InkoFile ?: return null
        return CuttleboneQueries.getInstance(file.project).hover(file, original.textRange.startOffset)
    }

    /** The declaration, with where it's defined: `fn pub value -> Int` in `app.util.Helper`, and [type], this use's type. */
    private fun header(type: String? = null, stale: Boolean = false): String {
        val signature = StringUtil.escapeXmlEntities(definition.signature ?: definition.name)
        val location = StringUtil.escapeXmlEntities(listOfNotNull(definition.module, definition.owner).joinToString("."))
        val typeSection = type?.let {
            DocumentationMarkup.SECTION_HEADER_START + "Type here" + DocumentationMarkup.SECTION_SEPARATOR +
                "<code>${StringUtil.escapeXmlEntities(it)}</code>" + (if (stale) " (${CuttleboneQueries.OUTDATED})" else "") + DocumentationMarkup.SECTION_END
        }.orEmpty()
        return DocumentationMarkup.DEFINITION_START + "<code>$signature</code>" + DocumentationMarkup.DEFINITION_END +
            DocumentationMarkup.SECTIONS_START + DocumentationMarkup.SECTION_HEADER_START + "Defined in" +
            DocumentationMarkup.SECTION_SEPARATOR + "<code>$location</code>" + DocumentationMarkup.SECTION_END +
            typeSection + DocumentationMarkup.SECTIONS_END
    }
}

val InkoDefinition.icon: Icon
    get() = when (kind) {
        Kind.MODULE -> InkoIcons.FILE
        Kind.TYPE -> AllIcons.Nodes.Class
        Kind.TRAIT -> AllIcons.Nodes.Interface
        Kind.CONSTANT -> AllIcons.Nodes.Constant
        Kind.MODULE_METHOD -> AllIcons.Nodes.Function
        Kind.STATIC_METHOD, Kind.INSTANCE_METHOD -> AllIcons.Nodes.Method
        Kind.CONSTRUCTOR -> AllIcons.Nodes.Enum
        Kind.FIELD -> AllIcons.Nodes.Field
    }

/** Where idoc publishes the standard library's documentation (see idoc's module_name.inko and page/search.inko). */
object InkoStdDocs {
    fun url(definition: InkoDefinition, version: String?): String? {
        if (definition.module != "std" && !definition.module.startsWith("std.")) return null
        val module = "https://docs.inko-lang.org/std/${version?.let { "v$it" } ?: "latest"}/module/${definition.module.replace('.', '/')}/"
        return when (definition.kind) {
            Kind.MODULE -> module
            Kind.TYPE, Kind.TRAIT, Kind.CONSTANT, Kind.MODULE_METHOD -> "$module${definition.name}/"
            Kind.INSTANCE_METHOD, Kind.STATIC_METHOD -> definition.owner?.let { "$module$it/#method.${definition.name}" }
            Kind.FIELD -> definition.owner?.let { "$module$it/#field.${definition.name}" }
            Kind.CONSTRUCTOR -> definition.owner?.let { "$module$it/#constructor.${definition.name}" }
        }
    }
}

/**
 * Cuttlebone's hover: the type at a position, as a code block, the first of up to three sections, `\n\n---\n\n` apart (Cuttlebone's docs/lsp.md,
 * "textDocument/hover"): the type, the declaration of the name there (its signature in a code block, then
 * `Defined in …`; for a module, `Module …`), and its doc comment. At a declaration's own name there's no
 * expression, so no type, and the declaration comes first. In plain text, sections are a blank line apart.
 */
object InkoHover {
    fun type(hover: org.eclipse.lsp4j.Hover): String? {
        val contents = hover.contents ?: return null
        val plain = contents.isRight && contents.right.kind == org.eclipse.lsp4j.MarkupKind.PLAINTEXT
        val text = if (contents.isRight) contents.right.value else contents.left.joinToString("\n") { if (it.isLeft) it.left else it.right.value }
        val sections = text.trim().split(if (plain) "\n\n" else "\n\n---\n\n")
        val first = sections.first().trim()
        val declaration = first.startsWith("Module ") ||
            (if (plain) sections.getOrNull(1)?.startsWith("Defined in ") == true else "\n\nDefined in " in first)
        if (declaration) return null
        return first.removePrefix("```inko").removePrefix("```").removeSuffix("```").trim().takeIf { it.isNotEmpty() }
    }
}
