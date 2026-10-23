package com.setdef.parakeet

import com.intellij.application.options.CodeStyle

/** Two-space indentation, as inko fmt formats, when typing. */
class InkoIndentTest : InkoTestCase() {
    private fun typing(before: String, typed: String, after: String) {
        myFixture.configureByText("main.inko", before)
        myFixture.type(typed)
        myFixture.checkResult(after)
    }

    fun testDefaultsToTwoSpaces() {
        myFixture.configureByText("main.inko", "")
        val options = CodeStyle.getIndentOptions(myFixture.file)
        assertEquals(2, options.INDENT_SIZE)
        assertFalse(options.USE_TAB_CHARACTER)
    }

    fun testEnterBetweenBracesIndentsTheBody() =
        typing("fn a {<caret>}\n", "\n", "fn a {\n  <caret>\n}\n")

    fun testEnterAfterAnOpeningBraceIndents() =
        typing("type A {\n  fn a {<caret>\n  }\n}\n", "\n", "type A {\n  fn a {\n    <caret>\n  }\n}\n")

    fun testEnterKeepsTheCurrentLevel() =
        typing("fn a {\n  let x = 1<caret>\n}\n", "\n", "fn a {\n  let x = 1\n  <caret>\n}\n")

    fun testEnterAfterABlockReturnsToItsLevel() =
        typing("fn a {\n  if x {\n    1\n  }<caret>\n}\n", "\n", "fn a {\n  if x {\n    1\n  }\n  <caret>\n}\n")

    fun testBracketsInStringsAndCommentsDoNotCount() =
        typing("fn a {\n  let s = '{' # (<caret>\n}\n", "\n", "fn a {\n  let s = '{' # (\n  <caret>\n}\n")

    fun testTypingAClosingBraceDedents() =
        typing("fn a {\n  let x = 1\n  <caret>\n", "}", "fn a {\n  let x = 1\n}<caret>\n")

    fun testOpenParenthesesIndentArguments() =
        typing("foo(<caret>)\n", "\n", "foo(\n  <caret>\n)\n")
}
