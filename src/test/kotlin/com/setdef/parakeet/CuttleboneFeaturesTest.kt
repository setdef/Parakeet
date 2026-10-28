package com.setdef.parakeet

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

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
        assertEquals("not asked yet: nothing, rather than a guess", 0, reference.multiResolve(false).size)
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

    fun testInCodeBeingEditedThereAreNoHintsAndNothingIsAskedPerCall() {
        start(inlayHints(status = "unavailable").copy(reason = "noHir"), definitionOfSize())
        assertEquals("no guessed names", emptyList<Any>(), inBackground { InkoParameterHints.inFile(myFixture.file) })
        assertEquals("only Cuttlebone knows types", emptyList<Any>(), inBackground { InkoParameterHints.types(myFixture.file) })
        assertEquals(0, received("textDocument/definition").size)
    }

    /** A stand-in for the IDE's contexts: getters answer from [values], setters record into it. */
    private inline fun <reified T> context(values: MutableMap<String, Any?>): T =
        java.lang.reflect.Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            when {
                method.name == "setupUIComponentPresentation" -> { values["shown"] = Triple(args!![0], args[1], args[2]); args[0] }
                method.name.startsWith("set") && args?.size == 1 -> { values[method.name.removePrefix("set").replaceFirstChar(Char::lowercase)] = args[0]; null }
                method.name.startsWith("get") -> values[method.name.removePrefix("get").replaceFirstChar(Char::lowercase)]
                method.name.startsWith("is") -> values[method.name.removePrefix("is").replaceFirstChar(Char::lowercase)] ?: false
                else -> values[method.name]
            }
        } as T

    /** Parameter info at [offset], as IntelliJ runs it (in the background): each item, with the current parameter in [brackets]. */
    private fun parameterInfo(offset: Int): List<String> = inBackground {
        val handler = InkoParameterInfoHandler()
        val created = mutableMapOf<String, Any?>("file" to myFixture.file, "editor" to myFixture.editor, "offset" to offset, "project" to project)
        val owner = handler.findElementForParameterInfo(context(created)) ?: return@inBackground emptyList()
        val updated = mutableMapOf<String, Any?>("file" to myFixture.file, "editor" to myFixture.editor, "offset" to offset, "parameterOwner" to owner, "objectsToView" to created["itemsToShow"])
        handler.updateParameterInfo(owner, context(updated))
        (created["itemsToShow"] as Array<*>).map { item ->
            val ui = mutableMapOf<String, Any?>("parameterOwner" to owner, "currentParameterIndex" to updated["currentParameter"], "defaultParameterColor" to java.awt.Color.WHITE)
            handler.updateUI(item!!, context(ui))
            val (shown, start, end) = ui["shown"] as Triple<*, *, *>
            val text = shown as String
            if ((start as Int) < 0) text else text.substring(0, start) + "[" + text.substring(start, end as Int) + "]" + text.substring(end)
        }
    }

    private fun signatureHelp(status: String = "fresh") = answerAt(main, "2.0", "textDocument/signatureHelp", mapOf(
        "signatures" to listOf(mapOf("label" to "fn pub size(scale: Float) -> Int", "parameters" to listOf(mapOf("label" to listOf(12, 24))), "activeParameter" to 0)),
        "activeSignature" to 0, "activeParameter" to 0,
    ), status = status)

    fun testParameterInfoShowsCuttlebonesSignature() {
        start(signatureHelp())
        assertEquals(listOf("fn pub size([scale: Float]) -> Int"), parameterInfo(main.indexOf("2.0")))
    }

    fun testInCodeBeingEditedParameterInfoIsParakeets() {
        start(signatureHelp(status = "unavailable").copy(reason = "noHir"))
        assertEquals("both size methods fit", listOf("[unit: Int]", "[scale: Float]"), parameterInfo(main.indexOf("2.0")).sorted().reversed())
    }

    fun testWithoutAnAnswerParameterInfoShowsNothing() {
        start(signatureHelp(status = "unavailable").copy(reason = "timeout"))
        assertEquals(emptyList<String>(), parameterInfo(main.indexOf("2.0")))
    }

    private val reassigned = "fn pub run {\n  let mut total = 0\n  total = 1\n  total + 2\n}\n"

    // A file's first question pays its whole check, which takes longer the busier the machine is (up to
    // 260 ms measured beside another test run, from 50 ms idle): the pass waits for it, as for inlay hints.
    // After that, Cuttlebone answers from the check it kept.
    fun testUsageHighlightingWaitsForAFilesFirstCheck() {
        useFake(FakeCuttlebone.Script(log = "", delayMs = 1000, answers = listOf(highlightsOfTotal())))
        open("src/main.inko", reassigned)
        runningServer()
        assertEquals("fn pub run {\n  let mut «=total» = 0\n  «=total» = 1\n  «total» + 2\n}\n", highlighted("total + 2"))
        // Its own question first: the caret's definition, asked in advance for the EDT, mustn't hold it up. That's
        // sent from a pooled thread, and isn't waited for, so on a busy machine it reaches the fake after the
        // highlights are in: wait for it before reading the order.
        fun asked() = messages().mapNotNull { it.get("method")?.asString }.filter { it == "textDocument/documentHighlight" || it == "textDocument/definition" }
        waitFor("the caret's definition to be asked") { "textDocument/definition" in asked() }
        assertEquals(listOf("textDocument/documentHighlight", "textDocument/definition"), asked())
    }

    /** Usage highlighting at [marker] in [reassigned]: «=write» and «read». */
    private fun highlighted(marker: String): String {
        val name = inBackground { PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(reassigned.indexOf(marker)), InkoName::class.java, false)!! }
        val handler = InkoHighlightUsagesHandlerFactory().createHighlightUsagesHandler(myFixture.editor, myFixture.file, name) as InkoHighlightUsagesHandler
        inBackground { handler.computeUsages(handler.targets) }
        val marks = handler.writeUsages.map { it to "=" } + handler.readUsages.map { it to "" }
        return marks.sortedByDescending { it.first.startOffset }.fold(reassigned) { result, (range, kind) ->
            result.substring(0, range.startOffset) + "«$kind" + result.substring(range.startOffset, range.endOffset) + "»" + result.substring(range.endOffset)
        }
    }

    private fun highlightsOfTotal(status: String = "fresh"): FakeCuttlebone.Answer {
        fun range(marker: String, kind: Int): Map<String, Any> {
            val offset = reassigned.indexOf(marker)
            val line = reassigned.substring(0, offset).count { it == '\n' }
            val character = offset - (reassigned.lastIndexOf('\n', offset - 1) + 1)
            return mapOf("range" to mapOf("start" to mapOf("line" to line, "character" to character), "end" to mapOf("line" to line, "character" to character + 5)), "kind" to kind)
        }
        return answerAt(reassigned, "total + 2", "textDocument/documentHighlight", listOf(range("total = 0", 3), range("total = 1", 3), range("total + 2", 2)), status = status)
    }

    fun testUsageHighlightingIsCuttlebonesWhereItHasAnAnswer() {
        useFake(FakeCuttlebone.Script(log = "", answers = listOf(highlightsOfTotal())))
        open("src/main.inko", reassigned)
        runningServer()
        // The compiler knows `total = 1` assigns it.
        assertEquals("fn pub run {\n  let mut «=total» = 0\n  «=total» = 1\n  «total» + 2\n}\n", highlighted("total + 2"))
    }

    fun testInCodeBeingEditedUsageHighlightingIsParakeets() {
        useFake(FakeCuttlebone.Script(log = "", answers = listOf(highlightsOfTotal(status = "unavailable").copy(reason = "declarationErrors"))))
        open("src/main.inko", reassigned)
        runningServer()
        assertEquals("fn pub run {\n  let mut «=total» = 0\n  «=total» = 1\n  «total» + 2\n}\n", highlighted("total + 2"))
    }

    /** Find Usages of the `size` declared at [marker] in util.inko, as offsets in main.inko. */
    private fun usagesOfSize(marker: String): List<Int> = inBackground {
        val util = myFixture.findFileInTempDir("src/app/util.inko")!!
        val file = com.intellij.psi.PsiManager.getInstance(project).findFile(util)!!
        val declaration = PsiTreeUtil.getParentOfType(file.findElementAt(file.text.indexOf(marker)), InkoName::class.java, false)!!
        com.intellij.psi.search.searches.ReferencesSearch.search(declaration, com.intellij.psi.search.GlobalSearchScope.projectScope(project)).findAll()
            .filter { it.element.containingFile.name == "main.inko" }.map { it.element.textRange.startOffset }
    }

    /** `references` at the declaration of the `size` at [marker] in util.inko: the declaration and [uses] in main.inko. */
    private fun referencesOfSize(marker: String, vararg uses: String, status: String = "fresh") = answerAt(util, marker, "textDocument/references",
        listOf(locationAt("src/app/util.inko", util, marker.substringBefore('('))) + uses.map { locationAt("src/main.inko", main, it.substringBefore('(')) }, status = status)

    fun testFindUsagesAsksForTheWholeProjectsReferencesOnce() {
        // A common name has an occurrence on nearly every line: asking about each, one check each, took minutes.
        start(referencesOfSize("size(scale", "size(2.0)"), referencesOfSize("size(unit"))
        assertEquals(listOf(main.indexOf("size(2.0)")), usagesOfSize("size(scale"))
        assertEquals(emptyList<Int>(), usagesOfSize("size(unit"))
        assertEquals(2, received("textDocument/references").size)
        assertEquals(0, received("textDocument/definition").size)
        // Kept until the code changes.
        assertEquals(listOf(main.indexOf("size(2.0)")), usagesOfSize("size(scale"))
        assertEquals(2, received("textDocument/references").size)
    }

    fun testWithoutReferencesEachOccurrenceIsResolved() {
        // Code that a declaration's error may affect mentions `size` somewhere in the project, but not in this file.
        start(referencesOfSize("size(scale", status = "unavailable").copy(reason = "declarationErrors"), definitionOfSize())
        assertEquals(listOf(main.indexOf("size(2.0)")), usagesOfSize("size(scale"))
        assertEquals(1, received("textDocument/definition").size)
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

    // Milestone 5's answer kinds: `fresh` with `"repaired": true` (from the documents with some lines blank)
    // is a fresh answer; `stale` (from the last version that type checked) is used only by features that show
    // something and change no code, marked as possibly outdated where there's somewhere to say so.

    fun testARepairedAnswerIsUsedAsAFreshOne() {
        start(definitionOfSize().copy(repaired = true))
        assertEquals(listOf("util.inko:8:size"), targets("size(2.0)"))
        val answer = inBackground { CuttleboneQueries.getInstance(project).definition(myFixture.file, main.indexOf("size(2.0)")) }!!
        assertTrue(answer.repaired)
        assertFalse(answer.stale)
    }

    fun testAFreshAnswerWithoutTheRepairedFieldIsNotRepaired() {
        // What Cuttlebone before milestone 5 sends.
        start(definitionOfSize())
        val answer = inBackground { CuttleboneQueries.getInstance(project).definition(myFixture.file, main.indexOf("size(2.0)")) }!!
        assertFalse(answer.repaired)
        assertFalse(answer.stale)
    }

    fun testWithoutStatusNotificationsNoAnswerIsUsedAndNothingIsGuessed() {
        // A server that sends no status can't say an answer is fresh, nor that the code is being edited.
        myFixture.addFileToProject("src/app/util.inko", util)
        useFake(FakeCuttlebone.Script(log = "", answers = listOf(definitionOfSize()), statusNotifications = false))
        open("src/main.inko", main)
        runningServer()
        assertEquals(emptyList<String>(), targets("size(2.0)"))
    }

    fun testAStatusParakeetDoesntKnowIsNoAnswer() {
        start(definitionOfSize().copy(status = "someday"), inlayHints(status = "someday"))
        assertEquals(emptyList<String>(), targets("size(2.0)"))
        assertEquals(emptyList<Any>(), inBackground { InkoParameterHints.types(myFixture.file) })
    }

    fun testAStaleDefinitionIsOnlyUsedWhereNothingChangesCode() {
        start(definitionOfSize().copy(status = "stale"))
        // Navigation takes it, as an answer that isn't exact: Rename at the caret and the refactorings don't.
        assertEquals(listOf("util.inko:8:size"), targets("size(2.0)"))
        assertFalse(inBackground { (nameAt("size(2.0)").reference as InkoReference).multiResolve(false).single().isValidResult })
        assertEquals(emptyList<Any>(), inBackground { InkoResolver.resolveExactly(nameAt("size(2.0)")) })
        // To what takes only fresh answers, the code can't be checked as it is: it's being edited.
        val answer = inBackground { CuttleboneQueries.getInstance(project).definition(myFixture.file, main.indexOf("size(2.0)")) }!!
        assertNull(answer.value)
        assertTrue(answer.editing)
    }

    fun testStaleHintsAreShownAndMarkedPossiblyOutdated() {
        start(inlayHints(status = "stale"))
        assertEquals(listOf(main.indexOf("2.0") to "scale"), inBackground { InkoParameterHints.inFile(myFixture.file) })
        assertEquals(listOf(main.indexOf("x = make") + 1 to ": B"), inBackground { InkoParameterHints.types(myFixture.file) })
        assertEquals(listOf(CuttleboneQueries.OUTDATED, CuttleboneQueries.OUTDATED), inBackground { tooltips() })
    }

    fun testFreshHintsHaveNoTooltip() {
        start(inlayHints())
        assertEquals(listOf<String?>(null, null), inBackground { tooltips() })
    }

    /** The tooltips of the hints both providers show in main.inko. */
    private fun tooltips(): List<String?> {
        val tooltips = mutableListOf<String?>()
        val sink = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(com.intellij.codeInsight.hints.declarative.InlayTreeSink::class.java)) { _, method, args ->
            if (method.name == "addPresentation") tooltips += args!![2] as String?
            null
        } as com.intellij.codeInsight.hints.declarative.InlayTreeSink
        for (provider in listOf(InkoParameterHintsProvider(), InkoTypeHintsProvider())) {
            (provider.createCollector(myFixture.file, myFixture.editor) as com.intellij.codeInsight.hints.declarative.OwnBypassCollector).collectHintsForFile(myFixture.file, sink)
        }
        return tooltips
    }

    fun testStaleParameterInfoIsShownAndMarkedPossiblyOutdated() {
        start(signatureHelp(status = "stale"))
        assertEquals(listOf("fn pub size([scale: Float]) -> Int  (${CuttleboneQueries.OUTDATED})"), parameterInfo(main.indexOf("2.0")))
    }

    fun testStaleUsageHighlightsAreShown() {
        useFake(FakeCuttlebone.Script(log = "", answers = listOf(highlightsOfTotal(status = "stale"))))
        open("src/main.inko", reassigned)
        runningServer()
        assertEquals("fn pub run {\n  let mut «=total» = 0\n  «=total» = 1\n  «total» + 2\n}\n", highlighted("total + 2"))
    }

    fun testAStaleTypeInDocumentationIsMarkedPossiblyOutdated() {
        start(definitionOfSize(), answerAt(main, "size(2.0)", "textDocument/hover", mapOf(
            "contents" to mapOf("kind" to "markdown", "value" to "```inko\nInt\n```"),
        ), status = "stale"))
        val name = nameAt("size(2.0)")
        val html = inBackground {
            val target = (name.reference as InkoReference).multiResolve(false).single().element!!
            (InkoDocumentationTargetProvider().documentationTarget(target, name.firstChild) as InkoDocumentationTarget).html()
        }
        assertTrue(html, html.contains("Type here") && html.contains("<code>Int</code> (${CuttleboneQueries.OUTDATED})"))
    }

    fun testAStaleExpressionTypeIsMarkedPossiblyOutdated() {
        val call = main.indexOf("x.size(2.0)")
        start(answerAt(main, "x.size(2.0)", "textDocument/hover", mapOf(
            "contents" to mapOf("kind" to "markdown", "value" to "```inko\nInt\n```"),
            "range" to mapOf("start" to mapOf("line" to 8, "character" to 2), "end" to mapOf("line" to 8, "character" to 2 + "x.size(2.0)".length)),
        ), status = "stale"))
        val provider = InkoExpressionTypeProvider()
        val types = inBackground {
            provider.getExpressionsAt(myFixture.file.findElementAt(call + 2)!!).associate { it.text to provider.getInformationHint(it) }
        }
        assertEquals("Int (${CuttleboneQueries.OUTDATED})", types["x.size(2.0)"])
    }

    fun testStaleCompletionIsNotUsedSinceItInsertsCode() {
        // Cuttlebone never sends one; if it did, it would be from text that has changed since.
        val text = main.replace("x.size(2.0)", "x.")
        myFixture.addFileToProject("src/app/util.inko", util)
        useFake(FakeCuttlebone.Script(log = "", answers = listOf(answerAt(text, "x.\n", "textDocument/completion", mapOf(
            "isIncomplete" to false,
            "items" to listOf(mapOf("label" to "label", "kind" to 5, "detail" to "let pub @label: String")),
        ), shift = 2, status = "stale"))))
        open("src/main.inko", text)
        runningServer()
        val offset = text.indexOf("x.\n") + 2
        myFixture.editor.caretModel.moveToOffset(offset)
        val answer = inBackground { CuttleboneQueries.getInstance(project).completion(myFixture.file, offset) }!!
        assertNull(answer.value)
        assertTrue("only a stale answer: the code can't be checked as it is", answer.editing)
        myFixture.completeBasic()
        assertEquals(listOf("size"), myFixture.lookupElementStrings)
    }

    fun testARepairedCompletionIsUsed() {
        val text = main.replace("x.size(2.0)", "x.")
        myFixture.addFileToProject("src/app/util.inko", util)
        useFake(FakeCuttlebone.Script(log = "", answers = listOf(answerAt(text, "x.\n", "textDocument/completion", mapOf(
            "isIncomplete" to false,
            "items" to listOf(mapOf("label" to "label", "kind" to 5, "detail" to "let pub @label: String")),
        ), shift = 2).copy(repaired = true))))
        open("src/main.inko", text)
        runningServer()
        val offset = text.indexOf("x.\n") + 2
        myFixture.editor.caretModel.moveToOffset(offset)
        assertTrue(inBackground { CuttleboneQueries.getInstance(project).completion(myFixture.file, offset) }!!.repaired)
        myFixture.completeBasic()
        assertEquals(listOf("label"), myFixture.lookupElementStrings)
    }
}
