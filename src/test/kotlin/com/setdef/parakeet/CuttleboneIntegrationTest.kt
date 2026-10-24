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

    fun testParameterHintsForTheResolvedMethod() {
        open()
        val paren = myFixture.file.findElementAt(main.indexOf("(2.0)"))!!
        inBackground { InkoParameterHints.prefetch(myFixture.file, listOf(paren)) }
        assertEquals(listOf(main.indexOf("2.0") to "scale"), InkoParameterHints.at(paren))
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

    // The trade-off for completion: with a syntax error anywhere in the file, Cuttlebone has
    // no answer (noHir), and Parakeet's own member completion still offers something.
    fun testWithASyntaxErrorElsewhereCompletionFallsBackToParakeet() {
        val text = main.replace("x.size(2.0)", "x.") + "\nfn broken( {\n"
        open(text)
        val offset = text.indexOf("x.\n") + 2
        myFixture.editor.caretModel.moveToOffset(offset)
        assertNull(inBackground { CuttleboneQueries.getInstance(project).completion(myFixture.file, offset) })
        myFixture.completeBasic()
        assertTrue(myFixture.lookupElementStrings.toString(), myFixture.lookupElementStrings!!.contains("size"))
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
