package com.setdef.parakeet

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.openapi.actionSystem.IdeActions

class InkoPasteImportsTest : InkoTestCase(onDisk = true) {
    private var setting = CodeInsightSettings.YES

    override fun setUp() {
        super.setUp()
        setting = CodeInsightSettings.getInstance().ADD_IMPORTS_ON_PASTE
        CodeInsightSettings.getInstance().ADD_IMPORTS_ON_PASTE = CodeInsightSettings.YES
    }

    override fun tearDown() {
        try {
            CodeInsightSettings.getInstance().ADD_IMPORTS_ON_PASTE = setting
        } finally {
            super.tearDown()
        }
    }

    private fun copyThenPaste(from: String, selected: String, into: String): String {
        val source = myFixture.addFileToProject("src/app/source.inko", from)
        myFixture.configureFromExistingVirtualFile(source.virtualFile)
        val start = from.indexOf(selected)
        myFixture.editor.selectionModel.setSelection(start, start + selected.length)
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_COPY)

        val target = myFixture.addFileToProject("src/app/target.inko", into.replace("<caret>", ""))
        myFixture.configureFromExistingVirtualFile(target.virtualFile)
        myFixture.editor.caretModel.moveToOffset(into.indexOf("<caret>"))
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_PASTE)
        return myFixture.editor.document.text
    }

    private val source = """
        |import std.stdio (Stdout)
        |import std.fmt (Formatter as Fmt)
        |import app.util
        |
        |let LIMIT = 10
        |
        |fn run(value: Int) {
        |  let out = Stdout.new
        |  out.print(util.name)
        |  Fmt.new
        |  value + LIMIT
        |}
        |""".trimMargin()

    private val body = "  let out = Stdout.new\n  out.print(util.name)\n  Fmt.new\n  value + LIMIT\n"

    fun testAddsTheImportsThePastedCodeUses() {
        assertEquals(
            // In the order the pasted code uses them.
            "import std.stdio (Stdout)\nimport app.util\nimport std.fmt (Formatter as Fmt)\nimport app.source (LIMIT)\n\nfn other {\n$body}\n",
            copyThenPaste(source, body, "fn other {\n<caret>}\n"),
        )
    }

    fun testAddsOnlyWhatIsMissing() {
        assertEquals(
            "import std.stdio (Stderr, Stdout)\nimport app.util\nimport std.fmt (Formatter as Fmt)\nimport app.source (LIMIT)\n\nfn other {\n$body}\n",
            copyThenPaste(source, body, "import std.stdio (Stderr)\n\nfn other {\n<caret>}\n"),
        )
    }

    fun testLocalsAndMembersNeedNoImports() {
        assertEquals("fn other {\n  out.print\n}\n", copyThenPaste(source, "out.print", "fn other {\n  <caret>\n}\n"))
    }

    fun testNothingWhenTheSettingIsNever() {
        CodeInsightSettings.getInstance().ADD_IMPORTS_ON_PASTE = CodeInsightSettings.NO
        assertEquals("fn other {\n$body}\n", copyThenPaste(source, body, "fn other {\n<caret>}\n"))
    }

    fun testPastingWithinTheSameModuleAddsNothing() {
        val text = copyThenPaste(source, "value + LIMIT", source.replace("fn run", "fn again {\n  <caret>\n}\n\nfn run"))
        assertFalse(text.contains("import app.source"))
    }
}
