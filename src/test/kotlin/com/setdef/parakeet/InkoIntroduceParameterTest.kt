package com.setdef.parakeet

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager

/** Introduce Parameter, with `inko doc` output supplied by a fake. */
class InkoIntroduceParameterTest : InkoTestCase(onDisk = true) {
    private val util = """
        |let LIMIT = 10
        |let pub MAX = 20
        |
        |fn pub clamp(value: Int) -> Int {
        |  if value > LIMIT * 2 { LIMIT * 2 } else { value.min(MAX + 1) }
        |}
        |
        |type pub Box {
        |  let @size: Int
        |
        |  fn pub grow(by: Int) -> Int {
        |    @size + by + self.extra + 'x'.size
        |  }
        |
        |  fn extra -> Int {
        |    1
        |  }
        |}
        |""".trimMargin()

    private val main = """
        |import app.util (clamp)
        |
        |fn run -> Int {
        |  clamp(5) + clamp(value: 7)
        |}
        |""".trimMargin()

    private fun line(text: String, marker: String) = text.substring(0, text.indexOf(marker)).count { it == '\n' } + 1

    override fun setUp() {
        super.setUp()
        putTempDirInProjectContent()
        useFakeInko(FakeInkoDoc.cli { mirror ->
            val utilFile = mirror.resolve("src/app/util.inko")
            val mainFile = mirror.resolve("src/main.inko")
            listOf(
                FakeInkoDoc.module(
                    "app.util", utilFile,
                    constants = listOf(
                        mapOf("name" to "LIMIT", "location" to FakeInkoDoc.location(1), "type" to "let LIMIT", "documentation" to ""),
                        mapOf("name" to "MAX", "location" to FakeInkoDoc.location(2), "type" to "let pub MAX", "documentation" to ""),
                    ),
                    methods = listOf(FakeInkoDoc.member("clamp", utilFile, line(util, "fn pub clamp"), 1)),
                    types = listOf(FakeInkoDoc.type(
                        "Box", line(util, "type pub Box"),
                        instanceMethods = listOf(FakeInkoDoc.member("grow", utilFile, line(util, "fn pub grow")), FakeInkoDoc.member("extra", utilFile, line(util, "fn extra"))),
                    )),
                ),
                FakeInkoDoc.module("main", mainFile, methods = listOf(FakeInkoDoc.member("run", mainFile, line(main, "fn run"), 1))),
            )
        })
        myFixture.addFileToProject("src/app/util.inko", util)
        myFixture.addFileToProject("src/main.inko", main)
    }

    private fun file(path: String): PsiFile = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir(path))!!

    private fun plan(text: String, nth: Int = 0): Result<InkoIntroduceParameter.Plan> {
        var start = -1
        repeat(nth + 1) { start = util.indexOf(text, start + 1) }
        return InkoIntroduceParameter.plan(file("src/app/util.inko"), TextRange(start, start + text.length))
    }

    fun testWarnsWhenCallersElsewhereCantSeeAPrivateName() {
        val plan = plan("LIMIT * 2").getOrThrow()
        assertEquals(setOf("LIMIT"), plan.privateNames)
        try {
            InkoIntroduceParameter.processor(project, plan, "limit", "Int", replaceAll = true).run()
            fail("Expected a conflict")
        } catch (e: com.intellij.refactoring.BaseRefactoringProcessor.ConflictsInTestsException) {
            assertTrue(e.messages.toString(), e.messages.single().startsWith("`LIMIT` is private to app.util, so the calls in main.inko can't use it."))
        }
    }

    fun testReplacesTheExpressionAndPassesItFromEveryCall() {
        val plan = plan("MAX + 1").getOrThrow()
        assertEquals("max", plan.name)
        assertEquals(emptySet<String>(), plan.privateNames)
        InkoIntroduceParameter.processor(project, plan, "ceiling", "Int", replaceAll = true).run()
        assertTrue(file("src/app/util.inko").text.contains("fn pub clamp(value: Int, ceiling: Int) -> Int {\n  if value > LIMIT * 2 { LIMIT * 2 } else { value.min(ceiling) }\n}"))
        // Callers in other modules import what the expression names.
        assertEquals(
            "import app.util (clamp, MAX)\n\nfn run -> Int {\n  clamp(5, MAX + 1) + clamp(value: 7, ceiling: MAX + 1)\n}\n",
            file("src/main.inko").text,
        )
    }

    fun testReplacesEveryOccurrence() {
        val plan = plan("LIMIT * 2").getOrThrow()
        assertEquals("limit", plan.name)
        assertEquals(2, plan.occurrences.size)
        // Only the declaring module calls it here, so the private constant is fine.
        assertEquals(listOf("app.util" to "LIMIT"), plan.imports)
    }

    fun testReplacesOnlyTheSelectedOccurrenceWhenAsked() {
        val plan = plan("LIMIT * 2", nth = 1).getOrThrow()
        // As if Continue were clicked past the private-constant conflict.
        com.intellij.refactoring.BaseRefactoringProcessor.ConflictsInTestsException.withIgnoredConflicts<RuntimeException> {
            InkoIntroduceParameter.processor(project, plan, "limit", "Int", replaceAll = false).run()
        }
        assertTrue(file("src/app/util.inko").text.contains("  if value > LIMIT * 2 { limit } else { value.min(MAX + 1) }"))
    }

    fun testRefusesExpressionsCallersCantEvaluate() {
        assertTrue(plan("value > LIMIT").exceptionOrNull()!!.message!!.contains("`value`"))
        assertTrue(plan("@size", nth = 1).exceptionOrNull()!!.message!!.contains("@size"))
        val selfMessage = plan("self.extra").exceptionOrNull()?.message
        assertTrue(selfMessage.toString(), selfMessage!!.contains("`self`"))
    }

    fun testWorksOutTypesOfObviousExpressions() {
        fun type(text: String) = InkoIntroduceParameter.inferType(InkoTokenStream(text).tokens)
        assertEquals("String", type("'x'"))
        assertEquals("Int", type("42"))
        assertEquals("Int", type("-3"))
        assertEquals("Float", type("1.5"))
        assertEquals("Bool", type("true"))
        assertEquals("Box", type("Box(size: 1)"))
        assertEquals("Box", type("Box.new(1, 2)"))
        assertEquals("Box", type("Box.new"))
        assertNull(type("Box(size: 1).grow(1)"))
        assertNull(type("LIMIT * 2"))
    }

    fun testTheDialogBuilds() {
        val dialog = InkoIntroduceParameterDialog(project, plan("LIMIT * 2").getOrThrow())
        dialog.close(0)
    }

    fun testOffersAnExpressionWithoutLocals() {
        val plan = plan("'x'.size").getOrThrow()
        assertEquals("size", plan.name)
        assertNull(plan.type)
    }
}
