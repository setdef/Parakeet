package com.setdef.parakeet

import junit.framework.TestCase

/**
 * `inko check --format json` writes each string with Rust's `{:?}`
 * (compiler/src/presenters.rs), whose escapes aren't all JSON: `\u{1}`,
 * `\u{1f600}`, `\0`.
 */
class InkoDiagnosticsParseTest : TestCase() {
    // What inko 0.21.1 printed for a project with a syntax error in b.inko and an invalid escape in a.inko.
    private val real = """[{"id": "invalid-syntax", "level": "error", "file": "/p/src/b.inko", "lines": [3, 3], "columns": [1, 1], "message": "'}' can't be used here"},""" +
        """{"id": "invalid-syntax", "level": "error", "file": "/p/src/a.inko", "lines": [2, 2], "columns": [4, 5], "message": "the escape sequence '\\\u{1}' is invalid"}]"""

    fun testKeepsEveryDiagnosticFromInkosRealOutput() {
        val diagnostics = InkoCheck.parse(real)
        assertEquals(listOf("/p/src/b.inko", "/p/src/a.inko"), diagnostics.map { it.file })
        assertEquals("the escape sequence '\\\u0001' is invalid", diagnostics[1].message)
    }

    fun testDecodesRustEscapes() {
        val json = """[{"id": "x", "level": "warning", "file": "/p/\u{1f600}\0.inko", "lines": [1, 1], "columns": [1, 2], "message": "a\tb\n\u{7f} \"q\" \\"}]"""
        val diagnostic = InkoCheck.parse(json).single()
        assertEquals("/p/😀\u0000.inko", diagnostic.file)
        assertEquals("a\tb\n\u007f \"q\" \\", diagnostic.message)
    }

    fun testOneUnreadableDiagnosticDoesNotDropTheOthers() {
        val good = """{"id": "x", "level": "error", "file": "/p/a.inko", "lines": [1, 1], "columns": [1, 2], "message": "first"}"""
        val bad = """{"id": "x", "level": "error", "file": "/p/b.inko", "lines": [oops], "columns": [1, 2], "message": "broken"}"""
        val also = """{"id": "x", "level": "error", "file": "/p/c.inko", "lines": [2, 2], "columns": [1, 2], "message": "has } and { inside"}"""
        assertEquals(listOf("first", "has } and { inside"), InkoCheck.parse("[$good,$bad,$also]").map { it.message })
    }

    fun testOtherOutputHasNoDiagnostics() {
        assertTrue(InkoCheck.parse("error: this project requires Inko 9.0.0 or newer").isEmpty())
        assertTrue(InkoCheck.parse("").isEmpty())
    }
}
