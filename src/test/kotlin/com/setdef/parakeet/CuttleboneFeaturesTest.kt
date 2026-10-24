package com.setdef.parakeet

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import java.nio.file.Path

/**
 * Parakeet's features with Cuttlebone's answers (from a fake), and without
 * them. `x` comes from a function, so Parakeet can't tell its type: it guesses
 * both `size` methods, and the compiler knows it's `B`'s.
 */
class CuttleboneFeaturesTest : CuttleboneTestCase() {
    private val util = """
        |type pub A {
        |  fn pub size(unit: Int) -> Int {
        |    unit
        |  }
        |}
        |
        |type pub B {
        |  fn pub size(scale: Float) -> Int {
        |    1
        |  }
        |}
        |""".trimMargin()

    private val main = """
        |import app.util (A, B)
        |
        |fn make -> B {
        |  B()
        |}
        |
        |fn pub run -> Int {
        |  let x = make
        |  x.size(2.0)
        |}
        |""".trimMargin()

    override fun docModules(mirror: Path): List<String> {
        val file = mirror.resolve("src/app/util.inko")
        return listOf(FakeInkoDoc.module(
            "app.util", file,
            types = listOf(
                FakeInkoDoc.type("A", 1, instanceMethods = listOf(FakeInkoDoc.member("size", file, 2, signature = "fn pub size(unit: Int) -> Int"))),
                FakeInkoDoc.type("B", 7, instanceMethods = listOf(FakeInkoDoc.member("size", file, 8, signature = "fn pub size(scale: Float) -> Int"))),
            ),
        ))
    }

    private val bSize get() = locationAt("src/app/util.inko", util, "size(scale")

    private fun definitionOfSize() = answerAt(main, "size(2.0)", "textDocument/definition", listOf(bSize))

    private fun start(vararg answers: FakeCuttlebone.Answer) {
        myFixture.addFileToProject("src/app/util.inko", util)
        useFake(FakeCuttlebone.Script(log = "", answers = answers.toList()))
        open("src/main.inko", main)
        runningServer()
    }

    private fun nameAt(marker: String): InkoName =
        PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(main.indexOf(marker)), InkoName::class.java, false)!!

    private fun PsiElement.location(): String {
        val line = containingFile.viewProvider.document!!.getLineNumber(textOffset) + 1
        return "${containingFile.name}:$line:$text"
    }

    private fun targets(marker: String): List<String> =
        inBackground { (nameAt(marker).reference as InkoReference).multiResolve(false).map { it.element!!.location() } }

    fun testResolvesToTheMethodTheCompilerCalls() {
        start(definitionOfSize())
        assertEquals(listOf("util.inko:8:size"), targets("size(2.0)"))
    }

    fun testWithoutAFreshAnswerItGuessesAsBefore() {
        start(definitionOfSize().copy(status = "unavailable", reason = "noHir"))
        assertEquals(listOf("util.inko:2:size", "util.inko:8:size"), targets("size(2.0)").sorted())
    }

    fun testOnTheEdtResolveOnlyUsesAnswersAlreadyCached() {
        start(definitionOfSize())
        val reference = nameAt("size(2.0)").reference as InkoReference
        assertEquals("not asked yet: the heuristic", 2, reference.multiResolve(false).size)
        targets("size(2.0)")
        assertEquals("cached", 1, reference.multiResolve(false).size)
    }

    /** Cuttlebone's answer for the whole file's hints: `scale:` before `2.0`, and `x`'s type. */
    private fun inlayHints(status: String = "fresh") = FakeCuttlebone.Answer("textDocument/inlayHint", 0, 0, listOf(
        hint(main.indexOf("2.0"), "scale:", kind = 2),
        hint(main.indexOf("x = make") + 1, ": B", kind = 1),
    ), status)

    private fun hint(offset: Int, label: String, kind: Int): Map<String, Any> {
        val line = main.substring(0, offset).count { it == '\n' }
        return mapOf("position" to mapOf("line" to line, "character" to offset - (main.lastIndexOf('\n', offset - 1) + 1)), "label" to label, "kind" to kind)
    }

    fun testHintsComeFromOneRequestForTheWholeFile() {
        start(inlayHints(), definitionOfSize())
        repeat(2) {
            assertEquals(listOf(main.indexOf("2.0") to "scale"), inBackground { InkoParameterHints.inFile(myFixture.file) })
            assertEquals(listOf(main.indexOf("x = make") + 1 to ": B"), inBackground { InkoParameterHints.types(myFixture.file) })
        }
        assertEquals("one request for every hint, cached", 1, received("textDocument/inlayHint").size)
        assertEquals("nothing asked per call", 0, received("textDocument/definition").size)
    }

    fun testWithoutAnAnswerParameterHintsAreParakeetsAndNothingIsAskedPerCall() {
        start(inlayHints(status = "unavailable"), definitionOfSize())
        assertEquals("two parameter lists fit: no hint", emptyList<Any>(), inBackground { InkoParameterHints.inFile(myFixture.file) })
        assertEquals("only Cuttlebone knows types", emptyList<Any>(), inBackground { InkoParameterHints.types(myFixture.file) })
        assertEquals(0, received("textDocument/definition").size)
    }

    fun testParameterInfoUsesTheResolvedMethod() {
        start(definitionOfSize())
        targets("size(2.0)")
        val candidates = InkoResolver.resolveDefinitions(nameAt("size(2.0)")).map { it.first.signature }
        assertEquals("parameter info's candidates", listOf("fn pub size(scale: Float) -> Int"), candidates)
    }

    fun testCompletionListsTheCompilersMembers() {
        val text = main.replace("x.size(2.0)", "x.")
        myFixture.addFileToProject("src/app/util.inko", util)
        useFake(FakeCuttlebone.Script(log = "", answers = listOf(answerAt(text, "x.\n", "textDocument/completion", mapOf(
            "isIncomplete" to false,
            "items" to listOf(
                mapOf("label" to "size", "kind" to 2, "detail" to "fn pub size(scale: Float) -> Int", "labelDetails" to mapOf("description" to "app.util")),
                mapOf("label" to "label", "kind" to 5, "detail" to "let pub @label: String", "labelDetails" to mapOf("description" to "app.util")),
            ),
        ), shift = 2))))
        open("src/main.inko", text)
        runningServer()
        val offset = text.indexOf("x.\n") + 2
        myFixture.editor.caretModel.moveToOffset(offset)
        // Completion asks in the background in the IDE; here the answer is fetched first.
        inBackground { CuttleboneQueries.getInstance(project).completion(myFixture.file, offset) }
        myFixture.completeBasic()
        assertEquals(listOf("label", "size"), myFixture.lookupElementStrings!!.sorted())
    }

    fun testCompletionWithoutAnAnswerGuessesAsBefore() {
        val text = main.replace("x.size(2.0)", "x.")
        myFixture.addFileToProject("src/app/util.inko", util)
        useFake()
        open("src/main.inko", text)
        runningServer()
        val offset = text.indexOf("x.\n") + 2
        myFixture.editor.caretModel.moveToOffset(offset)
        inBackground { CuttleboneQueries.getInstance(project).completion(myFixture.file, offset) }
        myFixture.completeBasic()
        // Every type's size (shown once), as before: the receiver's type isn't known, and `label` is only Cuttlebone's.
        assertEquals(listOf("size"), myFixture.lookupElementStrings)
    }

    fun testDocumentationShowsTheTypeOfThisUse() {
        start(definitionOfSize(), answerAt(main, "size(2.0)", "textDocument/hover", mapOf(
            "contents" to mapOf("kind" to "markdown", "value" to "```inko\nInt\n```"),
        )))
        val name = nameAt("size(2.0)")
        val html = inBackground {
            val target = (name.reference as InkoReference).multiResolve(false).single().element!!
            (InkoDocumentationTargetProvider().documentationTarget(target, name.firstChild) as InkoDocumentationTarget).html()
        }
        assertTrue(html, html.contains("<code>fn pub size(scale: Float) -&gt; Int</code>"))
        assertTrue(html, html.contains("Type here") && html.contains("<code>Int</code>"))
    }

    fun testExpressionTypeIsTheCompilersForExactlyThatExpression() {
        val call = main.indexOf("x.size(2.0)")
        val callRange = mapOf(
            "start" to mapOf("line" to 8, "character" to 2),
            "end" to mapOf("line" to 8, "character" to 2 + "x.size(2.0)".length),
        )
        start(
            // At `x`, hover gives `x` alone: not the call, so it isn't the call's type.
            answerAt(main, "x.size(2.0)", "textDocument/hover", mapOf(
                "contents" to mapOf("kind" to "markdown", "value" to "```inko\nB\n```"),
                "range" to mapOf("start" to mapOf("line" to 8, "character" to 2), "end" to mapOf("line" to 8, "character" to 3)),
            )),
            answerAt(main, "x.size(2.0)", "textDocument/hover", mapOf(
                "contents" to mapOf("kind" to "markdown", "value" to "```inko\nInt\n```"),
                "range" to callRange,
            ), shift = "x.size(2.0".length),
        )
        val provider = InkoExpressionTypeProvider()
        val types = inBackground {
            provider.getExpressionsAt(myFixture.file.findElementAt(call + 2)!!).associate { it.text to provider.getInformationHint(it) }
        }
        assertEquals("Int", types["x.size(2.0)"])
    }
}
