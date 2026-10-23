package com.setdef.parakeet

import java.nio.file.Files
import java.nio.file.Path

/** Implement Methods (⌃I), Override Methods (⌃O), and the quick fix, with inko doc and inko check supplied by a fake. */
class InkoImplementMethodsTest : InkoTestCase(onDisk = true) {
    private var reportMissing = false

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "src/app/show.inko",
            "trait pub Show[T] {\n  fn pub show(value: T) -> String\n  fn pub name -> String\n\n  fn pub debug(value: T) -> String {\n    show(value)\n  }\n}\n",
        )
        val doc = FakeInkoDoc.cli { mirror ->
            val file = mirror.resolve("src/app/show.inko")
            listOf(FakeInkoDoc.module(
                "app.show", file,
                traits = listOf(FakeInkoDoc.trait(
                    "Show", 1,
                    signature = "trait pub Show[T]",
                    requiredMethods = listOf(
                        FakeInkoDoc.member("show", file, 2, signature = "fn pub show(value: T) -> String"),
                        FakeInkoDoc.member("name", file, 3, signature = "fn pub name -> String"),
                    ),
                    defaultMethods = listOf(FakeInkoDoc.member("debug", file, 5, signature = "fn pub debug(value: T) -> String")),
                )),
            ))
        }
        useFakeInko(FakeInkoCli { call ->
            if (call.args.first() != "check") return@FakeInkoCli doc.run(call.args, call.workingDirectory)!!
            if (!reportMissing) return@FakeInkoCli FakeInkoCli.ok(stderr = "[]")
            // Like the compiler: an error at the impl for each missing method.
            val copy = Path.of(call.args.last())
            val line = Files.readAllLines(copy).indexOfFirst { it.startsWith("impl") } + 1
            FakeInkoCli.ok(stderr = """[{"id": "invalid-implementation", "level": "error", "file": "$copy", "lines": [$line, $line], "columns": [1, 4], "message": "the method 'show' must be implemented for 'Thing'"}]""")
        })
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

    fun testRequiredMethodsFromUnsavedSource() {
        // A trait defined in the same (unsaved) file: its bodyless methods are the required ones.
        val definitions = InkoSourceDefinitions.of(Path.of("/x/src/main.inko"), "main", "trait Walk {\n  fn walk -> Int\n  fn run -> Int {\n    1\n  }\n}\n")
        assertEquals(mapOf("walk" to true, "run" to false), definitions.filter { it.owner == "Walk" }.associate { it.name to it.required })
    }
}
