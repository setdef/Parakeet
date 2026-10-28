package com.setdef.parakeet

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import java.nio.file.Path

/** The compiler's errors and warnings in the editor, from Cuttlebone's pull (CuttleboneIntegrationTest has the real one's). */
class InkoCheckTest : InkoTestCase(onDisk = true) {
    private fun problems(): List<HighlightInfo> =
        myFixture.doHighlighting().filter { it.severity >= HighlightSeverity.WEAK_WARNING && it.description != null }

    private fun open(path: String, text: String) {
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject(path, text).virtualFile)
    }

    private fun HighlightInfo.text() = myFixture.editor.document.getText(TextRange(startOffset, endOffset))

    fun testShowsErrorsAndWarningsAtTheirRanges() {
        stubCuttlebone { method, file, _ ->
            val diagnostics = listOf(diagnostic(1, 6, 12, "bad helper", "invalid-symbol"), diagnostic(0, 0, 3, "unused", "unused-symbol", warning = true))
            CuttleboneQueries.Answer(diagnostics.takeIf { method == "textDocument/diagnostic" }, null)
        }
        open("src/main.inko", "fn a {\n  let helper = 1\n}\n")

        val byMessage = problems().associateBy { it.description }
        assertEquals("helper", byMessage.getValue("bad helper").text())
        assertEquals(HighlightSeverity.ERROR, byMessage.getValue("bad helper").severity)
        assertEquals("fn ", byMessage.getValue("unused").text())
        assertEquals(HighlightSeverity.WARNING, byMessage.getValue("unused").severity)
    }

    fun testNothingWithoutCuttlebonesAnswer() {
        stubCuttlebone { _, _, _ -> null }
        open("src/main.inko", "fn a {}\n")
        assertEmpty(problems())
    }

    fun testProjectRoot() {
        val root = Path.of(myFixture.tempDirPath)
        myFixture.addFileToProject("src/app/util.inko", "")
        assertEquals(root, Inko.projectRoot(root.resolve("src/app/util.inko")))
        assertEquals(root, Inko.projectRoot(root.resolve("test/app/test_util.inko")))
        assertEquals(root, Inko.projectRoot(root.resolve("script.inko")))
        // A test directory without a sibling src isn't a project root.
        assertEquals(root.resolve("other/test"), Inko.projectRoot(root.resolve("other/test/a.inko")))
        assertEquals("app.util", Inko.moduleName(root.resolve("src/app/util.inko")))
    }

    companion object {
        /** A diagnostic covering the first [needle] in [text] (none if it isn't there), with the compiler's [id]. */
        fun covering(text: String, needle: String, id: String, message: String, warning: Boolean = false): org.eclipse.lsp4j.Diagnostic? {
            val at = text.indexOf(needle).takeIf { it >= 0 } ?: return null
            val line = text.substring(0, at).count { it == '\n' }
            val start = at - (text.lastIndexOf('\n', at - 1) + 1)
            return diagnostic(line, start, start + needle.length, message, id, warning)
        }

        /** A diagnostic as Cuttlebone's pull gives it: on [line], from [start] to [end] (0-based, UTF-16), with the compiler's [id]. */
        fun diagnostic(line: Int, start: Int, end: Int, message: String, id: String, warning: Boolean = false) = org.eclipse.lsp4j.Diagnostic(
            org.eclipse.lsp4j.Range(org.eclipse.lsp4j.Position(line, start), org.eclipse.lsp4j.Position(line, end)), message,
            if (warning) org.eclipse.lsp4j.DiagnosticSeverity.Warning else org.eclipse.lsp4j.DiagnosticSeverity.Error, "cuttlebone", id,
        )
    }
}
