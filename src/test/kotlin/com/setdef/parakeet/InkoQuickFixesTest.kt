package com.setdef.parakeet

import java.nio.file.Files
import java.nio.file.Path

/** Quick fixes for inko check's warnings (shaped like the real compiler's). */
class InkoQuickFixesTest : InkoTestCase(onDisk = true) {
    /** Diagnostics to report: (id, text to cover, message). Each covers the first occurrence of its text. */
    private var reported: List<Triple<String, String, String>> = emptyList()

    override fun setUp() {
        super.setUp()
        useFakeInko(FakeInkoCli { call ->
            if (call.args.first() != "check") return@FakeInkoCli FakeInkoCli.ok()
            val copy = Path.of(call.args.last())
            val lines = Files.readAllLines(copy)
            val diagnostics = reported.mapNotNull { (id, needle, message) ->
                val line = lines.indexOfFirst { needle in it }.takeIf { it >= 0 } ?: return@mapNotNull null
                val column = lines[line].indexOf(needle) + 1
                """{"id": "$id", "level": "warning", "file": "$copy", "lines": [${line + 1}, ${line + 1}], "columns": [$column, ${column + needle.length - 1}], "message": "$message"}"""
            }
            FakeInkoCli.ok(stderr = "[${diagnostics.joinToString(",")}]")
        })
    }

    private fun fix(text: String, fix: String, vararg diagnostics: Triple<String, String, String>) {
        reported = diagnostics.toList()
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("src/main.inko", text).virtualFile)
        myFixture.launchAction(myFixture.getAllQuickFixes().single { it.text == fix })
    }

    private fun unused(name: String, covering: String = name) = Triple("unused-symbol", covering, "the symbol '$name' is unused")

    fun testRemovesOneUnusedSymbol() {
        fix("import std.stdio (Stdout, Stderr)\n\nfn a {}\n", "Remove unused import 'Stderr'", unused("Stderr"))
        myFixture.checkResult("import std.stdio (Stdout)\n\nfn a {}\n")
    }

    fun testRemovesTheImportWithItsLastSymbol() {
        fix("import std.stdio (Stderr)\nimport std.fmt\n\nfn a {}\n", "Remove unused import 'Stderr'", unused("Stderr"))
        myFixture.checkResult("import std.fmt\n\nfn a {}\n")
    }

    fun testRemovesAnUnusedModuleImport() {
        fix("import std.stdio (Stdout)\nimport std.fmt\n\nfn a {}\n", "Remove unused import 'fmt'", unused("fmt", covering = "import std.fmt"))
        myFixture.checkResult("import std.stdio (Stdout)\n\nfn a {}\n")
    }

    fun testKeepsAliasesOfTheRemainingSymbols() {
        fix("import app.util (Show as Display, Helper)\n\nfn a {}\n", "Remove unused import 'Helper'", unused("Helper"))
        myFixture.checkResult("import app.util (Show as Display)\n\nfn a {}\n")
    }

    fun testPrefixesAnUnusedLocalWithAnUnderscore() {
        fix("fn a {\n  let unused = 1\n}\n", "Rename to _unused", unused("unused"))
        myFixture.checkResult("fn a {\n  let _unused = 1\n}\n")
    }

    fun testDiscardsAnUnusedResult() {
        fix(
            "fn a {\n  Stdout.new.print('x')\n}\n", "Discard the result with let _ =",
            Triple("unused-result", "Stdout.new.print('x')", "the result of this expression is unused"),
        )
        myFixture.checkResult("fn a {\n  let _ = Stdout.new.print('x')\n}\n")
    }

    // Ranges and messages as the real compiler reports them (see InkoIntegrationTest).
    fun testAddsAMissingField() {
        fix("fn main {\n  let p = Point(x: 1)\n}\n", "Add field 'y'", Triple("missing-field", "Point(x: 1)", "the field 'y' must be assigned a value"))
        myFixture.checkResult("fn main {\n  let p = Point(x: 1, y: <caret>)\n}\n")
    }

    fun testAddsAMissingFieldToEmptyParentheses() {
        fix("fn main {\n  let p = Point()\n}\n", "Add field 'x'", Triple("missing-field", "Point()", "the field 'x' must be assigned a value"))
        myFixture.checkResult("fn main {\n  let p = Point(x: <caret>)\n}\n")
    }

    fun testRemovesEverythingAfterAReturn() {
        // The compiler flags only the first unreachable statement.
        fix("fn early -> Int {\n  return 1\n  let a = 2\n  a + 3\n}\n", "Remove unreachable code", Triple("unreachable", "let a = 2", "this code is unreachable"))
        myFixture.checkResult("fn early -> Int {\n  return 1\n}\n")
    }

    fun testRemovesAnUnreachableMatchArm() {
        val text = "fn pick(value: Option[Int]) -> Int {\n  match value {\n    case _ -> 0\n    case Some(v) -> v\n  }\n}\n"
        fix(text, "Remove unreachable code", Triple("unreachable", "case Some(v) -> v", "this code is unreachable"))
        myFixture.checkResult("fn pick(value: Option[Int]) -> Int {\n  match value {\n    case _ -> 0\n  }\n}\n")
    }
}
