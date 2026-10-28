package com.setdef.parakeet

import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.BaseRefactoringProcessor

/** Inline Method. */
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
        |
        |impl ToString for Box {
        |  fn pub to_string -> String {
        |    'box'
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

    private fun inUtil(marker: String) = At("src/app/util.inko", util, marker)
    private fun inMain(marker: String) = At("src/main.inko", main, marker)

    /** Cuttlebone's answers for this code, as the compiler resolves it. */
    private fun compiler() = Compiler()
        .resolves(inUtil("SCALE\n"), inUtil("SCALE ="))
        .resolves(inUtil("countdown(n - 1)"), inUtil("countdown(n:"))
        .resolves(inUtil("@size +"), inUtil("@size:"))
        .resolves(inUtil("grow(1)"), inUtil("grow(by"))
        .resolves(inUtil("grow(2)"), inUtil("grow(by"))
        .resolves(inMain("Box) ->"), inUtil("Box {"))
        .resolves(inMain("area(w"), inUtil("area(width"))
        .resolves(inMain("log('hi')"), inUtil("log(message"))
        .resolves(inMain("repeat(w"), inUtil("repeat(value"))
        .resolves(inMain("double(w"), inUtil("double(value"))
        .resolves(inMain("grow(5)"), inUtil("grow(by"))

    override fun setUp() {
        super.setUp()
        compiler().install()
        putTempDirInProjectContent()
        useFakeInko(FakeInkoCli { FakeInkoCli.ok(stderr = "[]") })
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

    private fun inlined(methodText: String, method: String, callText: String, call: String): Result<String> =
        InkoInlineMethod.inline(methodText, methodText.indexOf(method), callText, callText.indexOf(call), usesSelf = false).map { edits ->
            edits.sortedByDescending { it.range.startOffset }.fold(callText) { text, edit -> text.replaceRange(edit.range.startOffset, edit.range.endOffset, edit.text) }
        }

    // inko-syntax's `bytes.whitespace?(b)`, whose body names the module's constants (`byte == SPACE or …`): called
    // through the module's name, they're named through it too.
    fun testNamesFromTheMethodsModuleGoThroughTheModulesNameAtTheCall() {
        val method = "let SPACE = 32\n\nfn pub space?(byte: Int) -> Bool {\n  byte == SPACE\n}\n"
        assertEquals(
            "import app.bytes\n\nfn run(b: Int) -> Bool {\n  b == bytes.SPACE\n}\n",
            inlined(method, "space?(byte", "import app.bytes\n\nfn run(b: Int) -> Bool {\n  bytes.space?(b)\n}\n", "space?(b").getOrThrow(),
        )
    }

    private val things = "import std.stdio (Stdout)\n\ntype pub Thing {\n  fn pub static new -> Self {\n    Self()\n  }\n}\n\nfn pub run {\n  Stdout.new\n}\n"
    private val user = "import app.things (Thing)\nimport app.things\n\nfn go {\n  Thing.new\n  things.run\n}\n"

    /** Inlines the call at [marker] in user.inko of [name] from things.inko, documented as `inko doc` would. */
    private fun inlineFromThings(name: String, marker: String) {
        myFixture.addFileToProject("src/app/things.inko", things)
        myFixture.addFileToProject("src/user.inko", user)
        fun inThings(marker: String) = At("src/app/things.inko", things, marker)
        useFakeInko(FakeInkoCli { FakeInkoCli.ok(stderr = "[]") })
        compiler()
            .resolves(inThings("Self()"), inThings("Thing {"))
            .resolves(At("src/user.inko", user, "new\n"), inThings("new -> Self"))
            .resolves(At("src/user.inko", user, "run\n"), inThings("run {"))
            .install()
        val method = PsiTreeUtil.findChildrenOfType(file("src/app/things.inko"), InkoDeclaration::class.java).first { it.name == name && InkoCallHierarchy.isMethod(it) }
        val call = PsiTreeUtil.getParentOfType(file("src/user.inko").findElementAt(file("src/user.inko").text.indexOf(marker)), InkoName::class.java, false)!!
        InkoInlineMethodProcessor(project, method, call, keepMethod = true).run()
    }

    // yorickpeterse.com's `Videos.new` (`Self()`): `Self` is named, not imported.
    fun testSelfIsNeverImported() {
        inlineFromThings("new", "new\n")
        assertEquals(user.replace("Thing.new", "Thing()"), file("src/user.inko").text)
    }

    // inko-website's `build.run`, whose body uses `Site`, which its module imports: called through the module,
    // `Site` is imported still.
    fun testThroughTheModuleWhatTheBodyImportsIsImported() {
        inlineFromThings("run", "run\n")
        assertEquals("import app.things (Thing)\nimport app.things\nimport std.stdio (Stdout)\n\nfn go {\n  Thing.new\n  Stdout.new\n}\n", file("src/user.inko").text)
    }

    // inko-website's `build.run`, whose body uses the module `layouts` (`import site.layouts`): the call's module
    // imports it too.
    fun testImportsAModuleTheBodyUses() {
        val tool = "import std.env\n\nfn pub count -> Int {\n  env.arguments.size\n}\n"
        val caller = "import app.tool (count)\n\nfn go -> Int {\n  count\n}\n"
        myFixture.addFileToProject("src/app/tool.inko", tool)
        myFixture.addFileToProject("src/caller.inko", caller)
        compiler().resolves(At("src/caller.inko", caller, "count\n}"), At("src/app/tool.inko", tool, "count ->")).install()
        val method = PsiTreeUtil.findChildrenOfType(file("src/app/tool.inko"), InkoDeclaration::class.java).first { it.name == "count" }
        val call = PsiTreeUtil.getParentOfType(file("src/caller.inko").findElementAt(caller.indexOf("count\n}")), InkoName::class.java, false)!!
        InkoInlineMethodProcessor(project, method, call, keepMethod = true).run()
        assertEquals("import app.tool (count)\nimport std.env\n\nfn go -> Int {\n  env.arguments.size\n}\n", file("src/caller.inko").text)
    }

    // Through the processor: the call's module needs no import, since it names them through the module.
    fun testACallThroughTheModuleImportsNothing() {
        val other = "import app.util\n\nfn go -> Int {\n  util.area(1, 2)\n}\n"
        myFixture.addFileToProject("src/other.inko", other)
        compiler().resolves(At("src/other.inko", other, "area(1"), inUtil("area(width")).install()
        inlineCall("area", "src/other.inko", "area(1")
        assertEquals("import app.util\n\nfn go -> Int {\n  let w = 1 * util.SCALE\n  w * 2\n}\n", file("src/other.inko").text)
    }

    // kvi's `ReaderWriter.new`, whose body is `Self(…)`: outside the type, `Self` is that type by its name.
    fun testSelfInTheBodyBecomesTheTypesNameOutsideIt() {
        val pair = "type pub Pair {\n  let @a: Int\n\n  fn pub static of(a: Int) -> Self {\n    Self(a: a)\n  }\n}\n"
        assertEquals("import app.pair (Pair)\n\nfn run -> Pair {\n  let p = Pair(a: 1)\n  p\n}\n", inlined(pair, "of(a", "import app.pair (Pair)\n\nfn run -> Pair {\n  let p = Pair.of(1)\n  p\n}\n", "of(1").getOrThrow())
        // Within the type, `Self` stays.
        val within = pair.replace("  }\n}\n", "  }\n\n  fn pub static one -> Self {\n    Pair.of(1)\n  }\n}\n")
        assertTrue(inlined(within, "of(a", within, "of(1").getOrThrow().contains("fn pub static one -> Self {\n    Self(a: 1)\n  }"))
        // A generic type's name alone isn't `Self`, which has its type arguments.
        val box = "type pub Box[T] {\n  let @v: T\n\n  fn pub static of(v: T) -> Self {\n    Self(v: v)\n  }\n}\n"
        assertEquals(
            "`of` uses `Self`, which is `Box` with its type arguments, so it can only be inlined inside `Box`.",
            inlined(box, "of(v", "fn run {\n  let b = Box.of(1)\n}\n", "of(1").exceptionOrNull()?.message,
        )
    }

    // kvi's `impl Clone for Config { fn pub clone … }`: removing it would leave Box not implementing ToString.
    fun testKeepsAMethodThatImplementsATraitsMethod() {
        try {
            InkoInlineMethodProcessor(project, method("to_string"), null, keepMethod = false).run()
            fail("Expected a conflict")
        } catch (e: BaseRefactoringProcessor.ConflictsInTestsException) {
            assertEquals(listOf("`to_string` implements ToString's method, so it's kept: without it, Box wouldn't implement ToString."), e.messages.toList())
        }
        assertTrue(file("src/app/util.inko").text.contains("fn pub to_string -> String {"))
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
