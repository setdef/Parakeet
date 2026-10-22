package com.setdef.parakeet

import com.intellij.codeInsight.folding.CodeFoldingSettings
import java.nio.file.Files

class InkoFoldingTest : InkoTestCase() {
    // <fold> marks each region, with its placeholder text.
    private val expected = """
        |<fold text='# Licensed under the MPL. ...'># Licensed under the MPL.
        |# See LICENSE.</fold>
        |
        |<fold text='import ...'>import std.fmt (Format)
        |import std.stdio (
        |  Stdout,
        |)</fold>
        |
        |type pub Helper <fold text='{...}'>{
        |  # Just one comment line.
        |  fn pub value -> Int <fold text='{...}'>{
        |    42
        |  }</fold>
        |
        |  fn pub one -> Int { 1 }
        |}</fold>
        |""".trimMargin()

    fun testFoldsBracesCommentsAndImports() {
        val file = Files.createTempFile("folding", ".inko")
        try {
            Files.writeString(file, expected)
            myFixture.testFolding(file.toString())
        } finally {
            Files.delete(file)
        }
    }

    // (The editor never collapses the region holding the caret, so this checks the builder directly.)
    fun testCollapsesImportsAndTheFileHeaderPerSettings() {
        val settings = CodeFoldingSettings.getInstance()
        val (header, imports) = settings.COLLAPSE_FILE_HEADER to settings.COLLAPSE_IMPORTS
        try {
            myFixture.configureByText("main.inko", expected.replace(Regex("</?fold[^>]*>"), ""))
            fun collapsed() = InkoFoldingBuilder().buildFoldRegions(myFixture.file, myFixture.editor.document, false)
                .filter { it.isCollapsedByDefault == true }.map { it.placeholderText }

            settings.COLLAPSE_FILE_HEADER = true
            settings.COLLAPSE_IMPORTS = true
            assertEquals(listOf("# Licensed under the MPL. ...", "import ..."), collapsed())

            settings.COLLAPSE_FILE_HEADER = false
            settings.COLLAPSE_IMPORTS = false
            assertEmpty(collapsed())
        } finally {
            settings.COLLAPSE_FILE_HEADER = header
            settings.COLLAPSE_IMPORTS = imports
        }
    }
}
