package com.setdef.parakeet

import com.intellij.execution.filters.FileHyperlinkInfo
import java.nio.file.Files
import java.nio.file.Path

/**
 * Compiler line/column positions count extended grapheme clusters (Inko's
 * lexer advances columns by `graphemes(true)`), not UTF-16 code units. The
 * columns below are what `inko check --format json` 0.21.1 reported for this
 * file, which is the same with LF and CRLF line endings.
 */
class InkoCompilerPositionsTest : InkoTestCase(onDisk = true) {
    private val lines = listOf(
        "fn pub a -> Int {",
        "  let s = '👩‍👩‍👦' + nope1", // A ZWJ sequence: one grapheme, eight UTF-16 units.
        "  let t = 'éé' + nope2", // Combining accents: two graphemes, four code points.
        "  let u = '漢字テスト' + nope3", // CJK: one grapheme and one UTF-16 unit each.
        "\tlet v = '\t' + nope4", // Tabs: one column each.
        "  let w = '🇳🇱🇯🇵' + nope5", // Flags: two regional indicators (four UTF-16 units) each.
        "  1",
        "}",
    )

    /** (line, start column, end column) per `nope` name, as inko reported them. */
    private val reported = listOf(Triple(2, 17, 21), Triple(3, 18, 22), Triple(4, 21, 25), Triple(5, 16, 20), Triple(6, 18, 22))

    private val text = lines.joinToString("\n") + "\n"

    fun testCompilerColumnsAreGraphemeClusters() {
        myFixture.configureByText("a.inko", text)
        val document = myFixture.editor.document
        for ((index, position) in reported.withIndex()) {
            val (line, start, end) = position
            val name = "nope${index + 1}"
            val from = Inko.offsetOf(document, line, start)
            // The end column is inclusive, so the range ends where the next column starts.
            val to = Inko.offsetOf(document, line, end + 1)
            assertEquals("line $line", name, document.getText(com.intellij.openapi.util.TextRange(from, to)))
        }
    }

    fun testColumnsPastTheLineStayOnIt() {
        myFixture.configureByText("a.inko", text)
        val document = myFixture.editor.document
        assertEquals(document.getLineEndOffset(1), Inko.offsetOf(document, 2, 500))
        assertEquals(document.getLineStartOffset(1), Inko.offsetOf(document, 2, 0))
    }

    // Console links (`file.inko:LINE:COLUMN` in compiler output) use the same columns.
    fun testConsoleLinksUseCompilerColumns() {
        val root = Path.of(myFixture.tempDirPath)
        val crlf = Files.createDirectories(root.resolve("src")).resolve("b.inko")
        Files.writeString(crlf, lines.joinToString("\r\n") + "\r\n")
        val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
            .getDocument(com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(crlf)!!)!!
        for ((index, position) in reported.withIndex()) {
            val (line, start, _) = position
            val output = "src/b.inko:$line:$start error(invalid-symbol): the symbol 'nope${index + 1}' is undefined\n"
            val item = InkoFileLinkFilter(project, root).applyFilter(output, output.length)!!.resultItems.single()
            val offset = (item.hyperlinkInfo as FileHyperlinkInfo).descriptor!!.offset
            assertEquals("line $line", "nope${index + 1}", document.charsSequence.substring(offset, offset + 5))
        }
    }
}
