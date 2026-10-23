package com.setdef.parakeet

import junit.framework.TestCase

/**
 * Which `if` starts an `if` expression. In Inko's grammar `if` is also a match
 * guard (`optional_match_guard`), an impl's bounds (`optional_type_bounds`),
 * and an import's build tags (`build_tags`), in ast/src/parser.rs. AUDIT.md
 * finding 1.11 (and 1.12, which shares this cause).
 */
class InkoIfKindsTest : TestCase() {
    private fun options(text: String): List<String> {
        val offset = text.indexOf("<caret>")
        return InkoUnwraps.around(text.replace("<caret>", ""), offset).map { it.description }
    }

    fun testAMatchGuardIsNotAnIf() {
        val text = "fn f(x: Option[Int]) {\n  match x {\n    case Some(n) if n > 1 -> {\n      print(<caret>n)\n    }\n    case _ -> {}\n  }\n}\n"
        assertEquals(emptyList<String>(), options(text))
    }

    fun testAnImplsBoundsAreNotAnIf() {
        val text = "impl Equal for Box if T: Equal {\n  fn pub ==(other: ref Self) -> Bool {\n    <caret>true\n  }\n}\n"
        assertEquals(emptyList<String>(), options(text))
    }

    fun testAnIfInAGuardedArmIsStillAnIf() {
        val text = "fn f(x: Option[Int]) {\n  match x {\n    case Some(n) if n > 1 -> {\n      if n > 2 {\n        print(<caret>n)\n      }\n    }\n    case _ -> {}\n  }\n}\n"
        assertEquals(listOf("Unwrap 'if...'"), options(text))
    }

    fun testWhichIfsStartExpressions() {
        val text = "import std.stdio (Stdout) if unix\n\nimpl Equal for Box if T: Equal {}\n\nfn f(x: Int) -> Int {\n  match x {\n    case n if n > 1 -> if n > 2 { 1 } else { 2 }\n    case _ -> 0\n  }\n}\n"
        val stream = InkoTokenStream(text)
        val ifs = stream.tokens.indices.filter { stream.tokens[it].isKeyword("if") }
        assertEquals(listOf(false, false, false, true), ifs.map { stream.startsIfExpression(it) })
    }
}
