package com.setdef.parakeet

import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.BaseRefactoringProcessor

/** Inline Method, with `inko doc` output supplied by a fake. */
class InkoInlineMethodTest : InkoTestCase(onDisk = true) {
    private val util = """
        |let pub SCALE = 3
        |
        |fn pub double(value: Int) -> Int {
        |  value * 2
        |}
        |
        |fn pub area(width: Int, height: Int) -> Int {
        |  let w = width * SCALE
        |  w * height
        |}
        |
        |fn pub log(message: String) {
        |  message.size
        |  message.size
        |}
        |
        |fn pub repeat(value: Int) -> Int {
        |  value + value
        |}
        |
        |fn pub countdown(n: Int) -> Int {
        |  if n == 0 { return 0 }
        |  countdown(n - 1)
        |}
        |
        |type pub Box {
        |  let @size: Int
        |
        |  fn pub grow(by: Int) -> Int {
        |    @size + by
        |  }
        |
        |  fn pub twice -> Int {
        |    grow(1) + self.grow(2)
        |  }
        |}
        |""".trimMargin()

    private val main = """
        |import app.util (Box, area, double, log, repeat)
        |
        |fn run(box: Box) -> Int {
        |  let w = 10
        |  let a = area(w, 2)
        |  log('hi')
        |  let r = repeat(w + 1)
        |  double(w + 1) * 3 + box.grow(5) + w + a + r
        |}
        |""".trimMargin()

    private fun line(text: String, marker: String) = text.substring(0, text.indexOf(marker)).count { it == '\n' } + 1

    override fun setUp() {
        super.setUp()
        // Parakeet's own reading of the code, which stands in only where Cuttlebone can't check it.
        cuttleboneCantCheckTheCode()
        putTempDirInProjectContent()
        useFakeInko(FakeInkoDoc.cli { mirror ->
            val utilFile = mirror.resolve("src/app/util.inko")
            val mainFile = mirror.resolve("src/main.inko")
            fun function(name: String) = FakeInkoDoc.member(name, utilFile, line(util, "fn pub $name"), 1)
            listOf(
                FakeInkoDoc.module(
                    "app.util", utilFile,
                    constants = listOf(mapOf("name" to "SCALE", "location" to FakeInkoDoc.location(1), "type" to "let pub SCALE", "documentation" to "")),
                    methods = listOf("double", "area", "log", "repeat", "countdown").map(::function),
                    types = listOf(FakeInkoDoc.type(
                        "Box", line(util, "type pub Box"),
                        fields = listOf(FakeInkoDoc.member("size", utilFile, line(util, "let @size"))),
                        instanceMethods = listOf(FakeInkoDoc.member("grow", utilFile, line(util, "fn pub grow")), FakeInkoDoc.member("twice", utilFile, line(util, "fn pub twice"))),
                    )),
                ),
                FakeInkoDoc.module("main", mainFile, methods = listOf(FakeInkoDoc.member("run", mainFile, line(main, "fn run"), 1))),
            )
        })
        myFixture.addFileToProject("src/app/util.inko", util)
        myFixture.addFileToProject("src/main.inko", main)
    }

    private fun file(path: String): PsiFile = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir(path))!!

    private fun method(name: String): InkoDeclaration =
        PsiTreeUtil.findChildrenOfType(file("src/app/util.inko"), InkoDeclaration::class.java).first { it.name == name && InkoCallHierarchy.isMethod(it) }

    /** Inlines the call at [marker] in [path] only. */
    private fun inlineCall(name: String, path: String, marker: String) {
        val text = file(path).text
        val call = PsiTreeUtil.getParentOfType(file(path).findElementAt(text.indexOf(marker)), InkoName::class.java, false)!!
        InkoInlineMethodProcessor(project, method(name), call, keepMethod = true).run()
    }

    fun testIntoALetRenamingClashingLocalsAndImportingWhatItNeeds() {
        inlineCall("area", "src/main.inko", "area(w")
        val text = file("src/main.inko").text
        assertTrue(text, text.contains("  let w = 10\n  let w2 = w * SCALE\n  let a = w2 * 2\n"))
        assertTrue(text, text.startsWith("import app.util (Box, area, double, log, repeat, SCALE)\n"))
        // Just this call: the method stays.
        assertTrue(file("src/app/util.inko").text.contains("fn pub area("))
    }

    fun testParenthesizesWhereOperatorsWouldRegroup() {
        inlineCall("double", "src/main.inko", "double(w")
        assertTrue(file("src/main.inko").text.contains("  ((w + 1) * 2) * 3 + box.grow(5)"))
    }

    fun testBindsAnArgumentUsedMoreThanOnce() {
        inlineCall("repeat", "src/main.inko", "repeat(w")
        assertTrue(file("src/main.inko").text.contains("  let value = w + 1\n  let r = value + value\n"))
    }

    fun testACallStatementBecomesTheBodysStatements() {
        inlineCall("log", "src/main.inko", "log('hi')")
        assertTrue(file("src/main.inko").text.contains("  'hi'.size\n  'hi'.size\n  let r ="))
    }

    fun testInlinesBareAndSelfCallsWithinTheType() {
        inlineCall("grow", "src/app/util.inko", "grow(1)")
        inlineCall("grow", "src/app/util.inko", "grow(2)")
        assertTrue(file("src/app/util.inko").text.contains("    (@size + 1) + (@size + 2)\n"))
    }

    fun testRefusesCallsOnOtherValuesWhenTheBodyUsesFields() {
        try {
            inlineCall("grow", "src/main.inko", "grow(5)")
            fail("Expected a conflict")
        } catch (e: BaseRefactoringProcessor.ConflictsInTestsException) {
            assertTrue(e.messages.toString(), e.messages.single().contains("not on `box`"))
        }
    }

    fun testRefusesMethodsThatCantBeInlined() {
        assertTrue(InkoInlineMethod.unsupported(util, util.indexOf("countdown"))!!.contains("`return`"))
        assertNull(InkoInlineMethod.unsupported(util, util.indexOf("double")))
    }

    fun testInliningEveryCallRemovesTheMethodAndItsImport() {
        InkoInlineMethodProcessor(project, method("double"), null, keepMethod = false).run()
        assertFalse(file("src/app/util.inko").text.contains("fn pub double"))
        val text = file("src/main.inko").text
        assertTrue(text, text.startsWith("import app.util (Box, area, log, repeat)\n"))
        assertTrue(text, text.contains("((w + 1) * 2) * 3"))
    }

    fun testTheDialogBuilds() {
        InkoInlineMethodDialog(project, method("double"), null).close(0)
    }

    fun testTheInlineActionInlinesTheCallAtTheCaret() {
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("src/main.inko"))
        myFixture.editor.caretModel.moveToOffset(main.indexOf("log('hi')"))
        myFixture.performEditorAction("Inline")
        assertTrue(myFixture.editor.document.text.contains("  'hi'.size\n  'hi'.size\n"))
        assertTrue(file("src/app/util.inko").text.contains("fn pub log("))
    }
}
