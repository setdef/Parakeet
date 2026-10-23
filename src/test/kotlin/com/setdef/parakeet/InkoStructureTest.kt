package com.setdef.parakeet

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder

class InkoStructureTest : InkoTestCase() {
    private val text = """
        |type pub Helper {
        |  let @count: Int
        |
        |  fn pub value -> Int {
        |    @count<caret>
        |  }
        |}
        |
        |impl ToString for Helper {
        |  fn pub to_string -> String {
        |    ''
        |  }
        |}
        |""".trimMargin()

    private fun StructureViewTreeElement.describe(): String {
        val label = presentation.presentableText
        val children = children.map { (it as StructureViewTreeElement).describe() }
        return if (children.isEmpty()) "$label" else "$label ${children.joinToString(", ", "[", "]")}"
    }

    fun testStructureViewShowsTheOutline() {
        myFixture.configureByText("main.inko", text)
        val builder = InkoStructureViewFactory().getStructureViewBuilder(myFixture.file) as TreeBasedStructureViewBuilder
        val model = builder.createStructureViewModel(myFixture.editor)
        try {
            assertEquals("main.inko [Helper [@count, value], impl ToString for Helper [to_string]]", model.root.describe())
            // The caret is in `value`, so that's the selected element.
            assertEquals("value", (model.currentEditorElement as InkoName).text)
        } finally {
            model.dispose()
        }
    }

    fun testBreadcrumbsFollowTheCaret() {
        myFixture.configureByText("main.inko", text)
        assertEquals(listOf("Helper", "value"), myFixture.breadcrumbsAtCaret.map { it.text })
    }

    fun testBreadcrumbsForImpls() {
        myFixture.configureByText("main.inko", text.replace("<caret>", "").replace("''", "'<caret>'"))
        assertEquals(listOf("impl ToString for Helper", "to_string"), myFixture.breadcrumbsAtCaret.map { it.text })
    }

    // Regression: an operator method's name isn't an InkoName, and its crumb was its own parent, looping forever.
    fun testBreadcrumbsInOperatorMethods() {
        myFixture.configureByText("main.inko", "type Set {\n  fn ==(other: ref Set) -> Bool {\n    <caret>true\n  }\n}\n")
        assertEquals(listOf("Set", "=="), myFixture.breadcrumbsAtCaret.map { it.text })
    }
}
