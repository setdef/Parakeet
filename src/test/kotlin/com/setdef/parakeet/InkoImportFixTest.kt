package com.setdef.parakeet

import java.nio.file.Files
import java.nio.file.Path

/** Auto-import: fixes on "the symbol 'X' is undefined", from the inko doc index. */
class InkoImportFixTest : InkoTestCase(onDisk = true) {
    private val root: Path get() = Path.of(myFixture.tempDirPath)

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("src/app/util.inko", "type pub Helper {}\n\nfn pub helper -> Int {\n  1\n}\n")
        val std = Files.createDirectories(root.resolve("std")).resolve("map.inko")
        Files.writeString(std, "type pub Map {}\n")
        useFakeInko(FakeInkoCli { call ->
            when (call.args.first()) {
                "doc" -> {
                    val docs = Files.createDirectories(call.workingDirectory!!.resolve("build/docs"))
                    val util = call.workingDirectory.resolve("src/app/util.inko")
                    Files.writeString(docs.resolve("app_util.json"), FakeInkoDoc.module(
                        "app.util", util,
                        methods = listOf(FakeInkoDoc.member("helper", util, 3, 1)),
                        types = listOf(FakeInkoDoc.type("Helper", 1), FakeInkoDoc.type("Map", 1)),
                    ))
                    Files.writeString(docs.resolve("std_map.json"), FakeInkoDoc.module("std.map", std, types = listOf(FakeInkoDoc.type("Map", 1))))
                    FakeInkoCli.ok()
                }
                "check" -> {
                    // Reports each of Helper, Map, and Nowhere as undefined wherever it appears in the file.
                    val copy = Path.of(call.args.last())
                    val lines = Files.readAllLines(copy)
                    val diagnostics = lines.flatMapIndexed { index, line ->
                        Regex("""\b(Helper|Map|Nowhere)\b""").findAll(line).filter { !line.trimStart().startsWith("import") }.map {
                            """{"id": "invalid-symbol", "level": "error", "file": "$copy", "lines": [${index + 1}, ${index + 1}], "columns": [${it.range.first + 1}, ${it.range.last + 1}], "message": "the symbol '${it.value}' is undefined"}"""
                        }.toList()
                    }
                    FakeInkoCli.failed("[${diagnostics.joinToString(",")}]")
                }
                else -> FakeInkoCli.ok()
            }
        })
    }

    private fun fixesAt(text: String, marker: String): List<String> {
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("src/main.inko", text).virtualFile)
        myFixture.editor.caretModel.moveToOffset(text.indexOf(marker))
        return myFixture.getAllQuickFixes().map { it.text }.filter { it.startsWith("Import ") }
    }

    private fun apply(fix: String) {
        myFixture.launchAction(myFixture.getAllQuickFixes().single { it.text == fix })
    }

    fun testAddsANewImportAfterTheLastOne() {
        assertEquals(listOf("Import Helper from app.util"), fixesAt("import std.stdio (Stdout)\n\nfn a {\n  Helper()\n}\n", "Helper()"))
        apply("Import Helper from app.util")
        myFixture.checkResult("import std.stdio (Stdout)\nimport app.util (Helper)\n\nfn a {\n  Helper()\n}\n")
    }

    fun testAddsToAnExistingImportOfTheModule() {
        fixesAt("import app.util (helper)\n\nfn a {\n  Helper()\n}\n", "Helper()")
        apply("Import Helper from app.util")
        myFixture.checkResult("import app.util (helper, Helper)\n\nfn a {\n  Helper()\n}\n")
    }

    fun testImportsAtTheTopWithoutOtherImports() {
        fixesAt("fn a {\n  Helper()\n}\n", "Helper()")
        apply("Import Helper from app.util")
        myFixture.checkResult("import app.util (Helper)\n\nfn a {\n  Helper()\n}\n")
    }

    fun testOffersEveryDefiningModuleProjectFirst() {
        assertEquals(listOf("Import Map from app.util", "Import Map from std.map"), fixesAt("fn a {\n  Map()\n}\n", "Map()"))
    }

    fun testNoFixForNamesNothingDefines() {
        assertEmpty(fixesAt("fn a {\n  Nowhere()\n}\n", "Nowhere()"))
    }
}
