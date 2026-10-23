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
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiReference
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil

class InkoFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, InkoLanguage) {
    override fun getFileType(): FileType = InkoFileType.INSTANCE
    override fun toString(): String = "Inko File"
}

/** A name (identifier, constant, or `@field`) that may refer to a definition. */
open class InkoName(node: ASTNode) : ASTWrapperPsiElement(node) {
    override fun getReference(): PsiReference? = InkoReference(this)

    val tokenType: IElementType? get() = node.firstChildNode?.elementType

    /** Replaces this name's text with [name] (keeping a field's `@`). */
    fun rename(name: String): InkoName {
        val text = if (tokenType == InkoTokens.FIELD && !name.startsWith("@")) "@$name" else name
        (firstChild as LeafPsiElement).replaceWithText(text)
        return this
    }
}

/**
 * A name where something is declared: a type, trait, method, field, constant,
 * enum constructor, local, or parameter. What Find Usages and Rename act on.
 */
class InkoDeclaration(node: ASTNode) : InkoName(node), PsiNameIdentifierOwner {
    override fun getName(): String = text.removePrefix("@")

    override fun setName(name: String): PsiElement = rename(name)

    override fun getNameIdentifier(): PsiElement? = firstChild

    // A declaration is the target of references, not a reference itself.
    override fun getReference(): PsiReference? = null

    /** Locals and parameters are only used in their own file. */
    val isLocal: Boolean get() = InkoLocals.declaredAt(containingFile.text, textRange.startOffset) != null

    override fun getUseScope(): SearchScope = if (isLocal) LocalSearchScope(containingFile) else super.getUseScope()
}

/** Where names are declared, found from a file's text alone (so the parser can use it). */
object InkoDeclarations {
    fun offsets(text: CharSequence): Set<Int> {
        val outline = InkoOutline.of(text)
        return buildSet {
            // An impl's "name" is the type it's for, which is declared elsewhere.
            outline.all.filter { it.kind != InkoOutline.Kind.IMPL }.forEach { add(it.nameOffset) }
            outline.roots.filter { it.kind == InkoOutline.Kind.TYPE }
                .forEach { type -> InkoSourceDefinitions.constructors(text, type).forEach { add(it.second) } }
            InkoLocals.declarations(text).forEach { add(it.offset) }
        }
    }

    /** What kind of thing [declaration] declares, for Find Usages ("method", "local variable", …). */
    fun kindOf(declaration: InkoDeclaration): String {
        if (declaration.isLocal) return "local variable"
        val text = declaration.containingFile.text
        val offset = declaration.textRange.startOffset
        val outline = InkoOutline.of(text)
        val path = outline.enclosing(offset)
        val node = path.lastOrNull { it.nameOffset == offset } ?: return "enum constructor"
        return when (node.kind) {
            InkoOutline.Kind.TYPE -> "type"
            InkoOutline.Kind.TRAIT -> "trait"
            InkoOutline.Kind.FIELD -> "field"
            InkoOutline.Kind.CONSTANT -> "constant"
            InkoOutline.Kind.METHOD -> if (path.size > 1) "method" else "function"
            InkoOutline.Kind.IMPL -> "impl"
        }
    }

    /** Whether [declaration] is a method that's called on a value, which can't always be told apart from same-named ones. */
    fun isInstanceMethod(declaration: InkoDeclaration): Boolean {
        val text = declaration.containingFile.text
        val offset = declaration.textRange.startOffset
        val path = InkoOutline.of(text).enclosing(offset)
        val node = path.lastOrNull { it.nameOffset == offset } ?: return false
        return node.kind == InkoOutline.Kind.METHOD && path.size > 1 &&
            !Regex("\\bstatic\\b").containsMatchIn(text.subSequence(node.range.startOffset, node.nameOffset))
    }
}

// Navigating the flat token stream, skipping whitespace.

fun PsiElement.previousToken(): PsiElement? = PsiTreeUtil.prevVisibleLeaf(this)

fun PsiElement.nextToken(): PsiElement? = PsiTreeUtil.nextVisibleLeaf(this)

fun PsiElement?.isToken(type: IElementType): Boolean = this?.node?.elementType == type

fun PsiElement?.isKeyword(vararg words: String): Boolean = isToken(InkoTokens.KEYWORD) && this!!.text in words

object InkoElementTypes {
    @JvmField val FILE = IFileElementType(InkoLanguage)
    @JvmField val NAME = IElementType("NAME", InkoLanguage)
    @JvmField val DECLARATION = IElementType("DECLARATION", InkoLanguage)
}

/**
 * A flat parser: the file is a list of tokens, with each name wrapped in an
 * [InkoName], or an [InkoDeclaration] where it declares something.
 */
class InkoParser : PsiParser {
    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val declarations = InkoDeclarations.offsets(builder.originalText)
        val file = builder.mark()
        while (!builder.eof()) {
            if (builder.tokenType in InkoTokens.NAMES) {
                val type = if (builder.currentOffset in declarations) InkoElementTypes.DECLARATION else InkoElementTypes.NAME
                val name = builder.mark()
                builder.advanceLexer()
                name.done(type)
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
    override fun createElement(node: ASTNode): PsiElement =
        if (node.elementType == InkoElementTypes.DECLARATION) InkoDeclaration(node) else InkoName(node)
    override fun createFile(viewProvider: FileViewProvider): PsiFile = InkoFile(viewProvider)
}
