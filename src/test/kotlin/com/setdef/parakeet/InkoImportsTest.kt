package com.setdef.parakeet

import junit.framework.TestCase

class InkoImportsTest : TestCase() {
    private val text = "import std.stdio (Stdout, Stderr as Err)\nimport app.util\nimport std.fmt (self as format)\n\nfn a {}\n"
    private val imports = InkoImports.parse(text)

    fun testModuleAliases() {
        assertEquals("app.util", imports.moduleAliasedAs("util"))
        assertEquals("std.fmt", imports.moduleAliasedAs("format"))
        assertNull(imports.moduleAliasedAs("stdio"))
    }

    fun testSymbols() {
        assertEquals("std.stdio" to "Stdout", imports.symbol("Stdout"))
        assertEquals("std.stdio" to "Stderr", imports.symbol("Err"))
        assertNull(imports.symbol("Stderr"))
        assertEquals(setOf("Stdout", "Stderr", "self"), imports.symbolNames)
    }

    fun testLocatesStatements() {
        assertEquals("std.stdio", imports.at(text.indexOf("Stdout"))?.module)
        assertTrue(imports.at(text.indexOf("Stdout"))!!.isInSymbols(text.indexOf("Stdout")))
        assertFalse(imports.at(text.indexOf("stdio"))!!.isInSymbols(text.indexOf("stdio")))
        assertNull(imports.at(text.indexOf("fn a")))
    }
}
