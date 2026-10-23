package com.setdef.parakeet

import com.intellij.lang.annotation.HighlightSeverity

/**
 * Bool methods that report an action's success, which std names without `?`:
 * methods of `ref` types (std/src/std/sync.inko: `load`, `compare_and_swap`)
 * and functions writing into a `mut` argument (std/src/std/uri.inko: `decode`).
 * AUDIT.md finding 1.16.
 */
class InkoBoolActionsTest : InkoTestCase(onDisk = true) {
    override fun setUp() {
        super.setUp()
        putTempDirInProjectContent()
        myFixture.enableInspections(InkoBooleanMethodNameInspection())
    }

    private fun warnings(text: String): List<String> {
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("src/main.inko", text).virtualFile)
        return myFixture.doHighlighting().filter { it.severity == HighlightSeverity.WEAK_WARNING }.map { it.description }
    }

    fun testActionsLikeStdsAreLeftAlone() {
        val text = """
            |type pub ref AtomicBool {
            |  fn pub load -> Bool {
            |    true
            |  }
            |
            |  fn pub compare_and_swap(current: Bool, new: Bool) -> Bool {
            |    true
            |  }
            |}
            |
            |fn pub decode(input: String, output: mut ByteArray) -> Bool {
            |  true
            |}
            |""".trimMargin()
        assertEmpty(warnings(text))
    }

    fun testPredicatesStillNeedAQuestionMark() {
        val text = "type pub Box {\n  fn pub empty -> Bool {\n    true\n  }\n}\n\nfn pub valid(input: String) -> Bool {\n  true\n}\n"
        assertEquals(2, warnings(text).size)
    }
}
