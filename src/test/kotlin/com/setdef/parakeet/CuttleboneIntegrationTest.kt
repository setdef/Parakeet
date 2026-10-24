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
        }, 60)
    }

    private fun <T> inBackground(action: () -> T): T {
        val future = ApplicationManager.getApplication().executeOnPooledThread<T> { ReadAction.compute<T, Throwable> { action() } }
        PlatformTestUtil.waitWithEventsDispatching("background action", { future.isDone }, 60)
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

    /** Whether this cuttlebone answers textDocument/inlayHint (milestone 4 and later). */
    private fun hasInlayHints(): Boolean =
        CuttleboneService.getInstance(project).server()?.initializeResult?.capabilities?.inlayHintProvider != null

    // `y.get(0).or(0)`: Array's `get(index)`, and Option's `or(default)`, which Parakeet can tell too.
    private val index get() = main.indexOf("0).or")
    private val default get() = main.indexOf("0)\n")

    fun testHintsForTheWholeFile() {
        open()
        val parameters = inBackground { InkoParameterHints.inFile(myFixture.file) }
        val types = inBackground { InkoParameterHints.types(myFixture.file) }
        if (!hasInlayHints()) {
            println("This cuttlebone has no inlay hints, so Parakeet's own are checked instead")
            assertEquals("x.size: two parameter lists fit, so no hint", listOf(default to "default"), parameters)
            assertEquals(emptyList<Any>(), types)
            return
        }
        assertEquals(listOf(index to "index", default to "default", main.indexOf("2.0") to "scale"), parameters)
        assertEquals(listOf(main.indexOf("x = make") + 1 to ": B", main.indexOf("y = [") + 1 to ": Array[Int]"), types)
    }

    fun testParameterInfoHasTheCompilersSignature() {
        // Being typed: no `)` yet, which Cuttlebone allows for.
        val text = main.replace("x.size(2.0)", "x.size(")
        open(text)
        val help = inBackground { CuttleboneQueries.getInstance(project).signatureHelp(myFixture.file, text.indexOf("x.size(") + 7) }
        if (CuttleboneService.getInstance(project).server()?.initializeResult?.capabilities?.signatureHelpProvider == null) {
            println("This cuttlebone has no signature help, so parameter info is Parakeet's")
            assertNull(help)
            return
        }
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
        if (CuttleboneService.getInstance(project).server()?.initializeResult?.capabilities?.documentHighlightProvider == null) {
            println("This cuttlebone has no document highlight, so usage highlighting is Parakeet's")
            assertEquals(listOf(text.indexOf("total = 0")), writes)
            return
        }
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

    fun testOpenedWithADeclarationErrorHintsFallBackToParakeet() {
        // No check has typed a method body yet, and the error isn't in a change Cuttlebone saw, so it has no answer.
        // Parakeet's hints still name what it can resolve.
        val text = main + twiceAndUses + "\nfn broken -> Missing {\n  1\n}\n"
        open(text)
        assertTrue("code being edited", inBackground { CuttleboneQueries.getInstance(project).inlayHints(myFixture.file) }!!.editing)
        assertEquals(listOf(default to "default", text.indexOf("21") to "value"), inBackground { InkoParameterHints.inFile(myFixture.file) })
        assertEquals("only Cuttlebone knows types", emptyList<Any>(), inBackground { InkoParameterHints.types(myFixture.file) })
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
     * Opens [main], has Cuttlebone check it (its last good check), then adds an import of a module that
     * doesn't exist: no copy of that type checks, so the answers that hold come from the last good check.
     */
    private fun openThenBreakTheImports(): String {
        open()
        assertFalse(inBackground { CuttleboneQueries.getInstance(project).inlayHints(myFixture.file) }!!.stale)
        val document = myFixture.editor.document
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(main.indexOf("\n") + 1, "import app.nope\n")
        }
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        PlatformTestUtil.waitWithEventsDispatching("the edit to be sent", {
            val server = CuttleboneService.getInstance(project).server()
            server != null && CuttleboneService.getInstance(project).isSynced(server, myFixture.file.virtualFile, document)
        }, 30)
        return document.text
    }

    fun testAfterAnEditNoCheckCanAnswerHintsAreStaleAndMarked() {
        val text = openThenBreakTheImports()
        val answer = inBackground { CuttleboneQueries.getInstance(project).inlayHints(myFixture.file) }
        assertTrue("from the last good check: ${answer?.status}", answer!!.stale)
        // Mapped to where their text is now, a line further down.
        assertEquals(listOf(text.indexOf("x = make") + 1 to ": B", text.indexOf("y = [") + 1 to ": Array[Int]"), inBackground { InkoParameterHints.types(myFixture.file) })
        assertEquals(CuttleboneQueries.OUTDATED, inBackground { InkoParameterHints.tooltip(myFixture.file) })
    }

    fun testAfterAnEditNoCheckCanAnswerResolveDoesntUseAStaleDefinition() {
        val text = openThenBreakTheImports()
        // Cuttlebone has a stale definition here, but Rename and the refactorings resolve with it.
        // Only a stale one: the code can't be checked as it is, so Parakeet reads it.
        val answer = inBackground { CuttleboneQueries.getInstance(project).definition(myFixture.file, text.indexOf("size(2.0)")) }!!
        assertNull(answer.value)
        assertTrue(answer.editing)
        assertEquals(listOf("util.inko:2:size", "util.inko:8:size"), targets(text, "size(2.0)").sorted())
    }

    fun testAfterAnEditNoCheckCanAnswerDocumentationMarksTheTypeOutdated() {
        val text = openThenBreakTheImports()
        val name = nameAt(text, "get(0)")
        val html = inBackground {
            val target = (name.reference as InkoReference).multiResolve(false).first().element!!
            (InkoDocumentationTargetProvider().documentationTarget(target, name.firstChild) as InkoDocumentationTarget).html()
        }
        assertTrue(html, html.contains("<code>Result[Int, OutOfBounds]</code> (${CuttleboneQueries.OUTDATED})"))
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

    fun testGoToTypeDeclarationIsIntellijs() {
        open()
        val offset = main.indexOf("x.size")
        myFixture.editor.caretModel.moveToOffset(offset)
        myFixture.performEditorAction("GotoTypeDeclaration")
        PlatformTestUtil.waitWithEventsDispatching("navigation to util.inko", {
            com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor?.virtualFile?.name == "util.inko"
        }, 30)
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
}
