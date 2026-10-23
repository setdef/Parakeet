package com.setdef.parakeet

import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInsight.template.impl.ConstantNode
import com.intellij.codeInsight.template.postfix.templates.PostfixTemplate
import com.intellij.codeInsight.template.postfix.templates.PostfixTemplateProvider
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/** Postfix completion for Inko: `cond.if`, `x.match`, `x.let`, `b.not`, `x.return`, `x.par`. */
class InkoPostfixTemplateProvider : PostfixTemplateProvider {
    private val templates: Set<PostfixTemplate> = setOf(
        // Lines after the first are indented relative to the expression's line by the template engine.
        InkoPostfixTemplate("if", "if expr {}", this) { "if $it {\n  \$END\$\n}" },
        InkoPostfixTemplate("match", "match expr {}", this) { "match $it {\n  case \$END\$ -> {}\n}" },
        InkoPostfixTemplate("let", "let name = expr", this, variable = "NAME" to "value") { "let \$NAME\$ = $it\$END\$" },
        InkoPostfixTemplate("not", "!expr", this) { "!$it\$END\$" },
        InkoPostfixTemplate("return", "return expr", this) { "return $it\$END\$" },
        InkoPostfixTemplate("par", "(expr)", this) { "($it)\$END\$" },
    )

    override fun getId(): String = "inko"

    override fun getPresentableName(): String = "Inko"

    override fun getTemplates(): Set<PostfixTemplate> = templates

    override fun isTerminalSymbol(currentChar: Char): Boolean = currentChar == '.'

    override fun preExpand(file: PsiFile, editor: Editor) {}

    override fun afterExpand(file: PsiFile, editor: Editor) {}

    override fun preCheck(copyFile: PsiFile, realEditor: Editor, currentOffset: Int): PsiFile = copyFile
}

/**
 * A postfix template that replaces the expression before `.key` with [build]'s
 * live-template text (given the expression), with an optional [variable] to
 * fill in.
 */
class InkoPostfixTemplate(
    key: String,
    example: String,
    provider: PostfixTemplateProvider,
    private val variable: Pair<String, String>? = null,
    private val build: (expression: String) -> String,
) : PostfixTemplate("inko.$key", key, example, provider) {
    // Shown in Settings | Editor | General | Postfix Completion (instead of bundled description files).
    override fun getDescription(): String = "Wraps an expression: <code>$example</code>"

    override fun isApplicable(context: PsiElement, copyDocument: Document, newOffset: Int): Boolean =
        InkoPostfixExpressions.before(copyDocument.charsSequence, newOffset) != null

    override fun expand(context: PsiElement, editor: Editor) {
        val document = editor.document
        val offset = editor.caretModel.offset
        val range = InkoPostfixExpressions.before(document.charsSequence, offset) ?: return
        val expression = document.getText(range)
        document.deleteString(range.startOffset, offset)
        editor.caretModel.moveToOffset(range.startOffset)

        val manager = TemplateManager.getInstance(context.project)
        // `$` would start a template variable, so the expression's own (in interpolated strings) are escaped.
        val template = manager.createTemplate("", "", build(expression.replace("$", "$$")))
        template.isToReformat = false
        variable?.let { (name, default) -> template.addVariable(name, ConstantNode(default), true) }
        manager.startTemplate(editor, template)
    }
}

object InkoPostfixExpressions {
    private val VALUES = setOf("self", "true", "false", "nil")

    /**
     * The expression ending right before [offset] (where `.key` was typed): a
     * chain of names, literals, calls, indexes, and `.`s, like `foo.bar(1)[0]`,
     * on one line apart from what its brackets hold. Null in comments and
     * strings, or when nothing precedes.
     */
    fun before(text: CharSequence, offset: Int): TextRange? {
        val stream = InkoTokenStream(text)
        val tokens = stream.tokens
        // A key typed right after a dot (`foo.` then `if`) leaves the dot before the offset.
        var i = tokens.indexOfLast { it.end <= offset }
        if (i < 0 || tokens[i].end != offset && text.subSequence(tokens[i].end, offset).isNotBlank()) return null
        if (insideStringOrComment(text, offset)) return null
        var start = -1
        while (i >= 0) {
            val token = tokens[i]
            when {
                token.closes -> {
                    val open = stream.match(i).takeIf { it >= 0 } ?: return null
                    start = tokens[open].start
                    i = open - 1
                    // `(…)` and `[…]` attach to what's right before them (calls and indexes, like `f(1)[0]`); otherwise they're the whole value.
                    if (i < 0 || tokens[i].end != tokens[open].start || !(isValue(tokens[i]) || tokens[i].closes)) break
                }
                isValue(token) -> {
                    start = token.start
                    i--
                    if (i < 0 || tokens[i].type != InkoTokens.DOT || stream.lineBreakBetween(i, i + 1)) break
                    // Step over the `.` to what it's called on.
                    i--
                }
                else -> break
            }
        }
        return if (start < 0) null else TextRange(start, offset)
    }

    private fun isValue(token: InkoToken): Boolean =
        token.type in InkoTokens.NAMES || token.type == InkoTokens.STRING || token.type == InkoTokens.NUMBER ||
            token.type == InkoTokens.KEYWORD && token.text in VALUES

    private fun insideStringOrComment(text: CharSequence, offset: Int): Boolean {
        val lexer = InkoLexer()
        lexer.start(text, 0, text.length, 0)
        while (lexer.tokenType != null) {
            if (lexer.tokenStart < offset && offset < lexer.tokenEnd) {
                return lexer.tokenType in InkoTokens.STRINGS || lexer.tokenType == InkoTokens.COMMENT
            }
            if (lexer.tokenStart >= offset) return false
            lexer.advance()
        }
        return false
    }
}
