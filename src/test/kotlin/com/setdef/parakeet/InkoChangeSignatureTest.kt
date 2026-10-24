package com.setdef.parakeet

import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.BaseRefactoringProcessor

/** Change Signature, with `inko doc` output supplied by a fake. */
class InkoChangeSignatureTest : InkoTestCase(onDisk = true) {
    private val util = """
        |type pub Greeter {
        |  let @name: String
        |
        |  fn pub static new(name: String) -> Greeter {
        |    Greeter(name: name)
        |  }
        |
        |  fn pub greet(greeting: String, punctuation: String) -> String {
        |    '${'$'}{greeting}, ${'$'}{@name}${'$'}{punctuation}'
        |  }
        |}
        |
        |fn pub add(a: Int, b: Int) -> Int {
        |  a + b
        |}
        |
        |fn pub twice(a: Int) -> Int {
        |  add(a, add(a, 0))
        |}
        |
        |fn pub log(message: String, level: Int) {
        |  message.size
        |}
        |
        |fn pub ping(times: Int) {
        |  nil
        |}
        |
        |type pub Parrot {
        |  fn pub greet(greeting: String, punctuation: String) -> String {
        |    greeting
        |  }
        |}
        |
        |trait pub Shape {
        |  fn area -> Int
        |}
        |""".trimMargin()

    private val main = """
        |import app.util (Greeter, Parrot, add, log, ping)
        |
        |fn run -> String {
        |  let g = Greeter.new('World')
        |  log('x', level: 1)
        |  log('y', 2)
        |  ping(3)
        |  g.greet('Hello', punctuation: '!')
        |}
        |
        |fn either(value: Greeter, maybe: Int) -> String {
        |  value.greet('a', 'b') + make.greet('c', 'd')
        |}
        |
        |fn sum -> Int {
        |  add(1, b: 2) + add(3, 4)
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
            fun member(name: String, marker: String, column: Int = 3) = FakeInkoDoc.member(name, utilFile, line(util, marker), column)
            listOf(
                FakeInkoDoc.module(
                    "app.util", utilFile,
                    methods = listOf(member("add", "fn pub add", 1), member("twice", "fn pub twice", 1), member("log", "fn pub log", 1), member("ping", "fn pub ping", 1)),
                    types = listOf(FakeInkoDoc.type(
                        "Greeter", 1,
                        staticMethods = listOf(member("new", "fn pub static new")),
                        instanceMethods = listOf(member("greet", "fn pub greet")),
                    ), FakeInkoDoc.type(
                        "Parrot", line(util, "type pub Parrot"),
                        instanceMethods = listOf(FakeInkoDoc.member("greet", utilFile, line(util, "  fn pub greet(greeting: String, punctuation: String) -> String {\n    greeting"), 3)),
                    )),
                    traits = listOf(FakeInkoDoc.trait("Shape", line(util, "trait pub Shape"), requiredMethods = listOf(member("area", "fn area")))),
                ),
                FakeInkoDoc.module("main", mainFile, methods = listOf(
                    FakeInkoDoc.member("run", mainFile, line(main, "fn run"), 1),
                    FakeInkoDoc.member("sum", mainFile, line(main, "fn sum"), 1),
                )),
            )
        })
        myFixture.addFileToProject("src/app/util.inko", util)
        myFixture.addFileToProject("src/main.inko", main)
    }

    private fun method(name: String): InkoDeclaration {
        val file = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("src/app/util.inko"))!!
        return PsiTreeUtil.findChildrenOfType(file, InkoDeclaration::class.java).first { it.name == name && InkoCallHierarchy.isMethod(it) }
    }

    private fun change(name: String, change: InkoSignatureChange) {
        InkoChangeSignatureProcessor(project, method(name), change).run()
    }

    private fun text(path: String) = myFixture.findFileInTempDir(path).let { PsiManager.getInstance(project).findFile(it)!!.text }

    fun testParsesSignatures() {
        val signature = InkoSignatures.at(util, util.indexOf("add("))!!
        assertEquals("add", signature.name)
        assertEquals(listOf(InkoParameter("a", "Int"), InkoParameter("b", "Int")), signature.parameters)
        assertEquals("Int", signature.returnType)
        assertEquals("add(a: Int, b: Int) -> Int", util.substring(signature.headerRange.startOffset, signature.headerRange.endOffset))
        val log = InkoSignatures.at(util, util.indexOf("log("))!!
        assertNull(log.returnType)
        assertEquals("area", InkoSignatures.at(util, util.indexOf("area"))!!.name)
    }

    fun testParsesCalls() {
        val text = "fn a {\n  foo(1, b: [2, 3])\n  x.foo\n  foo[Int](1)\n}\n"
        val call = InkoSignatures.callAt(text, text.indexOf("foo("))!!
        assertEquals(listOf(InkoArgument(null, "1"), InkoArgument("b", "[2, 3]")), call.arguments)
        assertNull(InkoSignatures.callAt(text, text.indexOf("foo\n"))!!.argumentsRange)
        assertEquals("[Int]", InkoSignatures.callAt(text, text.indexOf("foo["))!!.typeArguments)
    }

    fun testReordersParametersAndArguments() {
        change("add", InkoSignatureChange("add", listOf(InkoParameterChange(1, "b", "Int"), InkoParameterChange(0, "a", "Int")), "Int"))
        assertTrue(text("src/app/util.inko").contains("fn pub add(b: Int, a: Int) -> Int {\n  a + b\n}"))
        // Nested calls are both updated; a named argument stays named, and so does everything after it.
        assertTrue(text("src/app/util.inko").contains("add(add(0, a), a)"))
        assertTrue(text("src/main.inko").contains("add(b: 2, a: 1) + add(4, 3)"))
    }

    fun testRenamesParametersInTheBodyAndNamedArguments() {
        change("add", InkoSignatureChange("add", listOf(InkoParameterChange(0, "a", "Int"), InkoParameterChange(1, "other", "Int")), "Int"))
        assertTrue(text("src/app/util.inko").contains("fn pub add(a: Int, other: Int) -> Int {\n  a + other\n}"))
        assertTrue(text("src/main.inko").contains("add(1, other: 2) + add(3, 4)"))
    }

    fun testAddsParametersWithTheValueCallsPass() {
        change("add", InkoSignatureChange("add", listOf(InkoParameterChange(0, "a", "Int"), InkoParameterChange(1, "b", "Int"), InkoParameterChange(-1, "c", "Int", "0")), "Int"))
        assertTrue(text("src/app/util.inko").contains("fn pub add(a: Int, b: Int, c: Int) -> Int {"))
        assertTrue(text("src/main.inko").contains("add(1, b: 2, c: 0) + add(3, 4, 0)"))
    }

    fun testRemovesUnusedParametersAndTheirArguments() {
        change("log", InkoSignatureChange("log", listOf(InkoParameterChange(0, "message", "String")), null))
        assertTrue(text("src/app/util.inko").contains("fn pub log(message: String) {"))
        assertTrue(text("src/main.inko").contains("log('x')\n  log('y')"))
    }

    fun testRenamesMethodsAndChangesReturnTypes() {
        change("greet", InkoSignatureChange("salute", listOf(InkoParameterChange(0, "greeting", "String"), InkoParameterChange(1, "punctuation", "String")), "ref String"))
        assertTrue(text("src/app/util.inko").contains("fn pub salute(greeting: String, punctuation: String) -> ref String {"))
        assertTrue(text("src/main.inko").contains("g.salute('Hello', punctuation: '!')"))
    }

    fun testRemovingEveryParameterDropsTheParentheses() {
        change("ping", InkoSignatureChange("ping", emptyList(), null))
        assertTrue(text("src/app/util.inko").contains("fn pub ping {\n  nil\n}"))
        // Inko leaves out empty parentheses; the import keeps the name.
        assertTrue(text("src/main.inko").contains("  ping\n"))
        assertTrue(text("src/main.inko").contains("import app.util (Greeter, Parrot, add, log, ping)"))
    }

    fun testWarnsWhenARemovedParameterIsStillUsed() {
        try {
            change("add", InkoSignatureChange("add", listOf(InkoParameterChange(0, "a", "Int")), "Int"))
            fail("Expected a conflict")
        } catch (e: BaseRefactoringProcessor.ConflictsInTestsException) {
            assertTrue(e.messages.toString(), e.messages.any { "`b` is removed" in it })
        }
    }

    // A call on a value of unknown type might be Parrot's greet, so the preview shows it to be excluded or kept.
    fun testCallsOnValuesOfUnknownTypeAreUncertain() {
        val file = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("src/main.inko"))!!
        fun nameAt(marker: String) = file.findElementAt(main.indexOf(marker))!!
        assertFalse(InkoChangeSignature.isUncertain(nameAt("greet('a'")))
        assertTrue(InkoChangeSignature.isUncertain(nameAt("greet('c'")))
    }

    fun testFindsTheMethodFromItsNameOrACall() {
        val file = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("src/main.inko"))!!
        val handler = InkoChangeSignatureHandler()
        assertEquals(method("add"), handler.findTargetMember(file.findElementAt(main.indexOf("add(3"))!!))
        assertEquals(method("add"), handler.findTargetMember(method("add")))
        // Not a method: a local variable.
        assertNull(handler.findTargetMember(file.findElementAt(main.indexOf("g = "))!!))
    }

    // The dialog starts from the method's current signature.
    fun testTheDialogStartsWithTheCurrentSignature() {
        val add = method("add")
        val dialog = InkoChangeSignatureDialog(project, add, InkoSignatures.at(add.containingFile.text, add.textRange.startOffset)!!)
        try {
            val change = dialog.change()
            assertEquals("add", change.name)
            assertEquals("Int", change.returnType)
            assertEquals(listOf(InkoParameterChange(0, "a", "Int"), InkoParameterChange(1, "b", "Int")), change.parameters)
        } finally {
            dialog.close(0)
        }
    }

    fun testRefusesTraitMethods() {
        assertNotNull(InkoChangeSignature.unsupported(method("area")))
        assertNull(InkoChangeSignature.unsupported(method("add")))
    }

    fun testValidatesTheChange() {
        assertNotNull(InkoChangeSignature.invalid(InkoSignatureChange("add", listOf(InkoParameterChange(-1, "c", "Int")), null)))
        assertNotNull(InkoChangeSignature.invalid(InkoSignatureChange("Add", emptyList(), null)))
        assertNotNull(InkoChangeSignature.invalid(InkoSignatureChange("add", listOf(InkoParameterChange(0, "a", "Int"), InkoParameterChange(1, "a", "Int")), null)))
        assertNull(InkoChangeSignature.invalid(InkoSignatureChange("add?", listOf(InkoParameterChange(-1, "c", "Int", "0")), null)))
    }
}
