package com.setdef.parakeet

import com.intellij.psi.util.PsiTreeUtil

class InkoInlineVariableTest : InkoTestCase() {
    /** Inlines the variable at <caret> in [text], returning the result (or the refusal's message). */
    private fun inline(text: String): String {
        myFixture.configureByText("main.inko", text)
        val declaration = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(myFixture.caretOffset), InkoDeclaration::class.java, false)!!
        val uses = InkoUsages.inFile(declaration).filter { !it.second && it.first != declaration }.map { it.first.textRange }
        val plan = InkoInlineVariable.plan(InkoTokenStream(myFixture.editor.document.text), declaration.textRange.startOffset, uses)
            .getOrElse { return "refused: ${it.message}" }
        InkoInlineVariable.apply(project, myFixture.editor, plan)
        return myFixture.editor.document.text
    }

    fun testInlinesASimpleVariable() = assertEquals(
        "fn a -> Int {\n  items.size + 1\n}\n",
        inline("fn a -> Int {\n  let <caret>size = items.size\n  size + 1\n}\n"),
    )

    // Inko's binary operators share one precedence, left to right: `2 * total` with total = `a + b` needs parentheses.
    fun testParenthesizesWherePrecedenceWouldChange() = assertEquals(
        "fn a -> Int {\n  foo(a + b)\n  2 * (a + b)\n  (a + b).to_string\n}\n",
        inline("fn a -> Int {\n  let <caret>total = a + b\n  foo(total)\n  2 * total\n  total.to_string\n}\n"),
    )

    // `and`/`or` bind tighter than Inko's binary operators, but still looser than a `.call`.
    fun testParenthesizesAndOrBeforeACall() = assertEquals(
        "fn a {\n  (x and y).to_string\n}\n",
        inline("fn a {\n  let <caret>both = x and y\n  both.to_string\n}\n"),
    )

    // `as` binds like the binary operators, so `cast + 1` would cast `v + 1`.
    fun testParenthesizesCasts() = assertEquals(
        "fn a {\n  (v as Int) + 1\n}\n",
        inline("fn a {\n  let <caret>cast = v as Int\n  cast + 1\n}\n"),
    )

    fun testInlinesMultiLineChainsWhole() = assertEquals(
        "fn a {\n  use(foo\n    .bar\n    .baz)\n}\n",
        inline("fn a {\n  let <caret>value = foo\n    .bar\n    .baz\n  use(value)\n}\n"),
    )

    fun testInlinesTypedVariables() = assertEquals(
        "fn a {\n  use(Map.new)\n}\n",
        inline("fn a {\n  let <caret>map: Map[String, Array[Int]] = Map.new\n  use(map)\n}\n"),
    )

    fun testRefusesReassignedVariables() {
        assertEquals("refused: `n` is assigned a new value, so it can't be inlined.", inline("fn a {\n  let mut <caret>n = 1\n  n = 2\n  n\n}\n"))
        assertEquals("refused: `n` is assigned a new value, so it can't be inlined.", inline("fn a {\n  let mut <caret>n = 1\n  n := 2\n}\n"))
    }

    // `n += 2` assigns `n` like `n = n + 2` (Inko's `binary_assign_variable`): inlined, it would be `1 += 2`.
    fun testRefusesCompoundAssignedVariables() {
        for (operator in listOf("+=", "-=", "*=", "/=", "%=", "**=", "<<=", ">>=", ">>>=", "|=", "&=", "^=")) {
            assertEquals(operator, "refused: `n` is assigned a new value, so it can't be inlined.", inline("fn a -> Int {\n  let mut <caret>n = 1\n  n $operator 2\n  n\n}\n"))
        }
    }

    fun testComparisonsAreNotAssignments() = assertEquals(
        "fn a {\n  1 == 2\n}\n",
        inline("fn a {\n  let <caret>n = 1\n  n == 2\n}\n"),
    )

    fun testComparisonsLikeCompoundAssignmentsAreNotAssignments() = assertEquals(
        "fn a {\n  1 <= 2\n  1 >= 2\n  1 != 2\n}\n",
        inline("fn a {\n  let <caret>n = 1\n  n <= 2\n  n >= 2\n  n != 2\n}\n"),
    )

    fun testRefusesLetElseAndParameters() {
        assertEquals("refused: `let … else` can't be inlined.", inline("fn a {\n  let <caret>v = x else return\n  v\n}\n"))
        assertTrue(inline("fn a(<caret>v: Int) {\n  v\n}\n").startsWith("refused: Only variables defined with `let"))
    }

    fun testTheInlineActionInlinesLocals() {
        myFixture.configureByText("main.inko", "fn a -> Int {\n  let <caret>size = items.size\n  size + 1\n}\n")
        myFixture.performEditorAction("Inline")
        assertEquals("fn a -> Int {\n  items.size + 1\n}\n", myFixture.editor.document.text)
    }
}
