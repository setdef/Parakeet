package com.setdef.parakeet

import com.intellij.psi.PsiElement
import java.nio.file.Path

/** Quick documentation, with `inko doc` output supplied by a fake. */
class InkoDocumentationTest : InkoTestCase(onDisk = true) {
    private val util = "# Helps.\ntype pub Helper {\n  fn pub value -> Int {\n    42\n  }\n}\n"
    private val main = "import app.util (Helper)\n\nfn a(h: Helper) -> Int {\n  h.value\n}\n"

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("src/app/util.inko", util)
        useFakeInko(FakeInkoDoc.cli { mirror ->
            val file = mirror.resolve("src/app/util.inko")
            listOf(FakeInkoDoc.module(
                "app.util", file,
                types = listOf(FakeInkoDoc.type(
                    "Helper", 1, signature = "type pub Helper", docs = "Helps with **everything** & more.",
                    instanceMethods = listOf(FakeInkoDoc.member("value", file, 3, signature = "fn pub value -> Int", docs = "The `value`.")),
                )),
            ))
        })
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
        val leaf = myFixture.file.findElementAt(util.indexOf("value"))!!
        val html = (InkoDocumentationTargetProvider().documentationTarget(leaf.parent, leaf) as InkoDocumentationTarget).html()
        assertTrue(html, html.contains("fn pub value -&gt; Int"))
    }

    fun testNothingForUnknownNames() {
        assertNull(docsAt("src/main.inko", "fn a {\n  let local = 1\n  local\n}\n", "local\n}"))
    }

    fun testParserKeepsSignaturesAndDocs() {
        val definition = InkoDocParser.parse(
            FakeInkoDoc.module("m", Path.of("/m.inko"), types = listOf(FakeInkoDoc.type("T", 1, signature = "type pub T", docs = "Docs."))),
        ).single { it.name == "T" }
        assertEquals("type pub T", definition.signature)
        assertEquals("Docs.", definition.documentation)
    }
}
