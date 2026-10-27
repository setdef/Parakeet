package com.setdef.parakeet

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PlatformTestUtil
import java.util.concurrent.TimeUnit

/**
 * Parakeet's features against the real `cuttlebone` (the CUTTLEBONE
 * environment variable, else the setting's default: PATH) and the real
 * `inko`. scripts/check.sh skips these when there's no cuttlebone, except
 * with --full, which requires it.
 */
class CuttleboneIntegrationTest : InkoTestCase(onDisk = true, realInko = true) {
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
        |
        |type pub C {
        |  fn pub static new -> C {
        |    C()
        |  }
        |
        |  fn pub size -> Int {
        |    1
        |  }
        |
        |  fn pub mut reset -> Int {
        |    0
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
        |  let y = [10, 20]
        |  y.get(0).or(0)
        |  x.size(2.0)
        |}
        |""".trimMargin()

    override fun setUp() {
        super.setUp()
        val executable = System.getenv("CUTTLEBONE")?.takeIf { it.isNotEmpty() }
            ?: run { InkoProjectSettings.getInstance(project).cuttlebone = ""; Cuttlebone.executable(project)?.toString() }
        assertNotNull("cuttlebone must be installed (or CUTTLEBONE set) to run these tests", executable)
        println("Testing against cuttlebone at $executable")
        putTempDirInProjectContent()
        useCuttlebone(executable!!)
        myFixture.addFileToProject("src/app/util.inko", util)
    }

    private fun open(text: String = main) {
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("src/main.inko", text).virtualFile)
        PlatformTestUtil.waitWithEventsDispatching({ "Cuttlebone didn't start: ${CuttleboneService.getInstance(project).status?.message}" }, {
            // IntelliJ has sent the editor's text (didOpen): its own features (Go to Type Declaration) need that.
            val server = CuttleboneService.getInstance(project).server()
            server != null && CuttleboneService.getInstance(project).sentVersions[server.getDocumentIdentifier(myFixture.file.virtualFile).uri] ==
                server.getDocumentVersion(myFixture.editor.document)
        }, TimingLimits.seconds(60))
    }

    private fun <T> inBackground(action: () -> T): T {
        val future = ApplicationManager.getApplication().executeOnPooledThread<T> { ReadAction.compute<T, Throwable> { action() } }
        PlatformTestUtil.waitWithEventsDispatching("background action", { future.isDone }, TimingLimits.seconds(60))
        return future.get(1, TimeUnit.SECONDS)
    }

    private fun nameAt(text: String, marker: String, shift: Int = 0): InkoName =
        PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(text.indexOf(marker) + shift), InkoName::class.java, false)!!

    private fun PsiElement.location(): String {
        val line = containingFile.viewProvider.document!!.getLineNumber(textOffset) + 1
        return "${containingFile.name}:$line:$text"
    }

    private fun targets(text: String, marker: String): List<String> =
        inBackground { (nameAt(text, marker).reference as InkoReference).multiResolve(false).map { it.element!!.location() } }

    /**
     * The editor's folds once IntelliJ has Cuttlebone's answer ([until] holds), as first and last lines, with their
     * placeholders. IntelliJ asks for folding ranges as it highlights, and folds from the answer it keeps.
     */
    private fun folds(until: (List<com.intellij.openapi.editor.FoldRegion>) -> Boolean): List<Triple<Int, Int, String>> {
        val editor = myFixture.editor
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(TimingLimits.seconds(30).toLong())
        while (true) {
            myFixture.doHighlighting()
            com.intellij.codeInsight.folding.CodeFoldingManager.getInstance(project).updateFoldRegions(editor)
            if (until(editor.foldingModel.allFoldRegions.toList())) break
            assertTrue("no folds from Cuttlebone: ${editor.foldingModel.allFoldRegions.toList()}", System.nanoTime() < deadline)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(200)
        }
        val document = editor.document
        return editor.foldingModel.allFoldRegions.map { Triple(document.getLineNumber(it.startOffset), document.getLineNumber(it.endOffset), it.placeholderText) }
            .sortedBy { it.first }
    }

    // Folding is Cuttlebone's (textDocument/foldingRange, through IntelliJ's LSP support): blocks, comment runs
    // and imports, from the tokens, so it answers in code that doesn't parse, and a call's arguments over several
    // lines fold too.
    fun testFoldingIsCuttlebones() {
        val text = "# One.\n# Two.\nimport app.util (A)\nimport app.util (B)\n\nfn pub run -> Int {\n  add(\n    1,\n    2,\n  )\n}\n\n" +
            "fn add(a: Int, b: Int) -> Int {\n  a + b\n}\n\nfn broken( {\n"
        open(text)
        // A fold starts at the end of its first line: here, the call's `(`.
        val arguments = text.indexOf("(\n    1") + 1
        val folds = folds { regions -> regions.any { it.startOffset == arguments } }
        assertEquals("comments, imports, run's body, add's arguments, add's body", listOf(0 to 1, 2 to 3, 5 to 9, 6 to 8, 12 to 13), folds.map { it.first to it.second })
    }

    // Reformat Code is Cuttlebone's (textDocument/formatting, through IntelliJ's LSP support): exactly `inko fmt`,
    // from the upstream formatter, on the unsaved text, without running `inko`.
    fun testReformatIsCuttlebones() {
        open("fn pub run -> Int {\n      1 +   2\n}\n")
        assertEquals("IntelliJ's LSP formatting", "LspFormattingService", com.intellij.formatting.service.FormattingServiceUtil.findService(myFixture.file, true, true)?.javaClass?.simpleName)
        myFixture.performEditorAction(com.intellij.openapi.actionSystem.IdeActions.ACTION_EDITOR_REFORMAT)
        PlatformTestUtil.waitWithEventsDispatching({ "not formatted: ${myFixture.editor.document.text}" }, { myFixture.editor.document.text == "fn pub run -> Int {\n  1 + 2\n}\n" }, TimingLimits.seconds(30))
    }

    /** What IntelliJ's LSP Go to Class or Go to Symbol ([classes]) lists for [pattern]: each item's name and location. */
    private fun goTo(classes: Boolean, pattern: String, libraries: Boolean): List<String> = inBackground {
        // Under a progress indicator, as Go to Class and Search Everywhere ask.
        com.intellij.openapi.progress.ProgressManager.getInstance().runProcess<List<String>>({ goToNow(classes, pattern, libraries) }, com.intellij.openapi.progress.EmptyProgressIndicator())
    }

    private fun goToNow(classes: Boolean, pattern: String, libraries: Boolean): List<String> {
        val ep = if (classes) com.intellij.navigation.ChooseByNameContributor.CLASS_EP_NAME else com.intellij.navigation.ChooseByNameContributor.SYMBOL_EP_NAME
        val contributor = ep.extensionList.single { it.javaClass.simpleName.startsWith("LspGoTo") } as com.intellij.navigation.ChooseByNameContributorEx2
        val parameters = com.intellij.util.indexing.FindSymbolParameters.wrap(pattern, project, libraries)
        val names = mutableSetOf<String>()
        contributor.processNames({ names += it; true }, parameters)
        return names.filter { it.startsWith(pattern) }.flatMap { name ->
            mutableListOf<com.intellij.navigation.NavigationItem>().also { items -> contributor.processElementsWithName(name, { items += it; true }, parameters) }
        }.map { "${it.name} ${it.presentation?.locationString.orEmpty()}".trim() }.sorted()
    }

    // Go to Class and Go to Symbol are Cuttlebone's (workspace/symbol, through IntelliJ's LSP support): the
    // project's declarations, and with libraries, its dependencies' and the standard library's.
    fun testGoToClassAndSymbolAreCuttlebones() {
        open()
        assertTrue(goTo(classes = true, "B", libraries = false).toString(), goTo(classes = true, "B", libraries = false).any { it.startsWith("B") })
        assertEquals("A, B and C have one each", 3, goTo(classes = false, "size", libraries = false).count { it.startsWith("size") })
        assertTrue("std's Stdout", goTo(classes = true, "Stdout", libraries = true).any { it.startsWith("Stdout") })
    }

    fun testResolvesAMemberCallToTheMethodTheCompilerCalls() {
        open()
        assertEquals(listOf("util.inko:8:size"), targets(main, "size(2.0)"))
    }

    fun testUnsavedEditsAreSeen() {
        open()
        val document = myFixture.editor.document
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            val start = main.indexOf("make\n  let y")
            document.replaceString(start, start + "make".length, "A()")
        }
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertTrue("not saved", com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().isDocumentUnsaved(document))
        assertEquals(listOf("util.inko:2:size"), targets(document.text, "size(2.0)"))
    }

    // `y.get(0).or(0)`: Array's `get(index)`, and Option's `or(default)`, which Parakeet can tell too.
    private val index get() = main.indexOf("0).or")
    private val default get() = main.indexOf("0)\n")

    fun testHintsForTheWholeFile() {
        open()
        val parameters = inBackground { InkoParameterHints.inFile(myFixture.file) }
        val types = inBackground { InkoParameterHints.types(myFixture.file) }
        assertEquals(listOf(index to "index", default to "default", main.indexOf("2.0") to "scale"), parameters)
        assertEquals(listOf(main.indexOf("x = make") + 1 to ": B", main.indexOf("y = [") + 1 to ": Array[Int]"), types)
    }

    fun testParameterInfoHasTheCompilersSignature() {
        // Being typed: no `)` yet, which Cuttlebone allows for.
        val text = main.replace("x.size(2.0)", "x.size(")
        open(text)
        val help = inBackground { CuttleboneQueries.getInstance(project).signatureHelp(myFixture.file, text.indexOf("x.size(") + 7) }
        val signature = help!!.value!!.signatures.single()
        assertEquals("fn pub size(scale: Float) -> Int", signature.label)
        assertEquals(0, signature.activeParameter ?: help.value!!.activeParameter)
    }

    fun testUsageHighlightingKnowsAssignments() {
        val text = main + "\nfn pub count -> Int {\n  let mut total = 0\n  total = 1\n  total + 2\n}\n"
        open(text)
        val name = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(text.indexOf("total + 2")), InkoName::class.java, false)!!
        val handler = InkoHighlightUsagesHandlerFactory().createHighlightUsagesHandler(myFixture.editor, myFixture.file, name) as InkoHighlightUsagesHandler
        inBackground { handler.computeUsages(handler.targets) }
        val writes = handler.writeUsages.map { it.startOffset }.sorted()
        assertEquals(listOf(text.indexOf("total = 0"), text.indexOf("total = 1")), writes)
        assertEquals(listOf(text.indexOf("total + 2")), handler.readUsages.map { it.startOffset })
    }

    private val twiceAndUses = "\nfn twice(value: Int) -> Int {\n  value * 2\n}\n\nfn uses -> Int {\n  twice(21)\n}\n"

    fun testWithASyntaxErrorHintsAreCuttlebones() {
        // Since milestone 5, Cuttlebone answers a file that doesn't parse from a repaired copy (the broken line blank).
        val text = main + twiceAndUses + "\nfn broken( {\n"
        open(text)
        val answer = inBackground { CuttleboneQueries.getInstance(project).inlayHints(myFixture.file) }!!
        assertTrue(answer.repaired)
        assertNull("a repaired answer is fresh, so not marked", inBackground { InkoParameterHints.tooltip(myFixture.file) })
        assertEquals(
            listOf(index to "index", default to "default", text.indexOf("2.0") to "scale", text.indexOf("21") to "value"),
            inBackground { InkoParameterHints.inFile(myFixture.file) },
        )
        assertEquals(listOf(text.indexOf("x = make") + 1 to ": B", text.indexOf("y = [") + 1 to ": Array[Int]"), inBackground { InkoParameterHints.types(myFixture.file) })
    }

    fun testOpenedWithADeclarationErrorHintsAreCuttlebonesWhereTheErrorCantMatter() {
        // Since milestone 8, Cuttlebone types every method body past a declaration error, and answers (fresh) in
        // the units the error can't affect: not `broken`, whose signature has it, nor `calls_broken`, which names
        // it, so neither `twice(3)` nor `v` has a hint. Hints only inform, so none are guessed there.
        val text = main + twiceAndUses + "\nfn broken -> Missing {\n  1\n}\n" + "\nfn calls_broken {\n  let v = broken\n  twice(3)\n}\n"
        open(text)
        val answer = inBackground { CuttleboneQueries.getInstance(project).inlayHints(myFixture.file) }!!
        assertTrue("fresh: ${answer.status}", answer.status!!.fresh)
        assertFalse(answer.repaired)
        assertEquals(
            listOf(index to "index", default to "default", text.indexOf("2.0") to "scale", text.indexOf("21") to "value"),
            inBackground { InkoParameterHints.inFile(myFixture.file) },
        )
        assertEquals(listOf(text.indexOf("x = make") + 1 to ": B", text.indexOf("y = [") + 1 to ": Array[Int]"), inBackground { InkoParameterHints.types(myFixture.file) })
    }

    fun testOpenedWithAnImportOfAMissingModuleThereAreNoHints() {
        // A file importing a module that doesn't exist, and no check of it good yet, so nothing stale answers.
        // The import is blank in a repaired copy, which answers wherever the import can't matter:
        // not in `uses_nope`, which uses it. Hints only inform, so none are guessed where there's no answer.
        val usesNope = "\nfn uses_nope -> Int {\n  let v = nope.value\n  twice(4)\n}\n"
        val text = "import app.nope\n" + main + twiceAndUses + usesNope
        open(text)
        val answer = inBackground { CuttleboneQueries.getInstance(project).inlayHints(myFixture.file) }!!
        val parameters = inBackground { InkoParameterHints.inFile(myFixture.file) }
        val types = inBackground { InkoParameterHints.types(myFixture.file) }
        assertTrue("fresh, from a repaired copy: ${answer.status}", answer.status!!.fresh && answer.repaired)
        assertNull("a repaired answer is fresh, so not marked", inBackground { InkoParameterHints.tooltip(myFixture.file) })
        val shift = "import app.nope\n".length
        assertEquals(
            "Cuttlebone's, and none in uses_nope",
            listOf(index + shift to "index", default + shift to "default", text.indexOf("2.0") to "scale", text.indexOf("21") to "value"),
            parameters,
        )
        assertEquals(listOf(text.indexOf("x = make") + 1 to ": B", text.indexOf("y = [") + 1 to ": Array[Int]"), types)
    }

    fun testCompletionListsTheCompilersMembers() {
        val text = main.replace("x.size(2.0)", "x.")
        open(text)
        val offset = text.indexOf("x.\n") + 2
        myFixture.editor.caretModel.moveToOffset(offset)
        val answer = inBackground { CuttleboneQueries.getInstance(project).completion(myFixture.file, offset) }
        assertEquals(listOf("size" to "fn pub size(scale: Float) -> Int"), answer!!.value!!.map { it.label to it.detail })
        myFixture.completeBasic()
        assertEquals(listOf("size"), myFixture.lookupElementStrings)
    }

    fun testWithASyntaxErrorElsewhereCompletionIsCuttlebones() {
        // Since milestone 5, Cuttlebone completes from a repaired copy: the broken line elsewhere is blank.
        val text = main.replace("x.size(2.0)", "x.") + "\nfn broken( {\n"
        open(text)
        val offset = text.indexOf("x.\n") + 2
        myFixture.editor.caretModel.moveToOffset(offset)
        val answer = inBackground { CuttleboneQueries.getInstance(project).completion(myFixture.file, offset) }
        assertEquals(listOf("size" to "fn pub size(scale: Float) -> Int"), answer!!.value!!.map { it.label to it.detail })
        assertTrue(answer.repaired)
        myFixture.completeBasic()
        assertEquals(listOf("size"), myFixture.lookupElementStrings)
    }

    // Completion after a syntax error in the same method is what Cuttlebone still can't answer
    // (noHir): Parakeet's own member completion still offers something.
    fun testAfterASyntaxErrorInTheSameMethodCompletionFallsBackToParakeet() {
        val text = main.replace("let y = [10, 20]", "let y = [10, 20").replace("x.size(2.0)", "x.")
        open(text)
        val offset = text.indexOf("x.\n") + 2
        myFixture.editor.caretModel.moveToOffset(offset)
        assertTrue("code being edited", inBackground { CuttleboneQueries.getInstance(project).completion(myFixture.file, offset) }!!.editing)
        myFixture.completeBasic()
        assertTrue(myFixture.lookupElementStrings.toString(), myFixture.lookupElementStrings!!.contains("size"))
    }

    /**
     * Opens [main], has Cuttlebone check it (its last good check), then adds a module that doesn't parse
     * (saved, not open: Cuttlebone has no edits of it to blank) and imports it a line above everything in
     * main.inko. No check of the code as it is, or of a repaired copy, answers; `run` is unchanged, and the
     * import is about `broken`, which it doesn't mention, so the answers that hold there come from the last
     * good check, mapped to where their text is now.
     */
    private fun openThenBreakRun(): String {
        open()
        assertFalse(inBackground { CuttleboneQueries.getInstance(project).inlayHints(myFixture.file) }!!.stale)
        myFixture.addFileToProject("src/app/broken.inko", "fn pub broken( {\n")
        val document = myFixture.editor.document
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(main.indexOf("\n") + 1, "import app.broken\n")
        }
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        PlatformTestUtil.waitWithEventsDispatching("the edit to be sent", {
            val server = CuttleboneService.getInstance(project).server()
            server != null && CuttleboneService.getInstance(project).isSynced(server, myFixture.file.virtualFile, document)
        }, TimingLimits.seconds(30))
        return document.text
    }

    fun testAfterAnEditNoCheckCanAnswerHintsAreStaleAndMarked() {
        val text = openThenBreakRun()
        val answer = inBackground { CuttleboneQueries.getInstance(project).inlayHints(myFixture.file) }
        assertTrue("from the last good check: ${answer?.status}", answer!!.stale)
        // Mapped to where their text is now, a line further down.
        assertEquals(listOf(text.indexOf("x = make") + 1 to ": B", text.indexOf("y = [") + 1 to ": Array[Int]"), inBackground { InkoParameterHints.types(myFixture.file) })
        assertEquals(CuttleboneQueries.OUTDATED, inBackground { InkoParameterHints.tooltip(myFixture.file) })
    }

    fun testAfterAnEditNoCheckCanAnswerOnlyNavigationUsesAStaleDefinition() {
        val text = openThenBreakRun()
        // Cuttlebone has a stale definition here: navigation takes it, Rename and the refactorings don't.
        assertEquals(listOf("util.inko:8:size"), targets(text, "size(2.0)"))
        assertEquals(emptyList<Any>(), inBackground { InkoResolver.resolveExactly(nameAt(text, "size(2.0)")) })
        // To what takes only fresh answers, the code can't be checked as it is: it's being edited.
        val answer = inBackground { CuttleboneQueries.getInstance(project).definition(myFixture.file, text.indexOf("size(2.0)")) }!!
        assertNull(answer.value)
        assertTrue(answer.editing)
    }

    fun testAfterAnEditNoCheckCanAnswerDocumentationMarksTheTypeOutdated() {
        val text = openThenBreakRun()
        val name = nameAt(text, "get(0)")
        val html = inBackground {
            val target = (name.reference as InkoReference).multiResolve(false).first().element!!
            (InkoDocumentationTargetProvider().documentationTarget(target, name.firstChild) as InkoDocumentationTarget).html()
        }
        assertTrue(html, html.contains("<code>Result[Int, OutOfBounds]</code> (${CuttleboneQueries.OUTDATED})"))
    }

    fun testAfterAnEditImportingAMissingModuleTheRepairedAnswersAreUsedLikeFreshOnes() {
        // An import of a module that doesn't exist is blank in a repaired copy, which answers (fresh) wherever
        // the import can't matter: Parakeet uses those answers as it would any fresh one, for what changes code too.
        open()
        val document = myFixture.editor.document
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(main.indexOf("\n") + 1, "import app.nope\n")
        }
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        PlatformTestUtil.waitWithEventsDispatching("the edit to be sent", {
            val server = CuttleboneService.getInstance(project).server()
            server != null && CuttleboneService.getInstance(project).isSynced(server, myFixture.file.virtualFile, document)
        }, TimingLimits.seconds(30))
        val text = document.text
        val hints = inBackground { CuttleboneQueries.getInstance(project).inlayHints(myFixture.file) }!!
        assertTrue("fresh, from a repaired copy: ${hints.status}", hints.status!!.fresh && hints.repaired)
        assertEquals(listOf(text.indexOf("x = make") + 1 to ": B", text.indexOf("y = [") + 1 to ": Array[Int]"), inBackground { InkoParameterHints.types(myFixture.file) })
        assertNull("not marked", inBackground { InkoParameterHints.tooltip(myFixture.file) })
        // A fresh definition: navigation and what changes code (Rename, the refactorings) both take it.
        assertEquals(listOf("util.inko:8:size"), targets(text, "size(2.0)"))
        assertEquals(listOf("util.inko:8:size"), inBackground { InkoResolver.resolveExactly(nameAt(text, "size(2.0)")).map { it.location() } })
        val name = nameAt(text, "get(0)")
        val html = inBackground {
            val target = (name.reference as InkoReference).multiResolve(false).first().element!!
            (InkoDocumentationTargetProvider().documentationTarget(target, name.firstChild) as InkoDocumentationTarget).html()
        }
        assertTrue(html, html.contains("<code>Result[Int, OutOfBounds]</code>") && !html.contains(CuttleboneQueries.OUTDATED))
    }

    fun testDocumentationShowsTheTypeOfThisUse() {
        open()
        val name = nameAt(main, "get(0)")
        val html = inBackground {
            val target = (name.reference as InkoReference).multiResolve(false).first().element!!
            (InkoDocumentationTargetProvider().documentationTarget(target, name.firstChild) as InkoDocumentationTarget).html()
        }
        assertTrue(html, html.contains("Type here"))
        assertTrue(html, html.contains("<code>Result[Int, OutOfBounds]</code>"))
    }

    fun testExpressionTypeOfACallWhoseTypeTheCodeDoesntState() {
        open()
        val provider = InkoExpressionTypeProvider()
        val call = main.indexOf("y.get(0)")
        val types = inBackground {
            provider.getExpressionsAt(myFixture.file.findElementAt(call + 2)!!).associate { it.text to provider.getInformationHint(it) }
        }
        assertEquals("Result[Int, OutOfBounds]", types["y.get(0)"])
    }

    /**
     * Code where each expression's hover, since Cuttlebone 0.12.0, has the declaration of the name there after
     * its type (`make`, `new`, `reset`), and the declaration of `reset` says `mut`: what reads the type must
     * take the type alone.
     */
    private val typed = """
        |import app.util (C)
        |
        |fn make -> C {
        |  C()
        |}
        |
        |fn pub run -> Int {
        |  let x = make
        |  let c: C = C.new
        |  x.size + c.size + make.reset
        |}
        |""".trimMargin()

    private fun range(text: String, part: String) = text.indexOf(part).let { com.intellij.openapi.util.TextRange(it, it + part.length) }

    fun testExpressionTypeOfANameIsTheTypeAlone() {
        open(typed)
        val provider = InkoExpressionTypeProvider()
        val types = inBackground {
            provider.getExpressionsAt(myFixture.file.findElementAt(typed.indexOf("make\n"))!!).associate { it.text to provider.getInformationHint(it) }
        }
        assertEquals("C", types["make"])
    }

    fun testSpecifyTypeExplicitlyWritesTheTypeAlone() {
        open(typed)
        val rewrite = inBackground { InkoLetTypes.add(myFixture.file, typed.indexOf("x = make")) }
        assertEquals(listOf(": C"), rewrite?.edits?.map { it.text })
    }

    fun testRemoveExplicitTypeComparesTheTypeAlone() {
        open(typed)
        assertEquals("Remove explicit type", inBackground { InkoLetTypes.remove(myFixture.file, typed.indexOf("c: C")) }?.title)
    }

    fun testExtractMethodWritesTheTypeAlone() {
        open(typed)
        com.intellij.codeInsight.template.impl.TemplateManagerImpl.setTemplateTesting(testRootDisposable)
        val call = range(typed, "make\n")
        myFixture.editor.selectionModel.setSelection(call.startOffset, call.startOffset + "make".length)
        InkoExtractMethodHandler().invoke(project, myFixture.editor, myFixture.file, null)
        com.intellij.codeInsight.template.impl.TemplateManagerImpl.getTemplateState(myFixture.editor)?.gotoEnd(false)
        val text = myFixture.editor.document.text
        assertTrue(text, text.contains("fn extracted -> C {\n  make\n}"))
    }

    fun testIntroduceParameterWritesTheTypeAlone() {
        open(typed)
        val call = range(typed, "make.reset").let { com.intellij.openapi.util.TextRange(it.startOffset, it.startOffset + "make".length) }
        val type = inBackground { InkoIntroduceParameterHandler.compilerType(myFixture.file, call) }
        assertEquals("C", type)
        val plan = InkoIntroduceParameter.plan(myFixture.file, call) { type }.getOrThrow()
        InkoIntroduceParameter.processor(project, plan, "made", plan.type!!, replaceAll = true).run()
        val text = myFixture.editor.document.text
        assertTrue(text, text.contains("fn pub run(made: C) -> Int {"))
    }

    // `fn pub mut reset` says `mut`, but `make.reset` is an `Int`, not a borrow: Introduce Variable keeps it.
    fun testIntroduceVariableChecksTheTypeAloneForABorrow() {
        open(typed)
        assertNull(inBackground { InkoIntroduceVariable.borrowsFromATemporary(myFixture.file as InkoFile, range(typed, "make.reset")) })
    }

    fun testGoToTypeDeclarationIsIntellijs() {
        open()
        val offset = main.indexOf("x.size")
        myFixture.editor.caretModel.moveToOffset(offset)
        myFixture.performEditorAction("GotoTypeDeclaration")
        PlatformTestUtil.waitWithEventsDispatching("navigation to util.inko", {
            com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor?.virtualFile?.name == "util.inko"
        }, TimingLimits.seconds(30))
    }

    /** Client-side latency of each query, as Parakeet sees it, for the session report. */
    fun testLatency() {
        open()
        val queries = CuttleboneQueries.getInstance(project)
        val offsets = listOf("size(2.0)", "get(0)", "or(0)", "make\n  let", "B()").map { main.indexOf(it) }
        fun measure(name: String, ask: (Int) -> Any?) {
            val times = offsets.map { offset ->
                inBackground {
                    val start = System.nanoTime()
                    ask(offset)
                    (System.nanoTime() - start) / 1_000_000.0
                }
            }.sorted()
            println("LATENCY $name: median ${times[times.size / 2]} ms, min ${times.first()}, max ${times.last()} (${times.size} positions)")
            val cached = inBackground { val start = System.nanoTime(); ask(offsets.first()); (System.nanoTime() - start) / 1_000_000.0 }
            println("LATENCY $name cached: $cached ms")
        }
        val resolveTimes = listOf("size(2.0)", "get(0)", "or(0)").map { marker ->
            com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
            inBackground {
                val name = nameAt(main, marker)
                val start = System.nanoTime()
                InkoResolver.resolveDefinitions(name)
                (System.nanoTime() - start) / 1_000_000.0
            }
        }
        println("LATENCY resolve (cold, includes the definition request): $resolveTimes ms")
        measure("definition") { queries.definition(myFixture.file, it, 5000) }
        measure("hover") { queries.hover(myFixture.file, it, 5000) }
        measure("typeDefinition") { queries.typeDefinition(myFixture.file, it, 5000) }
    }

    /**
     * The real cuttlebone, packed as a release's archive is and served locally, downloaded when the offer is
     * clicked, installed, and started: it answers as the one installed did.
     */
    fun testDownloadsABuildAndStartsIt() {
        val installed = Cuttlebone.executable(project)!!
        val versionOutput = com.intellij.execution.process.CapturingProcessHandler(
            com.intellij.execution.configurations.GeneralCommandLine(installed.toString(), "--version"),
        ).runProcess(10_000).stdout.trim()
        val version = Regex("""^cuttlebone (\S+)""").find(versionOutput)!!.groupValues[1]
        val inko = Cuttlebone.linkedInko(versionOutput)!!
        val platform = CuttlebonePlatform.current!!
        CuttleboneReleaseServer().use { server ->
            server.publish(version, listOf(inko), java.nio.file.Files.readAllBytes(installed), listOf(platform))
            cuttleboneLocations.releasesSource = server.releasesSource
            cuttleboneLocations.platformOverride = platform
            cuttleboneLocations.onPath = { null }
            useCuttlebone("")
            fun offers() = com.intellij.notification.NotificationsManager.getNotificationsManager()
                .getNotificationsOfType(com.intellij.notification.Notification::class.java, project)
                .filter { it.title == "Parakeet needs Cuttlebone" && !it.isExpired }
            offers().forEach { it.expire() }
            myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("src/main.inko", main).virtualFile)
            PlatformTestUtil.waitWithEventsDispatching("no download offered", { offers().any { n -> n.actions.any { it.templateText == "Download Cuttlebone $version" } } }, TimingLimits.seconds(60))
            val offer = offers().single()
            com.intellij.notification.Notification.fire(offer, offer.actions.first { it.templateText == "Download Cuttlebone $version" }, null)
            val service = CuttleboneService.getInstance(project)
            PlatformTestUtil.waitWithEventsDispatching({ "the download didn't start: ${service.status?.message}" }, {
                service.status is Cuttlebone.Status.Running && service.server() != null
            }, TimingLimits.seconds(120))
            assertEquals(Cuttlebone.Source.DOWNLOADED, service.located?.source)
            assertTrue(service.located!!.path.startsWith(cuttleboneLocations.downloads))
            assertEquals(versionOutput, service.versionOutput)
            assertEquals(listOf("util.inko:8:size"), targets(main, "size(2.0)"))
        }
    }
}
