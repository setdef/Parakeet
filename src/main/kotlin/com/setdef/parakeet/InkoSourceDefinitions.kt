package com.setdef.parakeet

import java.nio.file.Path
import com.setdef.parakeet.InkoDefinitionKind as Kind

/**
 * Definitions read straight from a file's tokens (via [InkoOutline]), for when
 * `inko doc` can't describe it: the file has unsaved edits, or the project
 * doesn't compile. Less complete than `inko doc` (no inherited trait methods),
 * but always current.
 */
object InkoSourceDefinitions {
    fun of(file: Path, module: String, text: CharSequence): List<InkoDefinition> {
        val outline = InkoOutline.of(text)
        val lines = LineIndex(text)
        val result = mutableListOf(InkoDefinition(Kind.MODULE, module, module, null, file, 1, 1))

        fun add(kind: Kind, name: String, owner: String?, node: InkoOutline.Node) {
            val (line, column) = lines.position(node.nameOffset)
            result += InkoDefinition(
                kind, name, module, owner, file, line, column,
                signature = signature(text, node),
                documentation = lines.docCommentAbove(node.range.startOffset),
            )
        }

        for (root in outline.roots) {
            when (root.kind) {
                InkoOutline.Kind.TYPE, InkoOutline.Kind.TRAIT, InkoOutline.Kind.IMPL -> {
                    val owner = if (root.kind == InkoOutline.Kind.IMPL) implOwner(root.label) else root.label
                    if (root.kind != InkoOutline.Kind.IMPL) add(if (root.kind == InkoOutline.Kind.TYPE) Kind.TYPE else Kind.TRAIT, root.label, null, root)
                    for (member in root.children) {
                        when (member.kind) {
                            InkoOutline.Kind.METHOD -> add(if (isStatic(text, member)) Kind.STATIC_METHOD else Kind.INSTANCE_METHOD, member.label, owner, member)
                            InkoOutline.Kind.FIELD -> add(Kind.FIELD, member.label.removePrefix("@"), owner, member)
                            else -> {}
                        }
                    }
                    if (root.kind == InkoOutline.Kind.TYPE) {
                        constructors(text, root).forEach { (name, offset) ->
                            val (line, column) = lines.position(offset)
                            result += InkoDefinition(Kind.CONSTRUCTOR, name, module, owner, file, line, column, signature = "case $name")
                        }
                    }
                }
                InkoOutline.Kind.METHOD -> add(Kind.MODULE_METHOD, root.label, null, root)
                InkoOutline.Kind.CONSTANT -> add(Kind.CONSTANT, root.label, null, root)
                InkoOutline.Kind.FIELD -> {}
            }
        }
        return result
    }

    /** `impl Trait for Type` and `impl Type` both add methods to `Type`. */
    private fun implOwner(label: String): String {
        val words = label.removePrefix("impl ").replace(Regex("\\[.*?]"), "").split(' ').filter { it.isNotBlank() }
        val forIndex = words.indexOf("for")
        return if (forIndex >= 0) words.getOrElse(forIndex + 1) { words.first() } else words.first()
    }

    private fun isStatic(text: CharSequence, node: InkoOutline.Node): Boolean =
        Regex("\\bstatic\\b").containsMatchIn(text.subSequence(node.range.startOffset, node.nameOffset))

    /** The declaration up to its body (or end of line), e.g. `fn pub value -> Int`. */
    private fun signature(text: CharSequence, node: InkoOutline.Node): String {
        val start = node.range.startOffset
        var end = start
        while (end < text.length && text[end] != '{' && text[end] != '\n') end++
        return text.subSequence(start, end).toString().replace(Regex("\\s+"), " ").trim()
    }

    /** Enum constructors: `case Name` directly in a type's body, not in its methods' `match`es. */
    private fun constructors(text: CharSequence, type: InkoOutline.Node): List<Pair<String, Int>> {
        val methods = type.children.filter { it.kind == InkoOutline.Kind.METHOD }.map { it.range }
        return Regex("\\bcase\\s+([A-Z]\\w*)").findAll(text.subSequence(0, type.range.endOffset))
            .filter { it.range.first >= type.range.startOffset && methods.none { r -> r.containsOffset(it.range.first) } }
            .map { it.groupValues[1] to it.groups[1]!!.range.first }
            .toList()
    }

    private class LineIndex(private val text: CharSequence) {
        private val starts = buildList {
            add(0)
            text.forEachIndexed { index, c -> if (c == '\n') add(index + 1) }
        }

        /** 1-based line and column of [offset]. */
        fun position(offset: Int): Pair<Int, Int> {
            val line = starts.binarySearch(offset).let { if (it >= 0) it else -it - 2 }
            return (line + 1) to (offset - starts[line] + 1)
        }

        /** The `#` comment lines directly above the line containing [offset], without their `#`. */
        fun docCommentAbove(offset: Int): String {
            var line = position(offset).first - 2
            val comment = ArrayDeque<String>()
            while (line >= 0) {
                val content = text.subSequence(starts[line], starts.getOrNull(line + 1)?.minus(1) ?: text.length).toString().trim()
                if (!content.startsWith("#")) break
                comment.addFirst(content.removePrefix("#").removePrefix(" "))
                line--
            }
            return comment.joinToString("\n")
        }
    }
}
