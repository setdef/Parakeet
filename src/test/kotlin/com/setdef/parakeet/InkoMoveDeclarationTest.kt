package com.setdef.parakeet

import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.BaseRefactoringProcessor
import java.nio.file.Files
import java.nio.file.Path

/** Move (F6) for top-level Inko declarations. */
class InkoMoveDeclarationTest : InkoTestCase(onDisk = true) {
    override fun setUp() {
        super.setUp()
        // Cuttlebone, answering what each test's code needs (and that nothing is anywhere else).
        Compiler().install()
        putTempDirInProjectContent()
        myFixture.addFileToProject("inko.pkg", "")
        // An `inko doc` that documents nothing: the index falls back to the project's own sources, as when it doesn't compile.
        useFakeInko(FakeInkoCli { FakeInkoCli.ok(stderr = "[]") })
    }

    private fun declaration(path: String, name: String): InkoDeclaration {
        val file = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir(path))!!
        return PsiTreeUtil.findChildrenOfType(file, InkoDeclaration::class.java).first { it.name == name }
    }

    private fun move(path: String, name: String, to: String) =
        InkoMoveDeclarationProcessor(project, declaration(path, name), to).run()

    private fun text(path: String): String =
        com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(myFixture.findFileInTempDir(path))!!.text

    private val util = """
        |import std.stdio (Stdout)
        |
        |let pub LIMIT = 10
        |
        |# A thing that helps.
        |type pub Helper {
        |  let @count: Int
        |}
        |
        |impl Helper {
        |  fn pub show {
        |    Stdout.new.print(LIMIT.to_string)
        |  }
        |}
        |
        |fn pub make -> Helper {
        |  Helper(count: LIMIT)
        |}
        |""".trimMargin()

    fun testMovesATypeWithItsImplsAndUpdatesImports() {
        val main = "import app.util (Helper, make)\n\nfn main {\n  let h: Helper = make\n}\n"
        myFixture.addFileToProject("src/app/util.inko", util)
        myFixture.addFileToProject("src/main.inko", main)
        fun inUtil(marker: String) = At("src/app/util.inko", util, marker)
        Compiler()
            .resolves(inUtil("LIMIT.to_string"), inUtil("LIMIT ="))
            .resolves(inUtil("Helper {\n  Helper"), inUtil("Helper {"))
            .resolves(inUtil("Helper(count"), inUtil("Helper {"))
            .resolves(inUtil("count: LIMIT"), inUtil("@count"))
            .resolves(inUtil("LIMIT)"), inUtil("LIMIT ="))
            .resolves(At("src/main.inko", main, "Helper = "), inUtil("Helper {"))
            .resolves(At("src/main.inko", main, "make\n"), inUtil("make ->"))
            .install()
        move("src/app/util.inko", "Helper", "app.helpers")

        assertEquals(
            "import std.stdio (Stdout)\nimport app.helpers (Helper)\n\nlet pub LIMIT = 10\n\nfn pub make -> Helper {\n  Helper(count: LIMIT)\n}\n",
            text("src/app/util.inko"),
        )
        assertEquals(
            "import std.stdio (Stdout)\nimport app.util (LIMIT)\n\n# A thing that helps.\ntype pub Helper {\n  let @count: Int\n}\n\nimpl Helper {\n  fn pub show {\n    Stdout.new.print(LIMIT.to_string)\n  }\n}\n",
            text("src/app/helpers.inko"),
        )
        assertEquals("import app.util (make)\nimport app.helpers (Helper)\n\nfn main {\n  let h: Helper = make\n}\n", text("src/main.inko"))
        assertTrue(Files.isRegularFile(Path.of(myFixture.tempDirPath, "src/app/helpers.inko")))
    }

    fun testMovingIntoAModuleThatImportedItDropsThatImport() {
        myFixture.addFileToProject("src/app/util.inko", "let pub LIMIT = 10\n")
        myFixture.addFileToProject("src/app/other.inko", "import app.util (LIMIT)\n\nfn pub twice -> Int {\n  LIMIT * 2\n}\n")
        move("src/app/util.inko", "LIMIT", "app.other")
        assertEquals("", text("src/app/util.inko"))
        assertEquals("fn pub twice -> Int {\n  LIMIT * 2\n}\n\nlet pub LIMIT = 10\n", text("src/app/other.inko"))
    }

    // inko-markdown's `type pub Document` uses `markdown.parser`'s private `Parser`: private names are visible within
    // their root namespace (`markdown`, `app` here) only, so another can't import it.
    fun testMovedCodeUsingAnotherModulesPrivateNameStaysInItsNamespace() {
        myFixture.addFileToProject("src/app/parser.inko", "type Parser {}\n")
        myFixture.addFileToProject("src/app.inko", "import app.parser (Parser)\n\ntype pub Document {\n  let @parser: Parser\n}\n")
        try {
            move("src/app.inko", "Document", "other")
            fail("Expected a conflict")
        } catch (e: BaseRefactoringProcessor.ConflictsInTestsException) {
            assertEquals(listOf("The moved code uses Parser, which is private to app.parser, so other can't import it."), e.messages.toList())
        }
        move("src/app.inko", "Document", "app.docs")
        assertEquals("import app.parser (Parser)\n\ntype pub Document {\n  let @parser: Parser\n}\n", text("src/app/docs.inko"))
    }

    fun testQualifiedUsesBecomeImports() {
        myFixture.addFileToProject("src/app/util.inko", "let pub LIMIT = 10\n\nfn pub other -> Int {\n  1\n}\n")
        myFixture.addFileToProject("src/main.inko", "import app.util\n\nfn main -> Int {\n  util.LIMIT + util.other\n}\n")
        move("src/app/util.inko", "LIMIT", "app.limits")
        assertEquals("import app.util\nimport app.limits (LIMIT)\n\nfn main -> Int {\n  LIMIT + util.other\n}\n", text("src/main.inko"))
    }

    fun testPrivateDeclarationsStillUsedElsewhereConflict() {
        val util = "let LIMIT = 10\n\nfn pub twice -> Int {\n  LIMIT * 2\n}\n"
        myFixture.addFileToProject("src/app/util.inko", util)
        Compiler().resolves(At("src/app/util.inko", util, "LIMIT *"), At("src/app/util.inko", util, "LIMIT =")).install()
        try {
            move("src/app/util.inko", "LIMIT", "app.limits")
            fail("Expected a conflict")
        } catch (e: BaseRefactoringProcessor.ConflictsInTestsException) {
            assertTrue(e.messages.toString(), e.messages.any { "isn't public" in it })
        }
    }

    fun testNameClashInTheTargetConflicts() {
        myFixture.addFileToProject("src/app/util.inko", "let pub LIMIT = 10\n")
        myFixture.addFileToProject("src/app/other.inko", "let pub LIMIT = 20\n")
        try {
            move("src/app/util.inko", "LIMIT", "app.other")
            fail("Expected a conflict")
        } catch (e: BaseRefactoringProcessor.ConflictsInTestsException) {
            assertTrue(e.messages.toString(), e.messages.any { "already defines LIMIT" in it })
        }
    }

    fun testOnlyTopLevelDeclarationsMove() {
        myFixture.addFileToProject("src/app/util.inko", util)
        assertNotNull(InkoMoveDeclaration.movable(declaration("src/app/util.inko", "Helper")))
        assertNotNull(InkoMoveDeclaration.movable(declaration("src/app/util.inko", "make")))
        // Inside a type (on a field), F6 moves the type; inside an impl, nothing (it moves with its type).
        assertEquals("Helper", InkoMoveDeclaration.movable(declaration("src/app/util.inko", "count"))?.name)
        assertNull(InkoMoveDeclaration.movable(declaration("src/app/util.inko", "show")))
        assertNull(InkoMoveDeclaration.movable(declaration("src/app/util.inko", "Helper").containingFile))
    }

    fun testValidatesTheTargetModule() {
        myFixture.addFileToProject("src/app/util.inko", util)
        val helper = declaration("src/app/util.inko", "Helper")
        assertNotNull(InkoMoveDeclaration.invalidTarget(helper, "App.Things"))
        assertNotNull(InkoMoveDeclaration.invalidTarget(helper, "app.util"))
        assertNull(InkoMoveDeclaration.invalidTarget(helper, "app.things"))
    }
}
