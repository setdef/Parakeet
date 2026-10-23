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
}
