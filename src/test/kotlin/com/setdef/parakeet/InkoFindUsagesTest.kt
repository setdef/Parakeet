package com.setdef.parakeet

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.usageView.UsageInfo
import java.nio.file.Files

/** Find Usages and Rename, with `inko doc` output supplied by a fake. */
class InkoFindUsagesTest : InkoTestCase(onDisk = true) {
    private val util = """
        |type pub Helper {
        |  let @count: Int
        |
        |  fn pub static new -> Helper {
        |    Helper(count: 0)
        |  }
        |
        |  fn pub value -> Int {
        |    @count
        |  }
        |}
        |
        |type pub Other {
        |  let @count: Int
        |
        |  fn pub value -> Int {
        |    @count
        |  }
        |}
        |
        |fn pub helper -> Int {
        |  42
        |}
        |""".trimMargin()

    private val main = """
        |import app.util (Helper, Other, helper)
        |
        |fn run(x: Int) -> Int {
        |  let h: Helper = Helper.new
        |  let total = helper + x
        |  h.value
        |  x.value
        |  total
        |}
        |""".trimMargin()

    private fun inUtil(marker: String, shift: Int = 0) = At("src/app/util.inko", util, marker, shift)
    private fun inMain(marker: String, shift: Int = 0) = At("src/main.inko", main, marker, shift)

    /** Cuttlebone's answers for this code, as the compiler resolves it. `x.value` doesn't: `Int` has no `value`. */
    private fun compiler() = Compiler()
        .resolves(inUtil("new -> Helper", 7), inUtil("Helper {"))
        .resolves(inUtil("Helper(count"), inUtil("Helper {"))
        .resolves(inUtil("count: 0"), inUtil("@count: Int"))
        .resolves(inUtil("@count\n  }\n}\n\ntype"), inUtil("@count: Int"))
        .resolves(inUtil("@count\n  }\n}\n\nfn"), At("src/app/util.inko", util, "@count: Int", nth = 1))
        .resolves(inMain("Helper = "), inUtil("Helper {"))
        .resolves(inMain("Helper.new"), inUtil("Helper {"))
        .resolves(inMain("new\n"), inUtil("new -> Helper"))
        .resolves(inMain("helper +"), inUtil("helper ->"))
        .resolves(inMain("value\n  x"), inUtil("value ->"))

    override fun setUp() {
        super.setUp()
        compiler().install()
        // Like a real project: the files are in the project's content, so project-wide searches see them.
        putTempDirInProjectContent()
        myFixture.addFileToProject("src/app/util.inko", util)
        myFixture.addFileToProject("src/main.inko", main)
        useFakeInko(FakeInkoDoc.cli { mirror ->
            val file = mirror.resolve("src/app/util.inko")
            listOf(
                FakeInkoDoc.module(
                    "app.util", file,
                    methods = listOf(FakeInkoDoc.member("helper", file, 21, 1)),
                    types = listOf(
                        FakeInkoDoc.type(
                            "Helper", 1,
                            fields = listOf(FakeInkoDoc.member("count", file, 2)),
                            staticMethods = listOf(FakeInkoDoc.member("new", file, 4)),
                            instanceMethods = listOf(FakeInkoDoc.member("value", file, 8)),
                        ),
                        FakeInkoDoc.type(
                            "Other", 13,
                            fields = listOf(FakeInkoDoc.member("count", file, 14)),
                            instanceMethods = listOf(FakeInkoDoc.member("value", file, 16)),
                        ),
                    ),
                ),
            )
        })
    }

    private fun open(path: String) = myFixture.configureFromTempProjectFile(path)

    /** The declaration of the [nth] occurrence of [marker] in [path]. */
    private fun declaration(path: String, marker: String, nth: Int = 0): InkoDeclaration {
        open(path)
        val text = myFixture.file.text
        var index = -1
        repeat(nth + 1) { index = text.indexOf(marker, index + 1) }
        return PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(index), InkoDeclaration::class.java, false)
            ?: error("no declaration at '$marker' in $path")
    }

    private fun UsageInfo.describe(): String {
        val document = PsiDocumentManagerHelper.document(element!!)
        val offset = element!!.textRange.startOffset + (rangeInElement?.startOffset ?: 0)
        return "${element!!.containingFile.name}:${document.getLineNumber(offset) + 1}"
    }

    private fun usagesOf(element: PsiElement): List<String> = myFixture.findUsages(element).map { it.describe() }.sorted()

    fun testWhereCuttleboneAnswersThereAreNoPossibleUsages() {
        val usages = myFixture.findUsages(declaration("src/app/util.inko", "value ->"))
        assertEquals(mapOf("main.inko:6" to null), usages.associate { it.describe() to InkoUsageTypeProvider().getUsageType(it.element!!) })
    }

    fun testRenameDoesntTouchWhatCuttleboneCantCheck() {
        compiler().editing(inMain("value\n  total")).install()
        try {
            myFixture.renameElement(declaration("src/app/util.inko", "value ->"), "amount")
            fail("Expected a conflict")
        } catch (e: com.intellij.refactoring.BaseRefactoringProcessor.ConflictsInTestsException) {
            assertEquals(listOf("Cuttlebone can't check the code at main.inko:7 (it doesn't type check), so `value` there isn't renamed."), e.messages.toList())
        }
    }

    fun testAFunctionAcrossFiles() = assertEquals(listOf("main.inko:1", "main.inko:5"), usagesOf(declaration("src/app/util.inko", "helper ->")))

    fun testATypeIncludingImportsAndStaticCalls() = assertEquals(
        listOf("main.inko:1", "main.inko:4", "main.inko:4", "util.inko:4", "util.inko:5"),
        usagesOf(declaration("src/app/util.inko", "Helper {")),
    )

    private val aliased = "import app.util (Helper as Assistant)\n\nfn make -> Assistant {\n  Assistant.new\n}\n"

    /** A module importing Helper under another name, as inko-syntax's lexers import the trait `Lexer as LexerTrait`. */
    private fun withAlias() {
        myFixture.addFileToProject("src/alias.inko", aliased)
        fun inAlias(marker: String) = At("src/alias.inko", aliased, marker)
        compiler()
            .resolves(inAlias("Helper as"), inUtil("Helper {"))
            .resolves(inAlias("Assistant)"), inUtil("Helper {"))
            .resolves(inAlias("Assistant {"), inUtil("Helper {"))
            .resolves(inAlias("Assistant.new"), inUtil("Helper {"))
            .install()
    }

    fun testATypesUsesUnderAnAlias() {
        withAlias()
        assertEquals(
            listOf("alias.inko:1", "alias.inko:1", "alias.inko:3", "alias.inko:4", "main.inko:1", "main.inko:4", "main.inko:4", "util.inko:4", "util.inko:5"),
            usagesOf(declaration("src/app/util.inko", "Helper {")),
        )
    }

    // The alias stays: only the name it imports changes.
    fun testRenameATypeImportedUnderAnAlias() {
        withAlias()
        myFixture.renameElement(declaration("src/app/util.inko", "Helper {"), "Aide")
        assertEquals(aliased.replace("Helper", "Aide"), PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("src/alias.inko"))!!.text)
    }

    private val boxed = "type pub Box {\n  fn pub static new -> Self {\n    Self()\n  }\n}\n\nfn make -> Box {\n  Box.new\n}\n"

    /** A type its own methods name as `Self`, which Cuttlebone lists as uses of the type (kvi's `ReaderWriter`). */
    private fun withSelf() {
        myFixture.addFileToProject("src/box.inko", boxed)
        fun inBox(marker: String, nth: Int = 0) = At("src/box.inko", boxed, marker, nth = nth)
        compiler()
            .resolves(inBox("Self {"), inBox("Box {"))
            .resolves(inBox("Self()"), inBox("Box {"))
            .resolves(inBox("Box {\n  Box"), inBox("Box {"))
            .resolves(inBox("Box.new"), inBox("Box {"))
            .install()
    }

    fun testATypesUsesIncludeSelf() {
        withSelf()
        assertEquals(listOf("box.inko:2", "box.inko:3", "box.inko:7", "box.inko:8"), usagesOf(declaration("src/box.inko", "Box {")))
    }

    // `Self` names whatever type it's in, so it stays `Self`.
    fun testRenameATypeLeavesSelf() {
        withSelf()
        myFixture.renameElement(declaration("src/box.inko", "Box {"), "Crate")
        assertEquals(boxed.replace("Box", "Crate"), PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("src/box.inko"))!!.text)
    }

    fun testALocalStaysInItsFile() = assertEquals(listOf("main.inko:8"), usagesOf(declaration("src/main.inko", "total =")))

    fun testAParameter() = assertEquals(listOf("main.inko:5", "main.inko:7"), usagesOf(declaration("src/main.inko", "x: Int")))

    // The constructor's `count:` sets the field by name: the real cuttlebone lists it too (util.inko:5:11).
    fun testAFieldOnlyInItsOwnType() = assertEquals(listOf("util.inko:5", "util.inko:9"), usagesOf(declaration("src/app/util.inko", "@count")))

    fun testAModulesImports() {
        val util = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("src/app/util.inko"))!!
        assertEquals(listOf("main.inko:1"), usagesOf(util))
    }

    // In code being edited, where Cuttlebone can't check `x.value`, Parakeet's reading says it could be Helper's or Other's.
    fun testInCodeCuttleboneCantCheckCallsArePossibleUsages() {
        compiler().editing(inMain("value\n  total")).install()
        val usages = myFixture.findUsages(declaration("src/app/util.inko", "value ->"))
        val types = usages.associate { it.describe() to InkoUsageTypeProvider().getUsageType(it.element!!) }
        assertEquals(mapOf("main.inko:6" to null, "main.inko:7" to InkoUsageTypeProvider.POSSIBLE), types)
    }

    // Since milestone 8, references answer for one declaration (`helper`, which no code Cuttlebone can't check
    // names) while another's (`value`'s) have none: having had the first doesn't make `x.value` certain.
    fun testReferencesForOneDeclarationDontMakeAnothersUsagesCertain() {
        compiler().editing(inMain("value\n  total")).answersReferences(inUtil("helper ->")).install()
        assertEquals(listOf("main.inko:1", "main.inko:5"), usagesOf(declaration("src/app/util.inko", "helper ->")))
        val usages = myFixture.findUsages(declaration("src/app/util.inko", "value ->"))
        val types = usages.associate { it.describe() to InkoUsageTypeProvider().getUsageType(it.element!!) }
        assertEquals(mapOf("main.inko:6" to null, "main.inko:7" to InkoUsageTypeProvider.POSSIBLE), types)
    }

    fun testRenameALocalInPlace() {
        open("src/main.inko")
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("total ="))
        myFixture.renameElementAtCaret("sum")
        assertEquals(main.replace("total", "sum"), myFixture.file.text)
    }

    // kvi's `let arguments = arguments`: the value is std.env's `arguments`, which renaming the local leaves alone.
    fun testRenameALocalNamedLikeItsValue() {
        myFixture.configureByText("run.inko", "import std.env (arguments)\n\nfn run -> Int {\n  let <caret>arguments = arguments\n  arguments.size\n}\n")
        myFixture.renameElementAtCaret("args")
        assertEquals("import std.env (arguments)\n\nfn run -> Int {\n  let args = arguments\n  args.size\n}\n", myFixture.file.text)
    }

    // idoc's `Method(module: module, …)`: the label names the field, not the local, so renaming the local leaves it.
    fun testRenameALocalPassedToALabelOfItsName() {
        myFixture.configureByText("run.inko", "type Page {\n  let @module: String\n}\n\nfn run -> Page {\n  let <caret>module = 'a'\n  Page(module: module)\n}\n")
        myFixture.renameElementAtCaret("name")
        assertEquals("type Page {\n  let @module: String\n}\n\nfn run -> Page {\n  let name = 'a'\n  Page(module: name)\n}\n", myFixture.file.text)
    }

    // inko-builder's `fn node(…, escape: Bool)` calls the imported `escape(v)`: Inko calls a local closure with
    // `.call`, so `escape(` is the method, and renaming the parameter leaves it.
    fun testRenameAParameterNamedLikeAMethodItCalls() {
        myFixture.configureByText("run.inko", "import builder.xml (escape)\n\nfn node(v: String, <caret>escape: Bool) -> String {\n  if escape { escape(v) } else { v }\n}\n")
        myFixture.renameElementAtCaret("escaped")
        assertEquals("import builder.xml (escape)\n\nfn node(v: String, escaped: Bool) -> String {\n  if escaped { escape(v) } else { v }\n}\n", myFixture.file.text)
    }

    // inko-syntax's `impl Hash for Token { fn pub hash[H: mut + Hasher](hasher: mut H) }`, and yorickpeterse.com's
    // `handle(request: …)`: Inko requires a trait's parameter names, so one implementation's can't change alone.
    fun testRenameRefusesAParameterOfATraitsMethod() {
        myFixture.configureByText("num.inko", "impl Hash for Num {\n  fn pub hash[H: mut + Hasher](<caret>hasher: mut H) {\n    hasher.write_int(1)\n  }\n}\n")
        try {
            myFixture.renameElementAtCaret("h")
            fail("Expected a conflict")
        } catch (e: com.intellij.refactoring.BaseRefactoringProcessor.ConflictsInTestsException) {
            assertEquals(listOf("`hasher` is a parameter of `hash`, which implements Hash's method: Inko requires its parameter names, so it can't be renamed here alone."), e.messages.toList())
        }
    }

    fun testRenameATypeAcrossFiles() {
        myFixture.renameElement(declaration("src/app/util.inko", "Helper {"), "Assistant")
        assertEquals(util.replace("Helper", "Assistant"), myFixture.findFileInTempDir("src/app/util.inko").let { PsiManager.getInstance(project).findFile(it)!!.text })
        assertEquals(main.replace("Helper", "Assistant"), myFixture.findFileInTempDir("src/main.inko").let { PsiManager.getInstance(project).findFile(it)!!.text })
    }

    fun testRenameAFieldKeepsItsAt() {
        myFixture.renameElement(declaration("src/app/util.inko", "@count"), "total")
        val text = myFixture.file.text
        assertEquals(2, Regex("@total").findAll(text).count())
        assertTrue(text, text.contains("Helper(total: 0)"))
        assertEquals(2, Regex("@count").findAll(text).count()) // Other's field is untouched.
    }

    fun testRenameAModuleFileUpdatesImports() {
        val util = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("src/app/util.inko"))!!
        myFixture.renameElement(util, "tools.inko")
        val text = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("src/main.inko"))!!.text
        assertTrue(text, text.startsWith("import app.tools (Helper, Other, helper)"))
    }

    fun testNewNamesMustSuitWhatTheyName() {
        val validator = InkoRenameInputValidator()
        val context = com.intellij.util.ProcessingContext()
        val type = declaration("src/app/util.inko", "Helper {")
        assertTrue(validator.isInputValid("Assistant", type, context))
        assertFalse(validator.isInputValid("assistant", type, context))
        val method = declaration("src/app/util.inko", "value ->")
        assertTrue(validator.isInputValid("empty?", method, context))
        assertFalse(validator.isInputValid("Value", method, context))
        assertFalse(validator.isInputValid("type", method, context))
        val field = declaration("src/app/util.inko", "@count")
        assertTrue(validator.isInputValid("@total", field, context))
        assertTrue(validator.isInputValid("total", field, context))
    }

    fun testDeclarationsOutsideTheProjectCannotBeRenamed() {
        val dir = Files.createTempDirectory("std")
        val path = Files.writeString(dir.resolve("string.inko"), "type pub String {}\n")
        val file = PsiManager.getInstance(project).findFile(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!)!!
        val string = PsiTreeUtil.findChildrenOfType(file, InkoDeclaration::class.java).single()
        assertTrue(InkoRenameVeto().value(string))
        assertFalse(InkoRenameVeto().value(declaration("src/app/util.inko", "Helper {")))
    }

    fun testDescribesWhatEachDeclarationIs() {
        val provider = InkoFindUsagesProvider()
        assertEquals("type", provider.getType(declaration("src/app/util.inko", "Helper {")))
        assertEquals("field", provider.getType(declaration("src/app/util.inko", "@count")))
        assertEquals("method", provider.getType(declaration("src/app/util.inko", "value ->")))
        assertEquals("function", provider.getType(declaration("src/app/util.inko", "helper ->")))
        assertEquals("local variable", provider.getType(declaration("src/main.inko", "total =")))
    }
}

private object PsiDocumentManagerHelper {
    fun document(element: PsiElement) = com.intellij.psi.PsiDocumentManager.getInstance(element.project).getDocument(element.containingFile)!!
}
