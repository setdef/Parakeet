package com.setdef.parakeet

import com.intellij.openapi.actionSystem.IdeActions

/**
 * Complete Current Statement adds a body only where Inko's grammar has one:
 * not after build tags or a match guard's `if`, and not after `fn extern`,
 * whose body is optional (ast/src/parser.rs, `define_module_method`). AUDIT.md finding 1.12.
 */
class InkoSmartEnterKindsTest : InkoTestCase() {
    private fun complete(text: String): String {
        myFixture.configureByText("main.inko", text)
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_COMPLETE_STATEMENT)
        return myFixture.editor.document.text.let { it.substring(0, myFixture.caretOffset) + "<caret>" + it.substring(myFixture.caretOffset) }
    }

    fun testBuildTagsGetNoBody() =
        assertEquals("import std.stdio (Stdout) if unix\n<caret>\n", complete("import std.stdio (Stdout) if uni<caret>x\n"))

    fun testAGuardedArmGetsNoBlock() = assertEquals(
        "fn f(x: Option[Int]) {\n  match x {\n    case Some(n) if n > 1 -> foo(n)\n    <caret>\n  }\n}\n",
        complete("fn f(x: Option[Int]) {\n  match x {\n    case Some(n) if n > 1 -> foo(<caret>n)\n  }\n}\n"),
    )

    fun testAnExternFunctionGetsNoBody() =
        assertEquals("fn extern ceil(value: Float64) -> Float64\n<caret>\n", complete("fn extern ceil(value: Float64) -> Flo<caret>at64\n"))

    fun testAnIfInAnArmStillGetsABody() = assertEquals(
        "fn f(x: Int) {\n  match x {\n    case n -> if n > 1 {\n      <caret>\n    }\n  }\n}\n",
        complete("fn f(x: Int) {\n  match x {\n    case n -> if n ><caret> 1\n  }\n}\n"),
    )
}
