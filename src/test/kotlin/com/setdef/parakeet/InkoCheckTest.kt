package com.setdef.parakeet

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import java.nio.file.Files
import java.nio.file.Path

class InkoCheckTest : InkoTestCase(onDisk = true) {
    private fun diagnostic(file: String, line: Int, columns: IntRange, message: String, level: String = "error") =
        """{"id": "x", "level": "$level", "file": "$file", "lines": [$line, $line], "columns": [${columns.first}, ${columns.last}], "message": "$message"}"""

    private fun problems(): List<HighlightInfo> =
        myFixture.doHighlighting().filter { it.severity >= HighlightSeverity.WEAK_WARNING && it.description != null }

    private fun open(path: String, text: String) {
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject(path, text).virtualFile)
    }

    private fun HighlightInfo.text() = myFixture.editor.document.getText(TextRange(startOffset, endOffset))

    fun testShowsErrorsAndWarningsAtTheirColumns() {
        useFakeInko(FakeInkoCli { call ->
            val file = call.args.last()
            FakeInkoCli.failed("[${diagnostic(file, 2, 7..12, "bad helper")},${diagnostic(file, 1, 1..3, "unused", "warning")}]")
        })
        open("src/main.inko", "fn a {\n  let helper = 1\n}\n")

        val byMessage = problems().associateBy { it.description }
        assertEquals("helper", byMessage.getValue("bad helper").text())
        assertEquals(HighlightSeverity.ERROR, byMessage.getValue("bad helper").severity)
        assertEquals("fn ", byMessage.getValue("unused").text())
        assertEquals(HighlightSeverity.WARNING, byMessage.getValue("unused").severity)
    }

    fun testIgnoresDiagnosticsInOtherFiles() {
        useFakeInko(FakeInkoCli { FakeInkoCli.failed("[${diagnostic("/elsewhere/other.inko", 1, 1..2, "elsewhere")}]") })
        open("src/main.inko", "fn a {}\n")
        assertEmpty(problems())
    }

    fun testChecksUnsavedTextInAMirrorOfTheWholeProject() {
        val fake = useFakeInko(FakeInkoCli { call ->
            val mirror = call.workingDirectory!!
            val copy = call.args.last()
            // Other modules (like one that imports this file) are in the mirror, and this file has the unsaved text.
            if (Files.exists(mirror.resolve("src/main.inko")) && Files.readString(Path.of(copy)).contains("unsaved")) {
                FakeInkoCli.failed("[${diagnostic(copy, 2, 3..9, "unsaved seen")}]")
            } else {
                FakeInkoCli.ok(stderr = "[]")
            }
        })
        myFixture.addFileToProject("src/main.inko", "import app.util (helper)\n")
        open("src/app/util.inko", "fn pub helper -> Int {\n  42\n}\n")
        assertEmpty(problems())

        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("42"))
        myFixture.type("unsaved + ")

        assertEquals("unsaved seen", problems().single().description)
        assertEquals(listOf("check", "--format", "json"), fake.calls.last().args.take(3))
    }

    fun testDoesNothingWithoutInko() {
        val fake = useFakeInko(FakeInkoCli(available = false))
        open("src/main.inko", "fn a {}\n")
        assertEmpty(problems())
        assertEmpty(fake.calls)
    }

    fun testParsesOnlyJsonDiagnostics() {
        assertEmpty(InkoCheck.parse("error: something went wrong"))
        assertEquals("x", InkoCheck.parse("[${diagnostic("/a.inko", 1, 1..1, "x")}]").single().message)
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
}
