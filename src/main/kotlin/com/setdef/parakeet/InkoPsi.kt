package com.setdef.parakeet

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReference
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

class InkoFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, InkoLanguage) {
    override fun getFileType(): FileType = InkoFileType.INSTANCE
    override fun toString(): String = "Inko File"
}

/** A name (identifier, constant, or `@field`) that may refer to a definition. */
class InkoName(node: ASTNode) : ASTWrapperPsiElement(node) {
    override fun getReference(): PsiReference? = InkoReference(this)
}

object InkoElementTypes {
    @JvmField val FILE = IFileElementType(InkoLanguage)
    @JvmField val NAME = IElementType("NAME", InkoLanguage)
}

/** A flat parser: the file is a list of tokens, with each name wrapped in an [InkoName]. */
class InkoParser : PsiParser {
    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val file = builder.mark()
        while (!builder.eof()) {
            if (builder.tokenType in InkoTokens.NAMES) {
                val name = builder.mark()
                builder.advanceLexer()
                name.done(InkoElementTypes.NAME)
            } else {
                builder.advanceLexer()
            }
        }
        file.done(root)
        return builder.treeBuilt
    }
}

class InkoParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?) = InkoLexer()
    override fun createParser(project: Project?): PsiParser = InkoParser()
    override fun getFileNodeType(): IFileElementType = InkoElementTypes.FILE
    override fun getCommentTokens(): TokenSet = InkoTokens.COMMENTS
    override fun getStringLiteralElements(): TokenSet = InkoTokens.STRINGS
    override fun createElement(node: ASTNode): PsiElement = InkoName(node)
    override fun createFile(viewProvider: FileViewProvider): PsiFile = InkoFile(viewProvider)
}
