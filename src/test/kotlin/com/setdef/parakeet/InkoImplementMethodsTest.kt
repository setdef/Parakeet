package com.setdef.parakeet

import java.nio.file.Path

/** Implement Methods (⌃I), Override Methods (⌃O), and the quick fix, with Cuttlebone's diagnostics supplied by a stub. */
class InkoImplementMethodsTest : InkoTestCase(onDisk = true) {
    private var reportMissing = false
    private var reportMissingTrait = false

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "src/app/show.inko",
            "trait pub Show[T] {\n  fn pub show(value: T) -> String\n  fn pub name -> String\n\n  fn pub debug(value: T) -> String {\n    show(value)\n  }\n}\n",
        )
        useFakeInko(FakeInkoCli { FakeInkoCli.ok(stderr = "[]") })
        pullDiagnostics { _, text ->
            val lines = text.lines()
            when {
                // Like the compiler: an impl of a trait requiring Show[Int], which Thing lacks.
                reportMissingTrait -> listOf(InkoCheckTest.diagnostic(lines.indexOfFirst { it.startsWith("impl Walk") }, 0, 1, "the trait 'Show[Int]' isn't implemented for type 'Thing'", "missing-trait"))
                // Like the compiler: an error at the impl for each missing method.
                reportMissing -> listOf(InkoCheckTest.diagnostic(lines.indexOfFirst { it.startsWith("impl") }, 0, 4, "the method 'show' must be implemented for 'Thing'", "invalid-implementation"))
                else -> emptyList()
            }
        }
    }

    private fun run(text: String, action: String): String {
        val file = myFixture.addFileToProject("src/main.inko", text.replace("<caret>", ""))
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(text.indexOf("<caret>"))
        myFixture.performEditorAction(action)
        return myFixture.editor.document.text
    }

    private val header = "import app.show (Show)\n\ntype Thing {}\n\n"

    fun testImplementsTheRequiredMethodsWithTheImplsTypeArguments() {
        val text = header + "impl Show[Int] for Thing {\n  fn pub name -> String {\n    'thing'\n  }<caret>\n}\n"
        assertEquals(
            header + "impl Show[Int] for Thing {\n  fn pub name -> String {\n    'thing'\n  }\n\n" +
                "  fn pub show(value: Int) -> String {\n    panic('not implemented')\n  }\n}\n",
            run(text, "ImplementMethods"),
        )
        // The caret lands in the new method's body.
        assertTrue(myFixture.editor.document.text.substring(myFixture.caretOffset).startsWith("panic('not implemented')"))
    }

    fun testImplementsIntoAnEmptyImpl() {
        val text = header + "impl Show[Int] for Thing {<caret>}\n"
        assertEquals(
            header + "impl Show[Int] for Thing {\n" +
                "  fn pub show(value: Int) -> String {\n    panic('not implemented')\n  }\n\n" +
                "  fn pub name -> String {\n    panic('not implemented')\n  }\n}\n",
            run(text, "ImplementMethods"),
        )
    }

    fun testOverridesDefaultMethods() {
        val text = header + "impl Show[Int] for Thing {\n<caret>}\n"
        assertEquals(
            header + "impl Show[Int] for Thing {\n  fn pub debug(value: Int) -> String {\n    panic('not implemented')\n  }\n}\n",
            run(text, "OverrideMethods"),
        )
    }

    fun testDoesNothingOutsideATraitImpl() {
        val text = header + "impl Thing {\n<caret>}\n"
        assertEquals(text.replace("<caret>", ""), run(text, "ImplementMethods"))
    }

    fun testQuickFixForTheCompilersMissingMethodError() {
        reportMissing = true
        val text = header + "impl Show[Int] for Thing {\n  fn pub name -> String {\n    'thing'\n  }\n}\n"
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("src/main.inko", text).virtualFile)
        myFixture.doHighlighting()
        myFixture.launchAction(myFixture.getAllQuickFixes().single { it.text == "Implement missing methods" })
        assertTrue(myFixture.editor.document.text, "fn pub show(value: Int) -> String {\n    panic('not implemented')" in myFixture.editor.document.text)
    }

    fun testQuickFixForAMissingRequiredTrait() {
        reportMissingTrait = true
        val text = header + "trait Walk {}\n\nimpl Walk for Thing {\n}\n"
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("src/main.inko", text).virtualFile)
        myFixture.doHighlighting()
        myFixture.launchAction(myFixture.getAllQuickFixes().single { it.text == "Implement 'Show[Int]' for 'Thing'" })
        assertEquals(
            text + "\nimpl Show[Int] for Thing {\n" +
                "  fn pub show(value: Int) -> String {\n    panic('not implemented')\n  }\n\n" +
                "  fn pub name -> String {\n    panic('not implemented')\n  }\n}\n",
            myFixture.editor.document.text,
        )
    }

    fun testRequiredMethodsFromUnsavedSource() {
        // A trait defined in the same (unsaved) file: its bodyless methods are the required ones.
        val definitions = InkoSourceDefinitions.of(Path.of("/x/src/main.inko"), "main", "trait Walk {\n  fn walk -> Int\n  fn run -> Int {\n    1\n  }\n}\n")
        assertEquals(mapOf("walk" to true, "run" to false), definitions.filter { it.owner == "Walk" }.associate { it.name to it.required })
    }
}
