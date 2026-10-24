package com.setdef.parakeet

import com.intellij.ide.hierarchy.HierarchyNodeDescriptor
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

/** The type hierarchy (traits, what requires them, and what implements them), from a fake `inko doc`. */
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
        |""".trimMargin()

    override fun setUp() {
        super.setUp()
        // Parakeet's own reading of the code, which stands in only where Cuttlebone can't check it.
        cuttleboneCantCheckTheCode()
        myFixture.addFileToProject("src/app/util.inko", util)
        useFakeInko(FakeInkoDoc.cli { mirror ->
            val file = mirror.resolve("src/app/util.inko")
            listOf(
                FakeInkoDoc.module(
                    "app.util", file,
                    traits = listOf(
                        FakeInkoDoc.trait("Show", 1, implementations = listOf("app.util" to "Helper")),
                        FakeInkoDoc.trait("Debug", 3, implementations = listOf("app.util" to "Other"), signature = "trait pub Debug: Show"),
                    ),
                    types = listOf(
                        FakeInkoDoc.type("Helper", 5, implementedTraits = listOf("app.util" to "Show")),
                        FakeInkoDoc.type("Other", 9, implementedTraits = listOf("app.util" to "Debug", "app.util" to "Show")),
                    ),
                ),
            )
        })
        myFixture.configureFromTempProjectFile("src/app/util.inko")
    }

    private fun declaration(name: String): InkoDeclaration =
        PsiTreeUtil.findChildrenOfType(myFixture.file, InkoDeclaration::class.java).first { it.name == name }

    private fun names(elements: List<PsiElement>) = elements.map { InkoTypeHierarchy.definition(it)!!.name }

    fun testATypesSupertypesAreTheTraitsItImplements() =
        assertEquals(listOf("Show"), names(InkoTypeHierarchy.related(declaration("Helper"), supertypes = true)))

    fun testATraitsSupertypesAreTheTraitsItRequires() =
        assertEquals(listOf("Show"), names(InkoTypeHierarchy.related(declaration("Debug"), supertypes = true)))

    fun testATraitsSubtypesAreRequiringTraitsAndImplementingTypes() =
        assertEquals(listOf("Debug", "Helper"), names(InkoTypeHierarchy.related(declaration("Show"), supertypes = false)))

    fun testReadsRequiredTraitsFromTheSignature() {
        val trait = InkoDefinition(InkoDefinitionKind.TRAIT, "Debug", "m", null, java.nio.file.Path.of("/m.inko"), 1, 1, "trait pub Debug: ToString + Format[T]")
        assertEquals(listOf("ToString", "Format"), InkoTypeHierarchy.requiredTraitNames(trait))
    }

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
        "Show (app.util)\n  Debug (app.util)\n    Other (app.util)\n  Helper (app.util)\n",
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
