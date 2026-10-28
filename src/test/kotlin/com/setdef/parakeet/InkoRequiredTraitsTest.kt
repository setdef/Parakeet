package com.setdef.parakeet

import com.intellij.psi.util.PsiTreeUtil

/**
 * Required traits in the type hierarchy. `inko doc` writes a trait's type as
 * `trait pub Debug`, without what it requires (compiler/src/docs.rs, `traits`),
 * so they come from the trait's declaration. AUDIT.md finding 1.10.
 */
class InkoRequiredTraitsTest : InkoTestCase(onDisk = true) {
    private val util = "trait pub Show {}\n\ntrait pub Debug: Show {}\n\ntype pub Other {}\n\nimpl Show for Other {}\n\nimpl Debug for Other {}\n"

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("src/app/util.inko", util)
        useFakeInko(FakeInkoCli { FakeInkoCli.ok(stderr = "[]") })
        myFixture.configureFromTempProjectFile("src/app/util.inko")
    }

    private fun declaration(name: String): InkoDeclaration =
        PsiTreeUtil.findChildrenOfType(myFixture.file, InkoDeclaration::class.java).first { it.name == name }

    fun testATraitsRequirementsComeFromItsDeclaration() {
        assertEquals(listOf("Show"), InkoTypeHierarchy.related(declaration("Debug"), supertypes = true).map { InkoTypeHierarchy.definition(it)!!.name })
    }

    fun testATraitRequiredByAnotherIsItsSubtype() {
        assertTrue(InkoTypeHierarchy.related(declaration("Show"), supertypes = false).map { InkoTypeHierarchy.definition(it)!!.name }.contains("Debug"))
    }

    fun testTypeParameterBoundsAreNotRequirements() {
        val trait = InkoDefinition(InkoDefinitionKind.TRAIT, "Debug", "m", null, java.nio.file.Path.of("/m.inko"), 1, 1, "trait pub Debug[T: Show + Clone]: Format")
        assertEquals(listOf("Format"), InkoTypeHierarchy.requiredTraitNames(trait))
        assertEquals(emptyList<String>(), InkoTypeHierarchy.requiredTraitNames(trait.copy(signature = "trait pub Debug[T: Show]")))
    }
}
