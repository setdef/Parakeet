package com.setdef.parakeet

import com.intellij.ide.hierarchy.HierarchyNodeDescriptor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil

/** Call Hierarchy and usage counts, with `inko doc` output supplied by a fake. */
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

    override fun setUp() {
        super.setUp()
        putTempDirInProjectContent()
        myFixture.addFileToProject("src/app/util.inko", util)
        myFixture.addFileToProject("src/main.inko", main)
        useFakeInko(FakeInkoDoc.cli { mirror ->
            val utilFile = mirror.resolve("src/app/util.inko")
            val mainFile = mirror.resolve("src/main.inko")
            listOf(
                FakeInkoDoc.module(
                    "app.util", utilFile,
                    methods = listOf(FakeInkoDoc.member("helper", utilFile, 11, 1)),
                    types = listOf(FakeInkoDoc.type("Helper", 1, staticMethods = listOf(FakeInkoDoc.member("new", utilFile, 2)), instanceMethods = listOf(FakeInkoDoc.member("value", utilFile, 6)))),
                ),
                FakeInkoDoc.module("main", mainFile, methods = listOf(FakeInkoDoc.member("run", mainFile, 3, 1), FakeInkoDoc.member("twice", mainFile, 7, 1))),
            )
        })
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
        myFixture.addFileToProject("src/many.inko", "import app.util (helper)\n\nfn many {\n$calls\n}\n")
        val util = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("src/app/util.inko"))!!
        assertEquals("99+ usages", InkoUsagesCodeVisionProvider().getHint(declaration("src/app/util.inko", "helper"), util))
    }
}
