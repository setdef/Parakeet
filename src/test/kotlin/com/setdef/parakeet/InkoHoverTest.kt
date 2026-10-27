package com.setdef.parakeet

import junit.framework.TestCase
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.MarkupContent

/**
 * The type in Cuttlebone's hover, which has up to three
 * sections (Cuttlebone's docs/lsp.md, "textDocument/hover"): the type, the declaration, and its doc comment.
 * The examples are as Cuttlebone writes them (src/lsp.rs, `hover`).
 */
class InkoHoverTest : TestCase() {
    private fun markdown(value: String) = InkoHover.type(Hover(MarkupContent("markdown", value)))
    private fun plain(value: String) = InkoHover.type(Hover(MarkupContent("plaintext", value)))

    fun testSince012TheTypeIsTheFirstSection() {
        assertEquals(
            "Result[ref Int, OutOfBounds]",
            markdown(
                "```inko\nResult[ref Int, OutOfBounds]\n```\n\n---\n\n```inko\nfn pub get(index: Int) -> Result[ref T, OutOfBounds]\n```" +
                    "\n\nDefined in `std.array.Array`\n\n---\n\nReturns an immutable reference to the value at the given index.\n\n---\n\nMore.",
            ),
        )
        // A variable has no declaration section; a module's is `Module` and its name.
        assertEquals("Int", markdown("```inko\nInt\n```"))
        assertEquals("Int", markdown("```inko\nInt\n```\n\n---\n\nModule `std.int`"))
    }

    // A declaration's own name (`get` in `fn pub get`) is in no expression: the declaration is the first section.
    fun testWithoutATypeTheDeclarationIsntTakenForOne() {
        assertNull(markdown("```inko\nfn pub get(index: Int) -> Result[ref T, OutOfBounds]\n```\n\nDefined in `std.array.Array`\n\n---\n\nReturns it."))
        assertNull(markdown("```inko\nfn pub static new(\n  a: Int,\n  b: Int,\n) -> Self\n```\n\nDefined in `app.Point`"))
        assertNull(markdown("Module `std.array`\n\n---\n\nArrays."))
    }

    // A client asking for plain text gets the same sections without code blocks, a blank line apart.
    fun testPlainText() {
        assertEquals("Int", plain("Int"))
        assertEquals("Result[ref Int, OutOfBounds]", plain("Result[ref Int, OutOfBounds]\n\nfn pub get(index: Int) -> Result[ref T, OutOfBounds]\n\nDefined in std.array.Array\n\nReturns it."))
        assertNull(plain("fn pub get(index: Int) -> Result[ref T, OutOfBounds]\n\nDefined in std.array.Array\n\nReturns it."))
        assertNull(plain("Module std.array"))
    }
}
