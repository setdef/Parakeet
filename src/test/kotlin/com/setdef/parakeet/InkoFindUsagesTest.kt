package com.setdef.parakeet

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.usageView.UsageInfo
import com.intellij.usages.impl.rules.UsageType
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

    override fun setUp() {
        super.setUp()
        // Like a real project: the files are in the project's content, so project-wide searches see them.
        val root = LocalFileSystem.getInstance().refreshAndFindFileByPath(myFixture.tempDirPath)!!
        PsiTestUtil.addContentRoot(module, root)
        Disposer.register(testRootDisposable) { PsiTestUtil.removeContentEntry(module, root) }
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

    fun testAFunctionAcrossFiles() = assertEquals(listOf("main.inko:1", "main.inko:5"), usagesOf(declaration("src/app/util.inko", "helper ->")))

    fun testATypeIncludingImportsAndStaticCalls() = assertEquals(
        listOf("main.inko:1", "main.inko:4", "main.inko:4", "util.inko:4", "util.inko:5"),
        usagesOf(declaration("src/app/util.inko", "Helper {")),
    )

    fun testALocalStaysInItsFile() = assertEquals(listOf("main.inko:8"), usagesOf(declaration("src/main.inko", "total =")))

    fun testAParameter() = assertEquals(listOf("main.inko:5", "main.inko:7"), usagesOf(declaration("src/main.inko", "x: Int")))

    fun testAFieldOnlyInItsOwnType() = assertEquals(listOf("util.inko:9"), usagesOf(declaration("src/app/util.inko", "@count")))

    fun testAModulesImports() {
        val util = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("src/app/util.inko"))!!
        assertEquals(listOf("main.inko:1"), usagesOf(util))
    }

    // `h` is a Helper, but `x.value` could be Helper's or Other's (both are imported).
    fun testCallsOnUnknownTypesArePossibleUsages() {
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

    fun testRenameATypeAcrossFiles() {
        myFixture.renameElement(declaration("src/app/util.inko", "Helper {"), "Assistant")
        assertEquals(util.replace("Helper", "Assistant"), myFixture.findFileInTempDir("src/app/util.inko").let { PsiManager.getInstance(project).findFile(it)!!.text })
        assertEquals(main.replace("Helper", "Assistant"), myFixture.findFileInTempDir("src/main.inko").let { PsiManager.getInstance(project).findFile(it)!!.text })
    }

    fun testRenameAFieldKeepsItsAt() {
        myFixture.renameElement(declaration("src/app/util.inko", "@count"), "total")
        val text = myFixture.file.text
        assertEquals(2, Regex("@total").findAll(text).count())
        assertEquals(2, Regex("@count").findAll(text).count()) // Other's field is untouched.
    }

    fun testRenameAModuleFileUpdatesImports() {
        val util = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("src/app/util.inko"))!!
        myFixture.renameElement(util, "tools.inko")
        val text = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("src/main.inko"))!!.text
        assertTrue(text, text.startsWith("import app.tools (Helper, Other, helper)"))
    }

    fun testRenamingInstanceMethodsAlwaysShowsThePreview() {
        val processor = RenamePsiElementProcessor.forElement(declaration("src/app/util.inko", "value ->"))
        assertTrue(processor.forcesShowPreview())
        assertFalse(RenamePsiElementProcessor.forElement(declaration("src/app/util.inko", "new ->")).forcesShowPreview())
        assertFalse(RenamePsiElementProcessor.forElement(declaration("src/app/util.inko", "helper ->")).forcesShowPreview())
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
