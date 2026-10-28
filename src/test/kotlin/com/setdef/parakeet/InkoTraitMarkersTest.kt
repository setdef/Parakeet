package com.setdef.parakeet

import com.intellij.psi.PsiElement

/** Gutter icons and the links behind them, from Cuttlebone's answers (the type database's) and, up to a trait in the gutter, the code. */
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

    private fun at(marker: String, nth: Int = 0) = At("src/app/util.inko", util, marker, nth = nth)

    override fun setUp() {
        super.setUp()
        Compiler()
            .resolves(at("Show for"), at("Show {"))
            .answers("textDocument/declaration", at("show", nth = 1)) { listOf(location(at("show"))) }
            .answers("textDocument/implementation", at("Show {")) { listOf(location(at("Helper {}"))) }
            .answers("textDocument/implementation", at("show")) { listOf(location(at("show", nth = 1))) }
            .answers("typeHierarchy/supertypes", at("Helper {}")) { listOf(location(at("Show {"))) }
            .install()
        myFixture.addFileToProject("src/app/util.inko", util)
        useFakeInko(FakeInkoCli { FakeInkoCli.ok(stderr = "[]") })
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("src/app/util.inko"))
    }

    private fun token(marker: String, nth: Int = 0): PsiElement {
        var index = -1
        repeat(nth + 1) { index = util.indexOf(marker, index + 1) }
        assertTrue("'$marker' isn't in the file", index >= 0)
        return myFixture.file.findElementAt(index)!!
    }

    private fun link(marker: String, nth: Int = 0): Pair<String, List<String>>? = InkoTraitLinks.of(token(marker, nth))?.let { link ->
        link.tooltip to link.targets.map { "${it.text}:${myFixture.editor.document.getLineNumber(it.textOffset) + 1}" }
    }

    fun testImplPointsToTheTrait() =
        assertEquals("Implements trait Show" to listOf("Show:1"), link("Show for"))

    fun testImplMethodsPointToTheTraitMethod() =
        assertEquals("Implements Show.show" to listOf("show:2"), link("show", nth = 1))

    fun testTraitsPointToImplementations() =
        assertEquals("Implemented by Helper" to listOf("Helper:5"), link("Show {"))

    fun testTraitMethodsPointToImplementations() =
        assertEquals("Implemented in Helper" to listOf("show:8"), link("show"))

    fun testTypesPointToTheirTraits() =
        assertEquals("Implements Show" to listOf("Show:1"), link("Helper {}"))

    fun testNothingOnOtherNames() {
        assertNull(link("String"))
        assertNull(link("Helper {\n")) // `impl Show for Helper` uses Helper; it doesn't declare it.
    }

    // In the gutter, links up to the trait come from the code (a request per method on every pass would be too many),
    // and a type has none: Go to Super Method finds its traits.
    fun testIconsAppearInTheGutter() {
        val tooltips = myFixture.findAllGutters().mapNotNull { it.tooltipText }.toSet()
        assertTrue(tooltips.toString(), tooltips.containsAll(setOf("Implements trait Show", "Implemented by Helper", "Implemented in Helper", "Implements Show.show")))
        assertFalse(tooltips.toString(), "Implements Show" in tooltips)
    }
}
