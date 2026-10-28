package com.setdef.parakeet

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.codeInspection.InspectionManager
import com.intellij.openapi.command.WriteCommandAction

/** Style-guide inspections, with inko doc output (for resolving calls) supplied by a fake. */
class InkoStyleInspectionsTest : InkoTestCase(onDisk = true) {
    override fun setUp() {
        super.setUp()
        // Parakeet's own reading of the code, which stands in only where Cuttlebone can't check it.
        cuttleboneCantCheckTheCode()
        // Inspections only run in project content; on-disk test files aren't in the light project's content root.
        putTempDirInProjectContent()
        myFixture.enableInspections(
            InkoNamingInspection(), InkoBooleanMethodNameInspection(), InkoRedundantNamedArgumentInspection(), InkoEmptyParenthesesInspection(),
        )
        useFakeInko(FakeInkoCli { FakeInkoCli.ok(stderr = "[]") })
        myFixture.addFileToProject("src/app/text.inko", "fn pub slice(start: Int, chars: Int) -> Int {\n  start\n}\n")
    }

    private fun open(text: String) {
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("src/main.inko", text).virtualFile)
    }

    private fun warnings(): List<HighlightInfo> = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.WEAK_WARNING }

    private fun messages(text: String): List<String> {
        open(text)
        return warnings().map { "${myFixture.editor.document.getText(com.intellij.openapi.util.TextRange(it.startOffset, it.endOffset))}: ${it.description}" }
    }

    private fun fix(text: String, fixName: String): String {
        open(text)
        myFixture.doHighlighting()
        val fix = myFixture.getAllQuickFixes().firstOrNull { it.text == fixName }
        assertNotNull("No '$fixName' among ${myFixture.getAllQuickFixes().map { it.text }}", fix)
        myFixture.launchAction(fix!!)
        return myFixture.editor.document.text
    }

    fun testNamesFollowTheStyleGuide() {
        val text = """
            |let Max_size = 10
            |
            |type My_type {
            |  let @someField: Int
            |
            |  fn fooBar -> Int {
            |    let localValue = 1
            |    localValue
            |  }
            |}
            |
            |type enum Shape {
            |  case Big_circle
            |}
            |""".trimMargin()
        assertSameElements(
            messages(text),
            "Max_size: Constant name 'Max_size' should be SCREAMING_SNAKE_CASE",
            "My_type: Type name 'My_type' should be PascalCase",
            "@someField: Field name 'someField' should be snake_case",
            "fooBar: Method name 'fooBar' should be snake_case",
            "localValue: Variable name 'localValue' should be snake_case",
            "Big_circle: Constructor name 'Big_circle' should be PascalCase",
        )
    }

    fun testNamesChosenElsewhereAreLeftAlone() {
        val text = """
            |let MAX_SIZE = 10
            |
            |type pub HttpClient {
            |  let @_private_count: Int
            |}
            |
            |impl ToString for HttpClient {
            |  fn pub toString -> String {
            |    ''
            |  }
            |}
            |
            |fn extern inko_string_concat(a: Int) -> Int
            |
            |fn pub empty? -> Bool {
            |  true
            |}
            |""".trimMargin()
        assertEmpty(messages(text))
    }

    fun testRenamingFixesTheNameAndItsUses() {
        val text = "fn main {\n  let localValue = 1\n  localValue + localValue\n}\n"
        assertEquals("fn main {\n  let local_value = 1\n  local_value + local_value\n}\n", fix(text, "Rename to 'local_value'"))
    }

    fun testBoolMethodsEndInAQuestionMark() {
        val text = """
            |fn pub allowed -> Bool {
            |  true
            |}
            |
            |fn pub is_ready? -> Bool {
            |  true
            |}
            |
            |fn pub contains(value: Int) -> Bool {
            |  true
            |}
            |""".trimMargin()
        assertSameElements(
            messages(text),
            "allowed: A method returning Bool should end in ?: 'allowed?'",
            "is_ready?: Name a Bool method 'ready?' rather than prefixing it with is_",
            "contains: A method returning Bool should end in ?: 'contains?'",
        )
    }

    fun testBoolActionsAndNonBoolMethodsAreLeftAlone() {
        val text = """
            |type Set {
            |  fn mut insert(value: Int) -> Bool {
            |    true
            |  }
            |
            |  fn select(func: fn (Int) -> Bool) -> Int {
            |    0
            |  }
            |
            |  fn ==(other: ref Set) -> Bool {
            |    true
            |  }
            |}
            |
            |impl Equal for Set {
            |  fn pub equals(other: ref Set) -> Bool {
            |    true
            |  }
            |}
            |""".trimMargin()
        assertEmpty(messages(text))
    }

    /** Cuttlebone's answers for [text]: each call to `slice` is to app.text's. */
    private fun compilerResolvesSlice(text: String) =
        Compiler().resolvesEvery("src/main.inko", text, "slice(", At("src/app/text.inko", "fn pub slice(start: Int, chars: Int) -> Int {\n  start\n}\n", "slice")).install()

    fun testRedundantArgumentNames() {
        val text = """
            |import app.text (slice)
            |
            |fn main {
            |  let start = 1
            |  let chars = 2
            |  slice(start: start, chars: chars)
            |  slice(chars: chars, start: start)
            |  slice(start: chars, chars: start)
            |}
            |""".trimMargin()
        compilerResolvesSlice(text)
        // Only the first call's names can go: the second's are out of order, the third's don't match.
        assertSameElements(messages(text), "start: start: Redundant argument name 'start:'")
    }

    fun testRemovingARedundantArgumentName() {
        val text = "import app.text (slice)\n\nfn main {\n  let start = 1\n  slice(start: start, chars: 2)\n}\n"
        compilerResolvesSlice(text)
        assertEquals(
            "import app.text (slice)\n\nfn main {\n  let start = 1\n  slice(start, chars: 2)\n}\n",
            fix(text, "Remove redundant argument name"),
        )
    }

    fun testEmptyParentheses() {
        val text = """
            |type Empty {}
            |
            |fn size -> Int {
            |  1
            |}
            |
            |fn main {
            |  size()
            |  Empty()
            |  [1].pop()
            |  let size = 2
            |  size()
            |}
            |""".trimMargin()
        assertSameElements(messages(text), "(): Unnecessary parentheses in a call without arguments", "(): Unnecessary parentheses in a call without arguments")
    }

    fun testRemovingEmptyParentheses() {
        assertEquals("fn main {\n  [1].pop\n}\n", fix("fn main {\n  [1].pop()\n}\n", "Remove the parentheses"))
    }

    fun testNoInspectionCommentsSuppress() {
        assertEmpty(messages("fn main {\n  # noinspection InkoEmptyParentheses\n  [1].pop()\n}\n"))
    }

    // Suppress actions live in Alt+Enter's submenus, which the fixture doesn't list, so apply the fix directly.
    fun testSuppressFixAddsTheComment() {
        open("fn main {\n  [1].pop()\n}\n")
        val descriptor = InkoEmptyParenthesesInspection().checkFile(myFixture.file, InspectionManager.getInstance(project), true)!!.single()
        val suppress = InkoInspectionSuppressor().getSuppressActions(descriptor.psiElement, "InkoEmptyParentheses").single()
        WriteCommandAction.runWriteCommandAction(project) { suppress.applyFix(project, descriptor) }
        assertEquals("fn main {\n  # noinspection InkoEmptyParentheses\n  [1].pop()\n}\n", myFixture.editor.document.text)
        assertEmpty(warnings())
    }
}
