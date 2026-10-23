package com.setdef.parakeet

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.psi.util.PsiTreeUtil

/**
 * `import a.b (self)` imports the module as `b` (compiler/src/type_check/imports.rs:
 * `self` without `as` takes the module's last segment). AUDIT.md finding 1.9.
 */
class InkoImportSelfTest : InkoTestCase() {
    private val text = "import std.fmt (self, Format)\n\nfn show(v: Int) -> String {\n  fmt.fmt(v)\n}\n"

    fun testSelfImportsTheModuleUnderItsLastName() {
        val imports = InkoImports.parse(text)
        assertEquals("std.fmt", imports.moduleAliasedAs("fmt"))
        assertNull(imports.moduleAliasedAs("self"))
        assertEquals("std.fmt", InkoImports.parse("import std.fmt (self as f)\n").moduleAliasedAs("f"))
    }

    fun testTheModulesNameBeforeADotIsAModuleReference() {
        myFixture.configureByText("main.inko", text)
        val qualifier = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(text.indexOf("fmt.fmt")), InkoName::class.java, false)!!
        assertEquals(InkoNameContext.ModuleReference("std.fmt"), InkoNameContext.of(qualifier, InkoImports.parse(text)))
        val member = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(text.indexOf("fmt(v)")), InkoName::class.java, false)!!
        assertEquals(InkoNameContext.ModuleMember("std.fmt", "fmt"), InkoNameContext.of(member, InkoImports.parse(text)))
    }

    fun testRemovingAnotherSymbolKeepsSelfAsWritten() {
        val document = DocumentImpl(text)
        WriteCommandAction.runWriteCommandAction(project) {
            InkoImportEdits.removeSymbol(document, InkoImports.parse(text).statements.single(), "Format")
        }
        assertTrue(document.text, document.text.startsWith("import std.fmt (self)\n"))
    }
}
