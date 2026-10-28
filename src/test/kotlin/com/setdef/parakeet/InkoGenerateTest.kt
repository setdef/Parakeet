package com.setdef.parakeet

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.testFramework.TestActionEvent

/** Generate (⌘N / Alt+Insert). */
class InkoGenerateTest : InkoTestCase(onDisk = true) {
    private lateinit var std: java.nio.file.Path

    override fun setUp() {
        super.setUp()
        putTempDirInProjectContent()
        myFixture.addFileToProject("inko.pkg", "")
        std = FakeStd.write(java.nio.file.Path.of(myFixture.tempDirPath).parent.resolve("std-$name"), mapOf(
            "std.cmp" to "trait pub Equal {\n  fn pub ==(other: ref Self) -> Bool\n}\n",
            "std.string" to "type builtin String {}\n\ntrait pub ToString {\n  fn pub to_string -> String\n}\n",
            "std.int" to "type builtin Int {}\n",
            "std.bool" to "type builtin Bool {}\n",
        ))
        useFakeInko(FakeStd.cli(std))
    }

    override fun tearDown() {
        try {
            InkoGenerateTraitImplAction.chooser = null
        } finally {
            super.tearDown()
        }
    }

    private fun open(text: String) {
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("src/app/point.inko", text.replace("<caret>", "")).virtualFile)
        myFixture.editor.caretModel.moveToOffset(text.indexOf("<caret>"))
    }

    /** Whether the action shows (and is enabled) at the caret, without running it. */
    private fun isOffered(actionId: String): Boolean {
        val action = ActionManager.getInstance().getAction(actionId)
        val event = TestActionEvent.createTestEvent(action, (myFixture.editor as EditorEx).dataContext)
        action.update(event)
        return event.presentation.isEnabledAndVisible
    }

    private val point = "type pub Point {\n  let @x: Int\n  let pub @label: String\n\n  fn pub sum -> Int {\n    @x\n  }<caret>\n}\n"

    fun testGeneratesAConstructorFromEveryField() {
        open(point)
        myFixture.performEditorAction("Parakeet.GenerateConstructor")
        assertEquals(
            "type pub Point {\n  let @x: Int\n  let pub @label: String\n\n  fn pub static new(x: Int, label: String) -> Self {\n    Self(x: x, label: label)\n  }\n\n  fn pub sum -> Int {\n    @x\n  }\n}\n",
            myFixture.editor.document.text,
        )
    }

    fun testConstructorOnlyInTypesWithFieldsAndNoNew() {
        open("type Empty {<caret>\n}\n")
        // Offered inside any type, but explains why it can't when there's nothing to build from.
        assertTrue(isOffered("Parakeet.GenerateConstructor"))
        val hint = runCatching { myFixture.performEditorAction("Parakeet.GenerateConstructor") }.exceptionOrNull()
        assertEquals("This type has no fields to build a constructor from.", hint?.message)
        assertEquals("type Empty {\n}\n", myFixture.editor.document.text)
        open("fn main {<caret>}\n")
        assertFalse(isOffered("Parakeet.GenerateConstructor"))
    }

    fun testImplementsAChosenTraitWithItsRequiredMethods() {
        InkoGenerateTraitImplAction.chooser = { traits -> traits.single { it.name == "Equal" } }
        open(point)
        myFixture.performEditorAction("Parakeet.GenerateTraitImpl")
        val text = myFixture.editor.document.text
        assertTrue(text, text.startsWith("import std.cmp (Equal)\n\ntype pub Point {"))
        assertTrue(text, text.contains("impl Equal for Point {\n  fn pub ==(other: ref Self) -> Bool {\n    panic('not implemented')\n  }\n}"))
    }

    fun testTraitsAlreadyImplementedAreNotOffered() {
        var offered = emptyList<String>()
        InkoGenerateTraitImplAction.chooser = { traits -> offered = traits.map { it.name }; null }
        open("import std.cmp (Equal)\n\ntype Point {\n  let @x: Int<caret>\n}\n\nimpl Equal for Point {\n  fn pub ==(other: ref Point) -> Bool {\n    true\n  }\n}\n")
        myFixture.performEditorAction("Parakeet.GenerateTraitImpl")
        assertEquals(listOf("ToString"), offered)
    }

    // An `impl` in another module counts too: Cuttlebone's type database says which traits the type implements.
    fun testTraitsImplementedElsewhereAreNotOffered() {
        val text = "type Point {\n  let @x: Int<caret>\n}\n"
        val equal = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(std.resolve("std/cmp.inko"))!!
        Compiler().answers("typeHierarchy/supertypes", At("src/app/point.inko", text, "Point")) {
            listOf(org.eclipse.lsp4j.Location(equal.url, org.eclipse.lsp4j.Range(org.eclipse.lsp4j.Position(0, 10), org.eclipse.lsp4j.Position(0, 15))))
        }.install()
        var offered = emptyList<String>()
        InkoGenerateTraitImplAction.chooser = { traits -> offered = traits.map { it.name }; null }
        open(text)
        myFixture.performEditorAction("Parakeet.GenerateTraitImpl")
        assertEquals(listOf("ToString"), offered)
    }

    fun testImplementAndOverrideMethodsAreInGenerateInsideImpls() {
        open("import std.cmp (Equal)\n\ntype Point {\n  let @x: Int\n}\n\nimpl Equal for Point {<caret>\n}\n")
        assertTrue(isOffered("ImplementMethods"))
        assertTrue(isOffered("OverrideMethods"))
    }
}
