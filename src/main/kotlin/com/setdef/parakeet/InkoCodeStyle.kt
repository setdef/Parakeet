package com.setdef.parakeet

import com.intellij.application.options.CodeStyle
import com.intellij.application.options.CodeStyleAbstractConfigurable
import com.intellij.application.options.CodeStyleAbstractPanel
import com.intellij.application.options.SmartIndentOptionsEditor
import com.intellij.application.options.TabbedLanguageCodeStylePanel
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.lang.Language
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleConfigurable
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider
import com.intellij.psi.codeStyle.lineIndent.LineIndentProvider

/**
 * Settings | Editor | Code Style | Inko. Defaults to two spaces, as `inko fmt`
 * formats (it isn't configurable, so these only affect typing, not Reformat Code).
 */
class InkoCodeStyleSettingsProvider : LanguageCodeStyleSettingsProvider() {
    override fun getLanguage(): Language = InkoLanguage

    override fun customizeDefaults(commonSettings: CommonCodeStyleSettings, indentOptions: CommonCodeStyleSettings.IndentOptions) {
        indentOptions.INDENT_SIZE = 2
        indentOptions.CONTINUATION_INDENT_SIZE = 2
        indentOptions.TAB_SIZE = 2
        indentOptions.USE_TAB_CHARACTER = false
    }

    override fun getIndentOptionsEditor() = SmartIndentOptionsEditor()

    override fun getCodeSample(settingsType: SettingsType): String = """
        |import std.stdio (Stdout)
        |
        |type async Main {
        |  fn async main {
        |    let out = Stdout.new
        |
        |    for n in [1, 2, 3] {
        |      let _ = out.print(n.to_string)
        |    }
        |  }
        |}
        |""".trimMargin()

    override fun createConfigurable(baseSettings: CodeStyleSettings, modelSettings: CodeStyleSettings): CodeStyleConfigurable =
        object : CodeStyleAbstractConfigurable(baseSettings, modelSettings, "Inko") {
            override fun createPanel(settings: CodeStyleSettings): CodeStyleAbstractPanel =
                object : TabbedLanguageCodeStylePanel(InkoLanguage, currentSettings, settings) {
                    override fun initTabs(settings: CodeStyleSettings) {
                        addIndentOptionsTab(settings)
                    }
                }
        }
}

/**
 * Indents new lines (Enter, including between braces) and closing brackets.
 * Inko has no layout rules beyond brackets: a line is indented one level more
 * than the previous non-blank line if that line leaves a bracket open, and a
 * line starting with a closing bracket lines up with the line that opened it.
 */
class InkoLineIndentProvider : LineIndentProvider {
    override fun isSuitableFor(language: Language?): Boolean = language == InkoLanguage

    override fun getLineIndent(project: Project, editor: Editor, language: Language?, offset: Int): String? {
        val document = editor.document
        val text = document.charsSequence
        val line = document.getLineNumber(offset)
        val options = CodeStyle.getSettings(project).getIndentOptions(InkoFileType.INSTANCE)
        val unit = if (options.USE_TAB_CHARACTER) "\t" else " ".repeat(options.INDENT_SIZE)

        fun lineText(index: Int) = text.subSequence(document.getLineStartOffset(index), document.getLineEndOffset(index)).toString()

        var previous = line - 1
        while (previous >= 0 && lineText(previous).isBlank()) previous--
        if (previous < 0) return ""
        val previousText = lineText(previous)
        val base = previousText.takeWhile { it == ' ' || it == '\t' }
        val opensBlock = openBrackets(previousText) > 0
        val closesBlock = text.subSequence(offset, document.getLineEndOffset(line)).trimStart().firstOrNull() in CLOSERS

        return when {
            opensBlock && !closesBlock -> base + unit
            opensBlock && closesBlock -> base
            closesBlock -> base.removeSuffix(unit)
            else -> base
        }
    }

    /** Brackets the line opens minus those it closes, ignoring strings and comments. */
    private fun openBrackets(line: String): Int {
        val lexer = InkoLexer()
        lexer.start(line, 0, line.length, 0)
        var open = 0
        while (lexer.tokenType != null) {
            when (lexer.tokenType) {
                InkoTokens.LBRACE, InkoTokens.LPAREN, InkoTokens.LBRACKET -> open++
                InkoTokens.RBRACE, InkoTokens.RPAREN, InkoTokens.RBRACKET -> open--
            }
            lexer.advance()
        }
        return open
    }

    private companion object {
        val CLOSERS = setOf('}', ')', ']')
    }
}

/**
 * Lines up a closing bracket typed at the start of a line with the line that
 * opened it. (The IDE does this itself only for its own brace tokens, and the
 * editor's tokens here come from the TextMate grammar.)
 */
class InkoClosingBracketTypedHandler : TypedHandlerDelegate() {
    override fun charTyped(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (file !is InkoFile || c !in "})]") return Result.CONTINUE
        val document = editor.document
        val caret = editor.caretModel.offset
        val lineStart = document.getLineStartOffset(document.getLineNumber(caret))
        val beforeCaret = document.charsSequence.subSequence(lineStart, caret)
        if (beforeCaret.trimStart() != c.toString()) return Result.CONTINUE
        val indent = InkoLineIndentProvider().getLineIndent(project, editor, InkoLanguage, caret - 1) ?: return Result.CONTINUE
        document.replaceString(lineStart, caret - 1, indent)
        return Result.STOP
    }
}
