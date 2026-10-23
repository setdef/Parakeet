package com.setdef.parakeet

import com.intellij.openapi.util.TextRange
import com.intellij.psi.util.PsiTreeUtil
import java.nio.file.Path

/**
 * Keywords used as names, as Inko's parser allows (ast/src/parser.rs:
 * `method_name`, `call_with_receiver`, and named arguments). AUDIT.md finding 1.4.
 */
class InkoKeywordNamesTest : InkoTestCase() {
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

    private fun typeOf(text: String, word: String, nth: Int = 0) = tokens(text).filter { it.second == word }[nth].first

    fun testKeywordsAfterADotAreNames() {
        val text = "Countdown(n: 3).next.or(0).else(fn { 1 })\n  .true"
        assertEquals("IDENTIFIER", typeOf(text, "next"))
        assertEquals("IDENTIFIER", typeOf(text, "or"))
        assertEquals("IDENTIFIER", typeOf(text, "else"))
        assertEquals("IDENTIFIER", typeOf(text, "true"))
        assertEquals("KEYWORD", typeOf(text, "fn"))
        assertEquals("KEYWORD", typeOf("a or b", "or"))
    }

    fun testAMethodsNameMayBeAKeyword() {
        assertEquals("IDENTIFIER", typeOf("fn pub mut next -> Option[Int] {}", "next"))
        assertEquals("IDENTIFIER", typeOf("fn pub copy[T: ToString](to: ref T) {}", "copy"))
        assertEquals("IDENTIFIER", typeOf("fn pub mut true(value: Bool) {}", "true"))
        assertEquals("IDENTIFIER", typeOf("fn pub noinline foo {}", "foo"))
        assertEquals("KEYWORD", typeOf("fn pub noinline foo {}", "noinline"))
        assertEquals("IDENTIFIER", typeOf("fn async mut else {}", "else"))
        assertEquals("KEYWORD", typeOf("fn async mut else {}", "mut"))
        // A moving closure's `move` is a keyword.
        assertEquals("KEYWORD", typeOf("fn move (x) { x }", "move"))
        assertEquals("KEYWORD", typeOf("fn move { x }", "move"))
    }

    fun testKeywordArgumentLabelsAreNames() {
        val text = "f(type: 1, if: 2)"
        assertEquals("IDENTIFIER", typeOf(text, "type"))
        assertEquals("IDENTIFIER", typeOf(text, "if"))
        assertEquals(
            listOf(InkoArgument("type", "1"), InkoArgument("if", "2")),
            InkoSignatures.callAt(text, 0)!!.arguments,
        )
    }

    private val countdown = """
        |import std.iter (Iter)
        |
        |type Countdown {
        |  let mut @n: Int
        |
        |  fn pub copy -> Countdown {
        |    Countdown(n: @n)
        |  }
        |
        |  fn pub noinline peek -> Option[Int] {
        |    match next {
        |      case Some(v) -> Option.Some(v)
        |      case None -> Option.None
        |    }
        |  }
        |}
        |
        |impl Iter[Int] for Countdown {
        |  fn pub mut next -> Option[Int] {
        |    if @n == 0 { return Option.None }
        |    @n -= 1
        |    Option.Some(@n)
        |  }
        |}
        |
        |fn first -> Int {
        |  Countdown(n: 3).next.or(0)
        |}
        |""".trimMargin()

    fun testTheOutlineHasKeywordNamedMethods() {
        val outline = InkoOutline.of(countdown)
        assertEquals(listOf("@n", "copy", "peek"), outline.roots.single { it.label == "Countdown" }.children.map { it.label })
        assertEquals(listOf("next"), outline.roots.single { it.kind == InkoOutline.Kind.IMPL }.children.map { it.label })
    }

    fun testMatchArmsInAKeywordNamedMethodAreNotConstructors() {
        val definitions = InkoSourceDefinitions.of(Path.of("/p/src/main.inko"), "main", countdown)
        assertEquals(emptyList<String>(), definitions.filter { it.kind == InkoDefinitionKind.CONSTRUCTOR }.map { it.name })
        assertEquals(listOf("copy", "peek", "next"), definitions.filter { it.kind == InkoDefinitionKind.INSTANCE_METHOD }.map { it.name })
    }

    fun testKeywordNamedMethodsAndCallsArePsiNames() {
        myFixture.configureByText("main.inko", countdown)
        val next = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(countdown.indexOf("next ->")), InkoName::class.java, false)
        assertTrue(next is InkoDeclaration)
        val call = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(countdown.indexOf("next.or")), InkoName::class.java, false)
        assertNotNull(call)
        assertFalse(call is InkoDeclaration)
    }

    fun testExtractMethodAcceptsCallsOfNext() {
        val start = countdown.indexOf("Countdown(n: 3).next.or(0)")
        val plan = InkoExtractMethod.plan(countdown, TextRange(start, start + "Countdown(n: 3).next.or(0)".length))
        assertNull(plan.exceptionOrNull()?.message, plan.exceptionOrNull())
    }
}
