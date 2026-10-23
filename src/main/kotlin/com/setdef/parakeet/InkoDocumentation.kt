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
        return InkoDocumentationTarget(element, definition)
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

class InkoDocumentationTarget(private val element: PsiElement, private val definition: InkoDefinition) : DocumentationTarget {
    override fun createPointer(): Pointer<out DocumentationTarget> {
        val pointer = SmartPointerManager.createPointer(element)
        val definition = definition
        return Pointer { pointer.element?.let { InkoDocumentationTarget(it, definition) } }
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
        append(header())
        if (definition.documentation.isNotBlank()) {
            append(DocumentationMarkup.CONTENT_START)
            append(DocMarkdownToHtmlConverter.convert(element.project, definition.documentation))
            append(DocumentationMarkup.CONTENT_END)
        }
    }

    /** The declaration, with where it's defined: `fn pub value -> Int` in `app.util.Helper`. */
    private fun header(): String {
        val signature = StringUtil.escapeXmlEntities(definition.signature ?: definition.name)
        val location = StringUtil.escapeXmlEntities(listOfNotNull(definition.module, definition.owner).joinToString("."))
        return DocumentationMarkup.DEFINITION_START + "<code>$signature</code>" + DocumentationMarkup.DEFINITION_END +
            DocumentationMarkup.SECTIONS_START + DocumentationMarkup.SECTION_HEADER_START + "Defined in" +
            DocumentationMarkup.SECTION_SEPARATOR + "<code>$location</code>" + DocumentationMarkup.SECTION_END +
            DocumentationMarkup.SECTIONS_END
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
