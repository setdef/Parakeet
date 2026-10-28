package com.setdef.parakeet

import java.nio.file.Path

/** Implement Methods (⌃I) and Override Methods (⌃O). The quick fixes for missing methods and traits are Cuttlebone's (InkoQuickFixesTest). */
class InkoImplementMethodsTest : InkoTestCase(onDisk = true) {
    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "src/app/show.inko",
            "trait pub Show[T] {\n  fn pub show(value: T) -> String\n  fn pub name -> String\n\n  fn pub debug(value: T) -> String {\n    show(value)\n  }\n}\n",
        )
        useFakeInko(FakeInkoCli { FakeInkoCli.ok(stderr = "[]") })
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

    fun testRequiredMethodsFromUnsavedSource() {
        // A trait defined in the same (unsaved) file: its bodyless methods are the required ones.
        val definitions = InkoSourceDefinitions.of(Path.of("/x/src/main.inko"), "main", "trait Walk {\n  fn walk -> Int\n  fn run -> Int {\n    1\n  }\n}\n")
        assertEquals(mapOf("walk" to true, "run" to false), definitions.filter { it.owner == "Walk" }.associate { it.name to it.required })
    }
}
