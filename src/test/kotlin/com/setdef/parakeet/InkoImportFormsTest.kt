package com.setdef.parakeet

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.psi.util.PsiTreeUtil

/**
 * Imports with build tags and `import extern` (ast/src/parser.rs: `import`,
 * `build_tags`, `extern_import`). AUDIT.md finding 1.5.
 */
class InkoImportFormsTest : InkoTestCase() {
    private fun added(text: String, module: String, name: String): String {
        val document = DocumentImpl(text)
        WriteCommandAction.runWriteCommandAction(project) { InkoImportEdits.add(document, module, name) }
        return document.text
    }

    fun testANewImportGoesAfterATaggedOneAndLeavesItsTag() = assertEquals(
        "import std.stdio (Stdout)\nimport std.sys.unix.env (self as sys) if unix\nimport app.util (Helper)\n\nfn main {}\n",
        added("import std.stdio (Stdout)\nimport std.sys.unix.env (self as sys) if unix\n\nfn main {}\n", "app.util", "Helper"),
    )

    fun testTagsWithAnd() = assertEquals(
        "import foo if linux and amd64\nimport app.util (Helper)\n",
        added("import foo if linux and amd64\n", "app.util", "Helper"),
    )

    fun testANewImportGoesAfterAnExternImport() = assertEquals(
        "import extern \"m\"\nimport app.util (Helper)\n\nfn main {}\n",
        added("import extern \"m\"\n\nfn main {}\n", "app.util", "Helper"),
    )

    // A symbol imported under a tag would only exist for that platform.
    fun testASymbolIsNotAddedToATaggedImport() = assertEquals(
        "import std.sys.unix.env (Foo) if unix\nimport std.sys.unix.env (Helper)\n",
        added("import std.sys.unix.env (Foo) if unix\n", "std.sys.unix.env", "Helper"),
    )

    fun testExternImportsNameNoModule() {
        val imports = InkoImports.parse("import extern \"m\"\nimport std.stdio (Stdout) if mac\n")
        assertEquals(2, imports.statements.size)
        assertNull(imports.moduleAliasedAs("extern"))
        assertEquals("std.stdio" to "Stdout", imports.symbol("Stdout"))
        assertNull(imports.symbol("mac"))
    }

    fun testBuildTagsAreNotImportedSymbols() {
        val text = "import std.stdio (Stdout) if unix\n"
        myFixture.configureByText("main.inko", text)
        val tag = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(text.indexOf("unix")), InkoName::class.java, false)!!
        assertEquals(InkoNameContext.BuildTag, InkoNameContext.of(tag, InkoImports.parse(text)))
    }
}
