package com.setdef.parakeet

import com.intellij.codeInsight.template.impl.InvokeTemplateAction
import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.codeInsight.template.impl.TemplateSettings

/** Surround With (⌥⌘T / Ctrl+Alt+T) templates. */
class InkoSurroundTest : InkoTestCase() {
    private fun surround(template: String, text: String, vararg typed: String): String {
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
        myFixture.configureByText("main.inko", text)
        val impl = TemplateSettings.getInstance().getTemplate(template, "Inko")!!
        // As the Surround With action does: inside a command.
        com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(project, {
            InvokeTemplateAction(impl, myFixture.editor, project, HashSet()).perform()
        }, "Surround", null)
        // Typing each field's value, then Tab, as in the editor (where each runs in a command).
        fun command(run: () -> Unit) = com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(project, run, "Tab", null)
        for (value in typed) {
            myFixture.type(value)
            command { TemplateManagerImpl.getTemplateState(myFixture.editor)?.nextTab() }
        }
        command { TemplateManagerImpl.getTemplateState(myFixture.editor)?.gotoEnd(false) }
        return myFixture.editor.document.text
    }

    private val body = "fn a {\n  <selection>foo\n  bar</selection>\n}\n"

    fun testIf() = assertEquals("fn a {\n  if ready {\n    foo\n    bar\n  }\n}\n", surround("surround_if", body, "ready"))

    fun testIfNot() = assertEquals("fn a {\n  if !ready {\n    foo\n    bar\n  }\n}\n", surround("surround_if_not", body, "ready"))

    fun testIfElse() = assertEquals("fn a {\n  if ready {\n    foo\n    bar\n  } else {\n    \n  }\n}\n", surround("surround_if_else", body, "ready"))

    fun testFor() = assertEquals("fn a {\n  for x in xs {\n    foo\n    bar\n  }\n}\n", surround("surround_for", body, "x", "xs"))

    fun testMatch() = assertEquals(
        "fn a {\n  match v {\n    case _ -> {\n      foo\n      bar\n    }\n  }\n}\n",
        surround("surround_match", body, "v"),
    )

    fun testRecover() = assertEquals("fn a {\n  recover {\n    foo\n    bar\n  }\n}\n", surround("surround_recover", body))

    fun testParentheses() = assertEquals("fn a {\n  (a + b) * 2\n}\n", surround("surround_parentheses", "fn a {\n  <selection>a + b</selection> * 2\n}\n"))

    fun testEveryWrapperIsOffered() {
        myFixture.configureByText("main.inko", body)
        val surrounders = TemplateSettings.getInstance().templates.filter { it.groupName == "Inko" && it.isSelectionTemplate }.map { it.key }.toSet()
        assertEquals(
            setOf("surround_if", "surround_if_else", "surround_if_not", "surround_while", "surround_loop", "surround_for", "surround_match",
                "surround_closure", "surround_recover", "surround_block", "surround_parentheses"),
            surrounders,
        )
    }
}
