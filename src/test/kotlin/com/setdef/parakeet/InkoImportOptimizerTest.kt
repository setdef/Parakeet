package com.setdef.parakeet

import com.intellij.lang.ImportOptimizer
import com.intellij.openapi.command.WriteCommandAction

/** Optimize Imports, with `inko check` and `inko fmt` supplied by a fake. */
class InkoImportOptimizerTest : InkoTestCase(onDisk = true) {
    /** A fake reporting [unused] symbols (name to its line, 1-based) as unused, and sorting import lines for fmt. */
    private fun fake(vararg unused: Pair<String, Int>) = FakeInkoCli { call ->
        when (call.args.first()) {
            "check" -> {
                val file = call.args.last()
                val text = java.nio.file.Files.readString(java.nio.file.Path.of(file))
                FakeInkoCli.failed(unused.joinToString(",", "[", "]") { (name, line) ->
                    val column = text.lines()[line - 1].indexOf(name) + 1
                    """{"id": "unused-symbol", "level": "warning", "file": "$file", "lines": [$line, $line], "columns": [$column, ${column + name.length - 1}], "message": "the symbol '$name' is unused"}"""
                })
            }
            "fmt" -> FakeInkoCli.ok(stdout = call.stdin!!.lines().filter { it.isNotBlank() }.sorted().joinToString("\n") + "\n")
            else -> FakeInkoCli.ok()
        }
    }

    private fun optimize(text: String): Pair<String, String?> {
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("src/main.inko", text).virtualFile)
        val runnable = InkoImportOptimizer().processFile(myFixture.file)
        WriteCommandAction.runWriteCommandAction(project, runnable)
        return myFixture.editor.document.text to (runnable as? ImportOptimizer.CollectingInfoRunnable)?.userNotificationInfo
    }

    fun testRemovesUnusedImportsAndSorts() {
        useFakeInko(fake("Stderr" to 1, "Format" to 3))
        val (text, info) = optimize("import std.stdio (Stdout, Stderr)\nimport std.array (Array)\nimport std.fmt (Format)\n\nfn main {}\n")
        assertEquals("import std.array (Array)\nimport std.stdio (Stdout)\n\nfn main {}\n", text)
        assertEquals("Removed 2 unused imports, sorted imports", info)
    }

    fun testOnlySortsWhenNothingIsUnused() {
        useFakeInko(fake())
        val (text, info) = optimize("import std.stdio (Stdout)\nimport std.array (Array)\n\nfn main {}\n")
        assertEquals("import std.array (Array)\nimport std.stdio (Stdout)\n\nfn main {}\n", text)
        assertEquals("Sorted imports", info)
    }

    fun testLeavesTidyImportsAlone() {
        useFakeInko(fake())
        val source = "import std.array (Array)\nimport std.stdio (Stdout)\n\nfn main {}\n"
        assertEquals(source to null, optimize(source))
    }

    fun testDoesNotSortAroundCode() {
        useFakeInko(fake("Format" to 3))
        val (text, _) = optimize("import std.stdio (Stdout)\nlet A = 1\nimport std.fmt (Format)\n\nfn main {}\n")
        assertEquals("import std.stdio (Stdout)\nlet A = 1\n\nfn main {}\n", text)
    }

    fun testDoesNothingWithoutInko() {
        val source = "import std.stdio (Stdout)\nimport std.array (Array)\n"
        assertEquals(source, optimize(source).first)
    }
}
