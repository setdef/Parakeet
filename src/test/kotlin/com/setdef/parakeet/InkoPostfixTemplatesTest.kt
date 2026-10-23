package com.setdef.parakeet

import com.intellij.codeInsight.template.impl.TemplateManagerImpl

/** Postfix completion: type `.key` after an expression and press Tab. */
class InkoPostfixTemplatesTest : InkoTestCase() {
    override fun setUp() {
        super.setUp()
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
    }

    /** Types [typed] at <caret> in [text], presses Tab, finishes any template, and marks the caret. */
    private fun expand(text: String, typed: String): String {
        myFixture.configureByText("main.inko", text)
        myFixture.type(typed)
        myFixture.type('\t')
        TemplateManagerImpl.getTemplateState(myFixture.editor)?.gotoEnd(false)
        val document = myFixture.editor.document.text
        return document.substring(0, myFixture.caretOffset) + "<caret>" + document.substring(myFixture.caretOffset)
    }

    fun testIf() = assertEquals(
        "fn main {\n  if ready? {\n    <caret>\n  }\n}\n",
        expand("fn main {\n  ready?<caret>\n}\n", ".if"),
    )

    fun testMatchAWholeCallChain() = assertEquals(
        "fn main {\n  match numbers.get(0).ok {\n    case <caret> -> {}\n  }\n}\n",
        expand("fn main {\n  numbers.get(0).ok<caret>\n}\n", ".match"),
    )

    fun testLet() = assertEquals(
        "fn main {\n  let value = Thing.new(1)<caret>\n}\n",
        expand("fn main {\n  Thing.new(1)<caret>\n}\n", ".let"),
    )

    fun testNotOnlyTakesTheOperand() = assertEquals(
        "fn main {\n  a and !@done<caret>\n}\n",
        expand("fn main {\n  a and @done<caret>\n}\n", ".not"),
    )

    fun testReturnAndPar() {
        assertEquals("fn main {\n  return [1, 2][0]<caret>\n}\n", expand("fn main {\n  [1, 2][0]<caret>\n}\n", ".return"))
        assertEquals("fn main {\n  ('a'.size)<caret>\n}\n", expand("fn main {\n  'a'.size<caret>\n}\n", ".par"))
    }

    fun testNotInCommentsOrWithNothingBefore() {
        assertTrue(expand("# ready<caret>\n", ".if").startsWith("# ready.if"))
        assertFalse(InkoPostfixExpressions.before("fn main {\n  \n}\n", 12) != null)
    }
}
