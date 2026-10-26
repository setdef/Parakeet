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

    // inko-wobsite's `let bytes = ByteArray.new`, read into and then used, and kvi's `let out = try read(client)`:
    // at each use, the value would be a new one.
    fun testRefusesAComputedValueUsedMoreThanOnce() {
        assertEquals(
            "refused: `bytes` is used 2 times, and each use would compute `ByteArray.new` anew, not share one value.",
            inline("fn a -> String {\n  let <caret>bytes = ByteArray.new\n  read(bytes)\n  bytes.into_string\n}\n"),
        )
        assertEquals(
            "refused: `items` is used 2 times, and each use would compute `[]` anew, not share one value.",
            inline("fn a -> Int {\n  let <caret>items = []\n  items.push(1)\n  items.size\n}\n"),
        )
        // inko-builder's `let doc = new`: a name that isn't a variable calls a method, parentheses or not.
        assertEquals(
            "refused: `doc` is used 2 times, and each use would compute `new` anew, not share one value.",
            inline("type Document {\n  fn static new -> Document {\n    Document()\n  }\n\n  fn static html -> Document {\n    let <caret>doc = new\n    doc.element('html')\n    doc\n  }\n}\n"),
        )
    }

    // inko-syntax's `let token = helpers.text_or_keyword(@buffer, …)`, then `@buffer.offset += 1` before `token.value`:
    // inlined, the call would run after the code that changes what it reads.
    fun testRefusesMovingACallPastOtherCode() {
        assertEquals(
            "refused: `token`'s value would be computed after the code between it and where it's used, which may change it.",
            inline("fn a -> Int {\n  let <caret>token = word(@buffer)\n  if x {\n    @buffer.offset += 1\n    token.size\n  } else {\n    0\n  }\n}\n"),
        )
        // Right where it's used, or a value with no call, it's inlined as before.
        assertEquals("fn a -> Int {\n  show(word(@buffer))\n}\n", inline("fn a -> Int {\n  let <caret>token = word(@buffer)\n  show(token)\n}\n"))
        assertEquals("fn a -> Int {\n  step\n  show(1 + 2)\n}\n", inline("fn a -> Int {\n  let <caret>n = 1 + 2\n  step\n  show(n)\n}\n"))
    }

    // idoc's `let instance_methods = try …`, used in a closure: there, `try` would return from the closure instead.
    fun testRefusesMovingTryIntoAClosure() = assertEquals(
        "refused: `methods`'s value uses `try`, which would return from the closure it would be moved into.",
        inline("fn a -> Result[Int, String] {\n  let <caret>methods = try parse\n  each(fn (x) { show(methods) })\n  Result.Ok(1)\n}\n"),
    )

    // Outside a closure, or with no `try`, it's inlined as before.
    fun testInlinesTryOutsideAClosureAndPlainValuesIntoOne() {
        assertEquals("fn a -> Result[Int, String] {\n  show(try parse)\n}\n", inline("fn a -> Result[Int, String] {\n  let <caret>methods = try parse\n  show(methods)\n}\n"))
        assertEquals("fn a {\n  each(fn (x) { show(parse) })\n}\n", inline("fn a {\n  let <caret>methods = parse\n  each(fn (x) { show(methods) })\n}\n"))
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
