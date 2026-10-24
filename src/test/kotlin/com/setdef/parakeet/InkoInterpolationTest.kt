package com.setdef.parakeet

import com.intellij.openapi.util.TextRange
import com.intellij.psi.util.PsiTreeUtil

/** Code inside `${…}` is ordinary Inko code (AUDIT.md finding 1.1). */
class InkoInterpolationTest : InkoTestCase() {
    private val greet = "fn greet(name: String) -> String {\n  let greeting = 'Hello'\n  '\${greeting}, \${name}!'\n}\n"

    private fun tokens(text: String): List<Pair<String, String>> {
        val lexer = InkoLexer()
        lexer.start(text, 0, text.length, 0)
        return buildList {
            while (lexer.tokenType != null) {
                if (lexer.tokenType != InkoTokens.WHITE_SPACE) add(lexer.tokenType.toString() to text.substring(lexer.tokenStart, lexer.tokenEnd))
                lexer.advance()
            }
        }
    }

    // As Inko's lexer does (ast/src/lexer.rs, string_expression and curly_close): `${` returns to code until its `}`.
    fun testTheLexerReadsInterpolatedCodeAsTokens() {
        assertEquals(
            listOf(
                "STRING_START" to "'a \${", "IDENTIFIER" to "x", "." to ".", "IDENTIFIER" to "map", "(" to "(",
                "KEYWORD" to "fn", "{" to "{", "STRING" to "'}'", "}" to "}", ")" to ")",
                "STRING_MIDDLE" to "} b \${", "CONSTANT" to "Y", "STRING_END" to "}!'",
                "STRING" to "\"plain \\\${not code}\"",
            ),
            tokens("'a \${x.map(fn { '}' })} b \${Y}!' \"plain \\\${not code}\""),
        )
    }

    fun testUnterminatedStringsEndTheFile() {
        assertEquals(listOf("STRING_START" to "'a \${", "IDENTIFIER" to "x"), tokens("'a \${x"))
        assertEquals(listOf("STRING" to "'abc"), tokens("'abc"))
    }

    private fun declaration(text: String, marker: String): InkoDeclaration {
        myFixture.configureByText("main.inko", text)
        return PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(text.indexOf(marker)), InkoDeclaration::class.java, false)!!
    }

    fun testUsagesIncludeInterpolations() {
        val uses = InkoUsages.inFile(declaration(greet, "greeting =")).filter { !it.second }.map { it.first.textRange.startOffset }
        assertEquals(listOf(greet.indexOf("greeting}")), uses)
        val names = InkoUsages.inFile(declaration(greet, "name:")).filter { !it.second }.map { it.first.textRange.startOffset }
        assertEquals(listOf(greet.indexOf("name}")), names)
    }

    fun testRenamingALocalRenamesItsInterpolations() {
        myFixture.configureByText("main.inko", greet)
        myFixture.editor.caretModel.moveToOffset(greet.indexOf("greeting ="))
        myFixture.renameElementAtCaret("salute")
        assertEquals(greet.replace("greeting", "salute"), myFixture.editor.document.text)
    }

    fun testInlineVariableReplacesInterpolatedUses() {
        val declaration = declaration(greet, "greeting =")
        val uses = InkoUsages.inFile(declaration).filter { !it.second && it.first != declaration }.map { it.first.textRange }
        val plan = InkoInlineVariable.plan(InkoTokenStream(greet), declaration.textRange.startOffset, uses).getOrThrow()
        InkoInlineVariable.apply(project, myFixture.editor, plan)
        assertEquals("fn greet(name: String) -> String {\n  '\${'Hello'}, \${name}!'\n}\n", myFixture.editor.document.text)
    }

    fun testExtractMethodPassesInterpolatedLocals() {
        val line = "'\${greeting}, \${name}!'"
        val start = greet.indexOf(line)
        val plan = InkoExtractMethod.plan(greet, TextRange(start, start + line.length)).getOrThrow()
        assertEquals("extracted(greeting, name)", plan.call)
    }

    fun testAnInterpolatedStringIsOneExpression() {
        val text = "fn a(x: Int) -> Int {\n  '\${x} items'.size\n}\n"
        val stream = InkoTokenStream(text)
        val candidates = InkoIntroduceVariable.candidatesAt(stream, text.indexOf("items"))
        assertEquals(listOf("'\${x} items'", "'\${x} items'.size"), candidates.map { it.substring(text) })
    }

    fun testExtendSelectionGrowsFromTheCodeToTheString() {
        myFixture.configureByText("main.inko", "fn a(name: String) -> String {\n  'hi \${na<caret>me}!'\n}\n")
        val steps = (1..3).map {
            myFixture.performEditorAction(com.intellij.openapi.actionSystem.IdeActions.ACTION_EDITOR_SELECT_WORD_AT_CARET)
            myFixture.editor.selectionModel.selectedText
        }
        assertEquals(listOf("name", "hi \${name}!", "'hi \${name}!'"), steps)
    }

    fun testTypingTheClosingQuoteOfAnInterpolatedStringStepsOverIt() {
        myFixture.configureByText("main.inko", "let a = <caret>\n")
        myFixture.type("'\${b}'")
        assertEquals("let a = '\${b}'\n", myFixture.editor.document.text)
    }
}
