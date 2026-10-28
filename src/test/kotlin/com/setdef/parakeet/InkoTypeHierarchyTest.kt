package com.setdef.parakeet

import com.intellij.ide.hierarchy.HierarchyNodeDescriptor
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

/** The type hierarchy (traits, what requires them, and what implements them), from Cuttlebone's. */
class InkoTypeHierarchyTest : InkoTestCase(onDisk = true) {
    private val util = """
        |trait pub Show {}
        |
        |trait pub Debug: Show {}
        |
        |type pub Helper {}
        |
        |impl Show for Helper {}
        |
        |type pub Other {}
        |
        |impl Debug for Other {}
        |
        |# Debug requires Show, so Other implements it too, as the compiler requires.
        |impl Show for Other {}
        |""".trimMargin()

    private fun at(marker: String) = At("src/app/util.inko", util, marker)

    override fun setUp() {
        super.setUp()
        // Cuttlebone's answers for this code, as its type database has them.
        Compiler()
            .resolves(at("Show for Helper"), at("Show {}"))
            .related("typeHierarchy/supertypes", at("Helper {}"), at("Show {}"))
            .related("typeHierarchy/supertypes", at("Debug:"), at("Show {}"))
            .related("typeHierarchy/supertypes", at("Other {}"), at("Debug:"), at("Show {}"))
            .related("typeHierarchy/subtypes", at("Show {}"), at("Debug:"), at("Helper {}"), at("Other {}"))
            .related("typeHierarchy/subtypes", at("Debug:"), at("Other {}"))
            .install()
        myFixture.addFileToProject("src/app/util.inko", util)
        useFakeInko(FakeInkoCli { FakeInkoCli.ok(stderr = "[]") })
        myFixture.configureFromTempProjectFile("src/app/util.inko")
    }

    private fun declaration(name: String): InkoDeclaration =
        PsiTreeUtil.findChildrenOfType(myFixture.file, InkoDeclaration::class.java).first { it.name == name }

    private fun Compiler.related(method: String, to: At, vararg related: At) = answers(method, to) { related.map(::location) }

    private fun names(elements: List<PsiElement>) = elements.map { it.text }

    fun testATypesSupertypesAreTheTraitsItImplements() =
        assertEquals(listOf("Show"), names(InkoTypeHierarchy.related(declaration("Helper"), supertypes = true)))

    fun testATraitsSupertypesAreTheTraitsItRequires() =
        assertEquals(listOf("Show"), names(InkoTypeHierarchy.related(declaration("Debug"), supertypes = true)))

    fun testATraitsSubtypesAreRequiringTraitsAndImplementingTypes() =
        assertEquals(listOf("Debug", "Helper", "Other"), names(InkoTypeHierarchy.related(declaration("Show"), supertypes = false)))

    /** The tree under [element], indented, as the hierarchy view would show it. */
    private fun tree(element: PsiElement, supertypes: Boolean): String {
        val structure = InkoTypeHierarchyTreeStructure(project, element, supertypes)
        fun render(descriptor: HierarchyNodeDescriptor, depth: Int): String {
            descriptor.update()
            val line = "  ".repeat(depth) + descriptor.highlightedText.text + "\n"
            return line + structure.getChildElements(descriptor).map { render(it as HierarchyNodeDescriptor, depth + 1) }.sorted().joinToString("")
        }
        return render(structure.rootElement as HierarchyNodeDescriptor, 0)
    }

    fun testSubtypesTree() = assertEquals(
        "Show (app.util)\n  Debug (app.util)\n    Other (app.util)\n  Helper (app.util)\n  Other (app.util)\n",
        tree(declaration("Show"), supertypes = false),
    )

    fun testSupertypesTree() = assertEquals(
        "Other (app.util)\n  Debug (app.util)\n    Show (app.util)\n  Show (app.util)\n",
        tree(declaration("Other"), supertypes = true),
    )

    fun testTargetsTheTypeOrTraitAtTheCaretEvenAtAUse() {
        val text = myFixture.file.text
        myFixture.editor.caretModel.moveToOffset(text.indexOf("Show for Helper"))
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.EDITOR, myFixture.editor).add(CommonDataKeys.PSI_FILE, myFixture.file).build()
        val target = InkoTypeHierarchyProvider().getTarget(context)
        assertEquals(declaration("Show"), target)
    }

    fun testTheBrowserOpensTraitsOnWhatImplementsThem() {
        val provider = com.intellij.ide.hierarchy.LanguageTypeHierarchy.INSTANCE.forLanguage(InkoLanguage)
        assertTrue(provider is InkoTypeHierarchyProvider)
        val browser = provider.createHierarchyBrowser(declaration("Show")) as InkoTypeHierarchyBrowser
        com.intellij.openapi.util.Disposer.register(testRootDisposable, browser)
        assertTrue(browser.isInterface)
    }
}
