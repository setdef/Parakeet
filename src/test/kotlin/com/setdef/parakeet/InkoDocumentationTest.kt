package com.setdef.parakeet

import com.intellij.psi.PsiElement
import java.nio.file.Path

/** Quick documentation. */
class InkoDocumentationTest : InkoTestCase(onDisk = true) {
    // The doc comments are read from the code, as `inko doc` reads them.
    private val util = "# Helps with **everything** & more.\ntype pub Helper {\n  # The `value`.\n  fn pub value -> Int {\n    42\n  }\n}\n"
    private val main = "import app.util (Helper)\n\nfn a(h: Helper) -> Int {\n  h.value\n}\n"

    override fun setUp() {
        super.setUp()
        // Parakeet's own reading of the code, which stands in only where Cuttlebone can't check it.
        cuttleboneCantCheckTheCode()
        myFixture.addFileToProject("src/app/util.inko", util)
        useFakeInko(FakeInkoCli { FakeInkoCli.ok(stderr = "[]") })
    }

    /** The documentation for the name at [marker] in [path] (whose text is [text]). */
    private fun docsAt(path: String, text: String, marker: String): String? {
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject(path, text).virtualFile)
        val original: PsiElement = myFixture.file.findElementAt(text.indexOf(marker))!!
        val name = original.parent as InkoName
        val target = InkoResolver.resolve(name).firstOrNull() ?: name
        val documentation = InkoDocumentationTargetProvider().documentationTarget(target, original) as InkoDocumentationTarget?
        return documentation?.html()
    }

    fun testTypeShowsSignatureLocationAndRenderedMarkdown() {
        val html = docsAt("src/main.inko", main, "Helper)")!!
        assertTrue(html, html.contains("<code>type pub Helper</code>"))
        assertTrue(html, html.contains("<code>app.util</code>"))
        assertTrue(html, html.contains("<b>everything</b>") || html.contains("<strong>everything</strong>"))
        assertTrue(html, html.contains("&amp; more"))
    }

    fun testMethodShowsItsOwner() {
        val html = docsAt("src/main.inko", main, "value")!!
        assertTrue(html, html.contains("<code>fn pub value -&gt; Int</code>"))
        assertTrue(html, html.contains("<code>app.util.Helper</code>"))
        assertTrue(html, html.contains("<code>value</code>"))
    }

    fun testWorksOnTheDeclarationItself() {
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("src/app/util.inko"))
        // The method's name, not the doc comment above it that mentions it.
        val leaf = myFixture.file.findElementAt(util.indexOf("value ->"))!!
        val html = (InkoDocumentationTargetProvider().documentationTarget(leaf.parent, leaf) as InkoDocumentationTarget).html()
        assertTrue(html, html.contains("fn pub value -&gt; Int"))
    }

    fun testNothingForUnknownNames() {
        assertNull(docsAt("src/main.inko", "fn a {\n  let local = 1\n  local\n}\n", "local\n}"))
    }
}
