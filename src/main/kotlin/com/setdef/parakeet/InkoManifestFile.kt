package com.setdef.parakeet

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.icons.AllIcons
import com.intellij.lang.ASTNode
import com.intellij.lang.Commenter
import com.intellij.lang.Language
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lexer.LexerBase
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import javax.swing.Icon
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Colors

object InkoManifestLanguage : Language("InkoManifest") {
    private fun readResolve(): Any = InkoManifestLanguage

    override fun getDisplayName(): String = "Inko Package Manifest"
}

class InkoManifestFileType private constructor() : LanguageFileType(InkoManifestLanguage) {
    override fun getName(): String = "Inko Package Manifest"
    override fun getDescription(): String = "Inko package manifest (inko.pkg)"
    override fun getDefaultExtension(): String = "pkg"
    override fun getIcon(): Icon = AllIcons.Nodes.PpLib

    companion object {
        @JvmField
        val INSTANCE = InkoManifestFileType()
    }
}

class InkoManifestTokenType(debugName: String) : IElementType(debugName, InkoManifestLanguage)

object InkoManifestTokens {
    @JvmField val COMMENT = InkoManifestTokenType("COMMENT")
    @JvmField val KEYWORD = InkoManifestTokenType("KEYWORD")
    @JvmField val URL = InkoManifestTokenType("URL")
    @JvmField val VERSION = InkoManifestTokenType("VERSION")
    @JvmField val CHECKSUM = InkoManifestTokenType("CHECKSUM")
    @JvmField val COMMENTS = TokenSet.create(COMMENT)
}

/**
 * Lexes `require URL VERSION CHECKSUM` and `require inko VERSION` lines by
 * position: the state is which word of the line comes next.
 */
class InkoManifestLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var end = 0
    private var start = 0
    private var tokenEnd = 0
    private var type: IElementType? = null
    private var word = 0
    private var inkoLine = false

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.end = endOffset
        this.tokenEnd = startOffset
        this.word = initialState and 0xF
        this.inkoLine = initialState and 0x10 != 0
        advance()
    }

    override fun getState(): Int = word or (if (inkoLine) 0x10 else 0)
    override fun getTokenType(): IElementType? = type
    override fun getTokenStart(): Int = start
    override fun getTokenEnd(): Int = tokenEnd
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = end

    override fun advance() {
        start = tokenEnd
        if (start >= end) {
            type = null
            return
        }
        var i = start
        val c = buffer[i]
        when {
            c == '\n' -> {
                i++
                word = 0
                inkoLine = false
                type = TokenType.WHITE_SPACE
            }
            c.isWhitespace() -> {
                while (i < end && buffer[i].isWhitespace() && buffer[i] != '\n') i++
                type = TokenType.WHITE_SPACE
            }
            c == '#' && word == 0 -> {
                while (i < end && buffer[i] != '\n') i++
                type = InkoManifestTokens.COMMENT
            }
            else -> {
                while (i < end && !buffer[i].isWhitespace()) i++
                val text = buffer.subSequence(start, i).toString()
                type = when (word) {
                    0 -> if (text == "require") InkoManifestTokens.KEYWORD else TokenType.BAD_CHARACTER
                    1 -> if (text == "inko") InkoManifestTokens.KEYWORD.also { inkoLine = true } else InkoManifestTokens.URL
                    2 -> InkoManifestTokens.VERSION
                    3 -> if (inkoLine) TokenType.BAD_CHARACTER else InkoManifestTokens.CHECKSUM
                    else -> TokenType.BAD_CHARACTER
                }
                word = minOf(word + 1, 4)
            }
        }
        tokenEnd = i
    }
}

class InkoManifestHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer() = InkoManifestLexer()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = pack(
        when (tokenType) {
            InkoManifestTokens.COMMENT -> Colors.LINE_COMMENT
            InkoManifestTokens.KEYWORD -> Colors.KEYWORD
            InkoManifestTokens.URL -> Colors.STRING
            InkoManifestTokens.VERSION -> Colors.NUMBER
            InkoManifestTokens.CHECKSUM -> Colors.METADATA
            else -> null
        },
    )
}

class InkoManifestHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = InkoManifestHighlighter()
}

class InkoManifestPsiFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, InkoManifestLanguage) {
    override fun getFileType(): FileType = InkoManifestFileType.INSTANCE
}

/** One line of the manifest (what problems are reported on). */
class InkoManifestLine(node: ASTNode) : ASTWrapperPsiElement(node)

/** Parses the manifest into lines of tokens. */
class InkoManifestParserDefinition : ParserDefinition {
    private val file = IFileElementType(InkoManifestLanguage)

    override fun createLexer(project: Project?) = InkoManifestLexer()
    override fun createParser(project: Project?): PsiParser = PsiParser { root, builder: PsiBuilder ->
        val marker = builder.mark()
        var line: PsiBuilder.Marker? = null
        var previousEnd = 0
        // The builder skips whitespace (and comments), so line ends are found in the text between tokens.
        while (!builder.eof()) {
            if (line != null && builder.originalText.subSequence(previousEnd, builder.currentOffset).contains('\n')) {
                line.done(LINE)
                line = null
            }
            if (line == null) line = builder.mark()
            builder.advanceLexer()
            previousEnd = lastTokenEnd(builder)
        }
        line?.done(LINE)
        marker.done(root)
        builder.treeBuilt
    }

    // The end of the token just advanced past (the builder's current offset skips trailing whitespace).
    private fun lastTokenEnd(builder: PsiBuilder): Int {
        var step = -1
        while (true) {
            val type = builder.rawLookup(step) ?: return builder.currentOffset
            if (type != TokenType.WHITE_SPACE && type != InkoManifestTokens.COMMENT) return builder.rawTokenTypeStart(step + 1)
            step--
        }
    }

    override fun getFileNodeType(): IFileElementType = file
    override fun getCommentTokens(): TokenSet = InkoManifestTokens.COMMENTS
    override fun getStringLiteralElements(): TokenSet = TokenSet.EMPTY
    override fun createElement(node: ASTNode): PsiElement = InkoManifestLine(node)

    companion object {
        val LINE = IElementType("LINE", InkoManifestLanguage)
    }
    override fun createFile(viewProvider: FileViewProvider): PsiFile = InkoManifestPsiFile(viewProvider)
}

class InkoManifestCommenter : Commenter {
    override fun getLineCommentPrefix() = "# "
    override fun getBlockCommentPrefix(): String? = null
    override fun getBlockCommentSuffix(): String? = null
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}

/** Marks lines `inko` would reject, with the compiler's reasons. */
class InkoManifestAnnotator : Annotator {
    // Problems are found for the whole file (one line's validity can depend on another), then reported on their lines.
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is InkoManifestLine) return
        val range = element.textRange
        for (problem in InkoManifest.parse(element.containingFile.text).problems.filter { range.contains(it.range) }) {
            holder.newAnnotation(HighlightSeverity.ERROR, problem.message).range(problem.range).create()
        }
    }
}
