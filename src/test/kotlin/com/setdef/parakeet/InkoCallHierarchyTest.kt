package com.setdef.parakeet

import com.intellij.ide.hierarchy.HierarchyNodeDescriptor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil

/** Call Hierarchy and usage counts. */
class InkoCallHierarchyTest : InkoTestCase(onDisk = true) {
    private val util = """
        |type pub Helper {
        |  fn pub static new -> Helper {
        |    Helper()
        |  }
        |
        |  fn pub value -> Int {
        |    helper
        |  }
        |}
        |
        |fn pub helper -> Int {
        |  42
        |}
        |""".trimMargin()

    private val main = """
        |import app.util (Helper, helper)
        |
        |fn run -> Int {
        |  Helper.new.value + helper
        |}
        |
        |fn twice -> Int {
        |  run + run + twice
        |}
        |""".trimMargin()

    private fun inUtil(marker: String) = At("src/app/util.inko", util, marker)
    private fun inMain(marker: String) = At("src/main.inko", main, marker)

    /** Cuttlebone's answers for this code, as the compiler resolves it. */
    private fun compiler() = Compiler()
        .resolves(At("src/app/util.inko", util, "Helper {", shift = 0, nth = 1), inUtil("Helper {"))
        .resolves(inUtil("Helper()"), inUtil("Helper {"))
        .resolves(inUtil("helper\n  }"), inUtil("helper -> Int"))
        .resolves(inMain("Helper.new"), inUtil("Helper {"))
        .resolves(inMain("new.value"), inUtil("new -> Helper"))
        .resolves(inMain("value + helper"), inUtil("value -> Int"))
        .resolves(inMain("helper\n}"), inUtil("helper -> Int"))
        .resolvesEvery("src/main.inko", main, "run +", At("src/main.inko", main, "run -> Int"))
        .resolves(inMain("twice\n}"), At("src/main.inko", main, "twice -> Int"))
        // Cuttlebone's call hierarchy: the methods each call is in, and the methods a body calls.
        .callers(inUtil("helper -> Int"), inUtil("value -> Int"), inMain("run -> Int"))
        .callers(inUtil("value -> Int"), inMain("run -> Int"))
        .callers(inUtil("new -> Helper"), inMain("run -> Int"))
        .callers(inMain("run -> Int"), inMain("twice -> Int"))
        .callers(inMain("twice -> Int"), inMain("twice -> Int"))
        .callees(inMain("run -> Int"), inUtil("new -> Helper"), inUtil("value -> Int"), inUtil("helper -> Int"))

    private fun Compiler.callers(method: At, vararg callers: At) = answers("callHierarchy/incomingCalls", method) { callers.map(::location) }

    private fun Compiler.callees(method: At, vararg callees: At) = answers("callHierarchy/outgoingCalls", method) { callees.map(::location) }

    override fun setUp() {
        super.setUp()
        compiler().install()
        putTempDirInProjectContent()
        myFixture.addFileToProject("src/app/util.inko", util)
        myFixture.addFileToProject("src/main.inko", main)
        useFakeInko(FakeInkoCli { FakeInkoCli.ok(stderr = "[]") })
    }

    private fun declaration(path: String, name: String): InkoDeclaration {
        val file = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir(path))!!
        return PsiTreeUtil.findChildrenOfType(file, InkoDeclaration::class.java).first { it.name == name }
    }

    private fun labels(elements: List<PsiElement>) = elements.map(InkoCallHierarchy::label).sorted()

    fun testCallersAreTheMethodsUsingIt() =
        assertEquals(listOf("Helper.value", "run"), labels(InkoCallHierarchy.callers(declaration("src/app/util.inko", "helper"))))

    fun testCalleesAreTheMethodsItCalls() =
        assertEquals(listOf("Helper.new", "Helper.value", "helper"), labels(InkoCallHierarchy.callees(declaration("src/main.inko", "run"))))

    fun testCallerTree() {
        val structure = InkoCallTreeStructure(project, declaration("src/app/util.inko", "helper"), callers = true)
        fun render(descriptor: HierarchyNodeDescriptor, depth: Int): String {
            descriptor.update()
            return "  ".repeat(depth) + descriptor.highlightedText.text + "\n" +
                structure.getChildElements(descriptor).map { render(it as HierarchyNodeDescriptor, depth + 1) }.sorted().joinToString("")
        }
        // Helper.value (which calls helper) is called by run; twice calls itself, shown once rather than forever.
        assertEquals(
            "helper (app.util)\n" +
                "  Helper.value (app.util)\n    run (main)\n      twice (main)\n        twice (main)\n" +
                "  run (main)\n    twice (main)\n      twice (main)\n",
            render(structure.rootElement as HierarchyNodeDescriptor, 0),
        )
    }

    fun testUsageCounts() {
        val provider = InkoUsagesCodeVisionProvider()
        val main = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("src/main.inko"))!!
        assertEquals("2 usages", provider.getHint(declaration("src/main.inko", "run"), main))
        assertEquals("1 usage", provider.getHint(declaration("src/main.inko", "twice"), main))
        assertTrue(provider.acceptsElement(declaration("src/app/util.inko", "Helper")))
        // Locals don't get counts.
        myFixture.addFileToProject("src/local.inko", "fn a {\n  let x = 1\n  x\n}\n")
        assertFalse(provider.acceptsElement(declaration("src/local.inko", "x")))
    }

    fun testTargetsTheCalledMethodAndOpensOnItsCallers() {
        myFixture.configureFromTempProjectFile("src/main.inko")
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("helper\n"))
        val context = com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
            .add(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR, myFixture.editor)
            .add(com.intellij.openapi.actionSystem.CommonDataKeys.PSI_FILE, myFixture.file).build()
        val provider = com.intellij.ide.hierarchy.LanguageCallHierarchy.INSTANCE.forLanguage(InkoLanguage)
        val target = provider.getTarget(context)
        assertEquals(declaration("src/app/util.inko", "helper"), target)
        val browser = provider.createHierarchyBrowser(target!!)
        com.intellij.openapi.util.Disposer.register(testRootDisposable, browser as com.intellij.openapi.Disposable)
        assertTrue(browser is InkoCallHierarchyBrowser)
    }

    fun testUsageCountsStopAtNinetyNinePlus() {
        val calls = (1..120).joinToString("\n") { "  helper" }
        val many = "import app.util (helper)\n\nfn many {\n$calls\n}\n"
        myFixture.addFileToProject("src/many.inko", many)
        compiler().resolvesEvery("src/many.inko", many, "helper\n", inUtil("helper -> Int")).install()
        val util = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("src/app/util.inko"))!!
        assertEquals("99+ usages", InkoUsagesCodeVisionProvider().getHint(declaration("src/app/util.inko", "helper"), util))
    }
}
