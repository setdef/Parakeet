package com.setdef.parakeet

import com.intellij.psi.search.PsiTodoSearchHelper

class InkoTodoTest : InkoTestCase() {
    fun testFindsTodosInCommentsOnly() {
        val text = "# TODO: handle errors\nfn a {\n  let todo = 'TODO: not a comment'\n  # FIXME later\n}\n"
        myFixture.configureByText("main.inko", text)
        val items = PsiTodoSearchHelper.getInstance(project).findTodoItems(myFixture.file)
        assertEquals(listOf("TODO: handle errors", "FIXME later"), items.sortedBy { it.textRange.startOffset }.map { myFixture.editor.document.getText(it.textRange) })
        assertEquals(2, PsiTodoSearchHelper.getInstance(project).getTodoItemsCount(myFixture.file))
    }
}
