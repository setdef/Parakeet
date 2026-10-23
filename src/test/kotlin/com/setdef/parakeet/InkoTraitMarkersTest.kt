package com.setdef.parakeet

import com.intellij.psi.PsiElement

/** Gutter icons linking traits and implementations, from inko doc's trait data. */
class InkoTraitMarkersTest : InkoTestCase(onDisk = true) {
    private val util = """
        |trait pub Show {
        |  fn pub show -> String
        |}
        |
        |type pub Helper {}
        |
        |impl Show for Helper {
        |  fn pub show -> String {
        |    'h'
        |  }
        |}
        |""".trimMargin()

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("src/app/util.inko", util)
        useFakeInko(FakeInkoDoc.cli { mirror ->
            val file = mirror.resolve("src/app/util.inko")
            listOf(FakeInkoDoc.module(
                "app.util", file,
                types = listOf(FakeInkoDoc.type(
                    "Helper", 5,
                    instanceMethods = listOf(FakeInkoDoc.member("show", file, 8)),
                    implementedTraits = listOf("app.util" to "Show"),
                )),
                traits = listOf(FakeInkoDoc.trait(
                    "Show", 1,
                    requiredMethods = listOf(FakeInkoDoc.member("show", file, 2)),
                    implementations = listOf("app.util" to "Helper"),
                )),
            ))
        })
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("src/app/util.inko"))
    }

    private fun token(marker: String, nth: Int = 0): PsiElement {
        var index = -1
        repeat(nth + 1) { index = util.indexOf(marker, index + 1) }
        assertTrue("'$marker' isn't in the file", index >= 0)
        return myFixture.file.findElementAt(index)!!
    }

    private fun link(marker: String, nth: Int = 0): Pair<String, List<String>>? = InkoTraitLinks.of(token(marker, nth))?.let { link ->
        link.tooltip to link.targets.map { "${it.kind}:${it.name}:${it.line}" }
    }

    fun testImplPointsToTheTrait() =
        assertEquals("Implements trait Show" to listOf("TRAIT:Show:1"), link("Show for"))

    fun testImplMethodsPointToTheTraitMethod() =
        assertEquals("Implements Show.show" to listOf("INSTANCE_METHOD:show:2"), link("show", nth = 1))

    fun testTraitsPointToImplementations() =
        assertEquals("Implemented by Helper" to listOf("TYPE:Helper:5"), link("Show {"))

    fun testTraitMethodsPointToImplementations() =
        assertEquals("Implemented in Helper" to listOf("INSTANCE_METHOD:show:8"), link("show"))

    fun testTypesPointToTheirTraits() =
        assertEquals("Implements Show" to listOf("TRAIT:Show:1"), link("Helper {}"))

    fun testNothingOnOtherNames() {
        assertNull(link("String"))
        assertNull(link("Helper {\n")) // `impl Show for Helper` uses Helper; it doesn't declare it.
    }

    fun testIconsAppearInTheGutter() {
        val tooltips = myFixture.findAllGutters().mapNotNull { it.tooltipText }.toSet()
        assertTrue(tooltips.toString(), tooltips.containsAll(setOf("Implements trait Show", "Implemented by Helper", "Implements Show")))
    }
}
