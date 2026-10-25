package com.setdef.parakeet

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.RunConfigurationProducer
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.CapturingProcessAdapter
import com.intellij.execution.process.ProcessOutput
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.search.GlobalSearchScope
import java.nio.file.Files
import java.nio.file.Path

/**
 * Each feature end to end against the real `inko` (on PATH or via ivm).
 * Skipped by `./gradlew test -PunitOnly`.
 */
class InkoIntegrationTest : InkoTestCase(onDisk = true, realInko = true) {
    override fun setUp() {
        super.setUp()
        val cli = InkoCli.getInstance(project)
        assertTrue("inko must be installed to run integration tests", cli.isAvailable)
        println("Testing against ${cli.run(listOf("--version"), null)?.stdout?.trim()} at ${cli.commandLine(emptyList(), null)?.exePath}")
    }

    private fun open(path: String, text: String) {
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject(path, text).virtualFile)
    }

    private fun problems(): List<HighlightInfo> =
        myFixture.doHighlighting().filter { it.severity >= HighlightSeverity.WEAK_WARNING && it.description != null }

    fun testChecksUnsavedTextInAModuleOthersImport() {
        myFixture.addFileToProject("src/main.inko", "import app.util (helper)\n\ntype async Main {\n  fn async main {\n    helper\n  }\n}\n")
        open("src/app/util.inko", "import std.stdio (Stdout)\n\nfn pub helper -> Int {\n  42\n}\n")
        assertEquals("the symbol 'Stdout' is unused", problems().single { it.severity == HighlightSeverity.WARNING }.description)

        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("42"))
        myFixture.type("unsaved_only + ")
        assertEquals("the symbol 'unsaved_only' is undefined", problems().single { it.severity == HighlightSeverity.ERROR }.description)
    }

    // Compiler columns count grapheme clusters: the error is on `nope`, not a few UTF-16 units before it.
    fun testHighlightsErrorsAfterEmojiAndCombiningAccents() {
        open("src/app/wide.inko", "fn pub a -> Int {\n  let s = '\uD83D\uDC69\u200D\uD83D\uDC69\u200D\uD83D\uDC66 e\u0301' + nope\n  1\n}\n")
        val error = problems().single { it.severity == HighlightSeverity.ERROR }
        assertEquals("nope", myFixture.editor.document.getText(com.intellij.openapi.util.TextRange(error.startOffset, error.endOffset)))
    }

    /** Saves every file, then checks the whole project with the real compiler. */
    private fun assertCompiles() {
        FileDocumentManager.getInstance().saveAllDocuments()
        val result = InkoCli.getInstance(project).run(listOf("check"), Path.of(myFixture.tempDirPath))!!
        assertTrue(result.stderr, result.succeeded)
    }

    /**
     * Whether the real `cuttlebone` is there (CUTTLEBONE, which scripts/check.sh sets, else where Parakeet
     * looks), and if so, uses it: what changes code takes only the compiler's fresh answers. Without it the
     * test says it's skipped; `scripts/check.sh --full` requires it.
     */
    private val askingCuttlebone: Boolean
        get() {
            val executable = System.getenv("CUTTLEBONE")?.takeIf { it.isNotEmpty() }
                ?: run { InkoProjectSettings.getInstance(project).cuttlebone = ""; Cuttlebone.executable(project)?.toString() }
            if (executable == null) println("$name: skipped, as it needs cuttlebone (set CUTTLEBONE)") else useCuttlebone(executable)
            return executable != null
        }

    /**
     * Asks Cuttlebone about every name in [paths] (saved first, so it reads them from disk) in the background,
     * as the editor's passes would, so that what the test then runs on the EDT, where nothing waits for
     * Cuttlebone, has its answers.
     */
    private fun askCuttleboneAbout(vararg paths: String) {
        FileDocumentManager.getInstance().saveAllDocuments()
        val service = CuttleboneService.getInstance(project)
        if (service.server() == null) myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir(paths.first()))
        com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching("Cuttlebone to start (${service.status?.message})", { service.server() != null }, 60)
        val files = paths.map { PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir(it))!! }
        com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching("Cuttlebone to have the files", {
            val server = service.server()
            server != null && files.all { service.isSynced(server, it.virtualFile, it.viewProvider.document!!) }
        }, 30)
        val queries = CuttleboneQueries.getInstance(project)
        val asked = com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            com.intellij.openapi.application.ReadAction.run<Throwable> {
                for (file in files) for (name in PsiTreeUtil.findChildrenOfType(file, InkoName::class.java)) {
                    queries.definition(file, name.textRange.startOffset, budgetMs = 10_000)
                    queries.hover(file, name.textRange.startOffset, budgetMs = 10_000)
                }
            }
        }
        com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching("Cuttlebone's answers", { asked.isDone }, 120)
        asked.get()
    }

    private fun declaration(path: String, name: String): InkoDeclaration {
        val file = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir(path))!!
        return PsiTreeUtil.findChildrenOfType(file, InkoDeclaration::class.java).first { it.name == name }
    }

    fun testChangedSignaturesStillCompile() {
        // What changes code takes only the compiler's fresh answers.
        putTempDirInProjectContent()
        if (!askingCuttlebone) return
        myFixture.addFileToProject("src/app/util.inko", "fn pub area(width: Int, height: Int) -> Int {\n  width * height\n}\n\nfn pub square(side: Int) -> Int {\n  area(side, height: side)\n}\n")
        myFixture.addFileToProject("src/main.inko", "import app.util (area)\n\nfn total -> Int {\n  area(2, 3) + area(width: 4, height: area(1, 1))\n}\n")
        askCuttleboneAbout("src/app/util.inko", "src/main.inko")
        InkoChangeSignatureProcessor(project, declaration("src/app/util.inko", "area"), InkoSignatureChange(
            "rectangle",
            listOf(InkoParameterChange(1, "tall", "Int"), InkoParameterChange(0, "wide", "Int"), InkoParameterChange(-1, "scale", "Int", "1")),
            "Int",
        )).run()
        assertCompiles()
        assertTrue(PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("src/main.inko"))!!.text.contains("rectangle(3, 2, 1) + rectangle(tall: rectangle(1, 1, 1), wide: 4, scale: 1)"))
    }

    fun testIntroducedParametersStillCompile() {
        // What changes code takes only the compiler's fresh answers.
        putTempDirInProjectContent()
        if (!askingCuttlebone) return
        val util = "let pub LIMIT = 10\n\nfn pub clamp(value: Int) -> Int {\n  let doubled = LIMIT * 2\n  if value > doubled { LIMIT * 2 } else { value }\n}\n"
        myFixture.addFileToProject("src/app/util.inko", util)
        myFixture.addFileToProject("src/main.inko", "import app.util (clamp)\n\nfn total -> Int {\n  clamp(5) + clamp(value: 7)\n}\n")
        askCuttleboneAbout("src/app/util.inko", "src/main.inko")
        val file = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("src/app/util.inko"))!!
        val start = util.indexOf("LIMIT * 2")
        val plan = InkoIntroduceParameter.plan(file, com.intellij.openapi.util.TextRange(start, start + "LIMIT * 2".length)).getOrThrow()
        InkoIntroduceParameter.processor(project, plan, "limit", "Int", replaceAll = true).run()
        assertCompiles()
    }

    fun testIntroducedConstantsStillCompile() {
        val text = "import std.stdio (Stdout)\n\nfn pub seconds(hours: Int) -> Int {\n  hours * 60 * 60\n}\n\nfn pub minutes -> Int {\n  60 * 60 / 60\n}\n"
        open("src/app/time.inko", text)
        val stream = InkoTokenStream(text)
        val start = text.indexOf("60 * 60")
        val expression = com.intellij.openapi.util.TextRange(start, start + "60 * 60".length)
        val plan = InkoIntroduceConstant.plan(stream, expression, InkoIntroduceVariable.occurrences(stream, expression, fileWide = true)).getOrThrow()
        InkoIntroduceConstant.apply(project, myFixture.editor, plan)
        assertCompiles()
    }

    /** Extracts the text between `<selection>` markers in [path]'s [text], with the real compiler checking the result. */
    /**
     * Applies the intention titled [title] at `<caret>` in a fresh module, and checks the project still compiles.
     * One that needs [cuttlebone]'s answers has them asked for first.
     */
    private fun intentionCompiles(text: String, title: String, cuttlebone: Boolean = false) {
        putTempDirInProjectContent()
        if (cuttlebone && !askingCuttlebone) return
        open("src/app/rewrite.inko", text.replace("<caret>", ""))
        if (cuttlebone) askCuttleboneAbout("src/app/rewrite.inko")
        myFixture.editor.caretModel.moveToOffset(text.indexOf("<caret>"))
        val intention = myFixture.availableIntentions.firstOrNull { it.text == title } ?: error("'$title' wasn't offered")
        myFixture.launchAction(intention)
        assertCompiles()
    }

    private val describe = "fn pub describe(x: Int) -> String {\n  <caret>if x == 1 or x == 2 {\n    'small'\n  } else if x == 3 {\n    let y = 'three'\n    y\n  } else {\n    'big'\n  }\n}\n"

    fun testIfToMatchAndBackStillCompile() {
        intentionCompiles(describe, "Replace 'if' with 'match'")
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("match"))
        myFixture.launchAction(myFixture.availableIntentions.first { it.text == "Replace 'match' with 'if'" })
        assertCompiles()
    }

    fun testInvertedIfStillCompiles() = intentionCompiles(describe.replace("} else if x == 3 {\n    let y = 'three'\n    y\n  ", ""), "Invert 'if' condition")

    fun testFlippedComparisonStillCompiles() =
        intentionCompiles("fn pub check(a: Int, b: Int) -> Bool {\n  a + 1 <caret>< b and b > 2\n}\n", "Flip '<' to '>'")

    fun testNamedArgumentsStillCompile() =
        intentionCompiles("fn pub area(width: Int, height: Int) -> Int {\n  width * height\n}\n\nfn pub twice -> Int {\n  area(<caret>2, 3)\n}\n", "Add argument names", cuttlebone = true)

    fun testSpecifiedTypeStillCompiles() =
        intentionCompiles("fn pub area(width: Int, height: Int) -> Int {\n  let <caret>total = width * height\n  total\n}\n", "Specify type explicitly", cuttlebone = true)

    fun testProjectCheckFindsErrorsInUnopenedModules() {
        putTempDirInProjectContent()
        myFixture.addFileToProject("src/app/fine.inko", "fn pub fine -> Int {\n  1\n}\n")
        myFixture.addFileToProject("src/app/broken.inko", "fn pub broken -> Int {\n  nope\n}\n")
        FileDocumentManager.getInstance().saveAllDocuments()
        val root = Path.of(myFixture.tempDirPath)
        val result = InkoProjectCheck.getInstance(project).check(root)!!
        assertEquals(setOf(root.resolve("src/app/broken.inko")), result.diagnostics.keys)
        assertEquals("the symbol 'nope' is undefined", result.diagnostics.values.single().single().message)
    }

    // inko writes `\u{1}` for the control character in this message, which isn't JSON; the other error still shows.
    fun testAnInvalidEscapeDoesNotHideOtherErrors() {
        putTempDirInProjectContent()
        myFixture.addFileToProject("src/app/escape.inko", "fn pub a -> String {\n  '\\\u0001'\n}\n")
        myFixture.addFileToProject("src/app/broken.inko", "fn pub b -> Int {\n  (\n}\n")
        FileDocumentManager.getInstance().saveAllDocuments()
        val root = Path.of(myFixture.tempDirPath)
        val result = InkoProjectCheck.getInstance(project).check(root)!!
        assertEquals(setOf(root.resolve("src/app/escape.inko"), root.resolve("src/app/broken.inko")), result.diagnostics.keys)
        assertEquals("the escape sequence '\\\u0001' is invalid", result.diagnostics.getValue(root.resolve("src/app/escape.inko")).single().message)
    }

    fun testMovedDeclarationsStillCompile() {
        // What changes code takes only the compiler's fresh answers.
        putTempDirInProjectContent()
        if (!askingCuttlebone) return
        myFixture.addFileToProject("src/app/util.inko", "import std.stdio (Stdout)\n\nlet pub LIMIT = 10\n\ntype pub Helper {\n  let @count: Int\n}\n\nimpl Helper {\n  fn pub show {\n    Stdout.new.print(LIMIT.to_string)\n  }\n}\n\nfn pub make -> Helper {\n  Helper(count: LIMIT)\n}\n")
        myFixture.addFileToProject("src/main.inko", "import app.util (Helper, make)\n\ntype async Main {\n  fn async main {\n    let h: Helper = make\n    h.show\n  }\n}\n")
        assertCompiles()
        askCuttleboneAbout("src/app/util.inko", "src/main.inko")
        InkoMoveDeclarationProcessor(project, declaration("src/app/util.inko", "Helper"), "app.helpers").run()
        assertCompiles()
    }

    fun testGeneratedConstructorAndTraitImplCompile() {
        putTempDirInProjectContent()
        val text = "type pub Point {\n  let @x: Int\n  let pub @label: String\n}\n"
        open("src/app/point.inko", text)
        myFixture.editor.caretModel.moveToOffset(text.indexOf("let @x"))
        myFixture.performEditorAction("Parakeet.GenerateConstructor")
        InkoGenerateTraitImplAction.chooser = { traits -> traits.first { it.name == "Equal" && it.module == "std.cmp" } }
        try {
            myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("let @x"))
            myFixture.performEditorAction("Parakeet.GenerateTraitImpl")
        } finally {
            InkoGenerateTraitImplAction.chooser = null
        }
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains("impl Equal for Point"))
        assertCompiles()
    }

    /** Extracts the selection in [text], with [cuttlebone]'s types for locals if asked, and checks the project compiles. */
    private fun extractAndCompile(path: String, text: String, cuttlebone: Boolean = false) {
        val start = text.indexOf("<selection>")
        val end = text.indexOf("</selection>") - "<selection>".length
        val clean = text.replace("<selection>", "").replace("</selection>", "")
        open(path, clean)
        if (cuttlebone) askCuttleboneAbout(path)
        val file = myFixture.file as InkoFile
        val plan = InkoExtractMethod.plan(clean, com.intellij.openapi.util.TextRange(start, end)) { offset ->
            if (cuttlebone) InkoCompilerTypes.ofBinding(file, offset) else null
        }.getOrThrow()
        assertTrue("Placeholder types won't compile: ${plan.method}", plan.placeholders.isEmpty())
        InkoExtractMethod.apply(project, myFixture.editor, plan)
        assertCompiles()
    }

    fun testExtractedMethodsStillCompile() {
        extractAndCompile("src/app/area.inko", "fn pub area(width: Int, height: Int) -> Int {\n  <selection>let w = width * 2\n  w * height</selection>\n}\n")
        extractAndCompile(
            "src/app/counter.inko",
            "type pub Counter {\n  let mut @count: Int\n\n  fn pub mut bump(by: Int) {\n    <selection>@count = @count + by\n    @count = @count * 2</selection>\n  }\n}\n",
        )
        extractAndCompile(
            "src/app/totals.inko",
            "fn pub total(items: Array[Int]) -> Int {\n  let mut sum: Int = 0\n  <selection>sum = sum + items.size\n  sum = sum * 2</selection>\n  items.size + sum\n}\n",
        )
    }

    // Inko rejects assigning a parameter, `sum += …` included (`invalid-assign`), and the caller needs the new sum.
    fun testExtractedCompoundAssignmentsStillCompile() {
        extractAndCompile(
            "src/app/sums.inko",
            "fn pub total(items: Array[Int]) -> Int {\n  let mut sum: Int = 0\n  <selection>sum += items.size</selection>\n  sum\n}\n",
        )
        extractAndCompile(
            "src/app/loops.inko",
            "fn pub count -> Int {\n  let mut n: Int = 1\n  while n < 100 {\n    <selection>n *= 2</selection>\n  }\n  0\n}\n",
        )
    }

    // A local with no written type (`let mut sum = 0`) takes the compiler's type, so no placeholder is left.
    fun testExtractedUntypedLocalsTakeTheCompilersTypes() {
        putTempDirInProjectContent()
        if (!askingCuttlebone) return
        extractAndCompile(
            "src/app/untyped.inko",
            "fn pub total(items: Array[Int]) -> Int {\n  let mut sum = 0\n  let scale = 2.5\n  <selection>sum += items.size\n  sum += (scale * 2.0).to_int</selection>\n  sum\n}\n",
            cuttlebone = true,
        )
        assertTrue(myFixture.file.text, myFixture.file.text.contains("fn extracted(sum: Int, items: mut Array[Int], scale: Float) -> Int {"))
    }

    // Introduce Variable with every occurrence: not `sum += 1`'s, which assigns, nor the one after it, which reads the new sum.
    fun testIntroducedVariablesBesideCompoundAssignmentsStillCompile() {
        val text = "fn pub total(items: Array[Int]) -> Int {\n  let mut sum = 0\n  let first = sum + items.size\n  sum += 1\n  let again = sum + items.size\n  sum += first + (sum + items.size)\n  sum + again\n}\n"
        open("src/app/introduce.inko", text)
        val stream = InkoTokenStream(text)
        val expression = com.intellij.openapi.util.TextRange(text.indexOf("sum + items.size"), text.indexOf("sum + items.size") + "sum + items.size".length)
        val plan = InkoIntroduceVariable.plan(stream, expression, InkoIntroduceVariable.occurrences(stream, expression)).getOrThrow()
        InkoIntroduceVariable.apply(project, myFixture.editor, plan)
        assertEquals(text.replaceFirst("  let first = sum + items.size", "  let size = sum + items.size\n  let first = size"), myFixture.editor.document.text)
        assertCompiles()
    }

    // Inline Variable into a compound assignment's value, and its refusal for a compound-assigned variable.
    fun testInlinedVariablesBesideCompoundAssignmentsStillCompile() {
        val text = "fn pub total -> Int {\n  let step = 2\n  let mut sum = 1\n  sum += step\n  sum *= step\n  sum\n}\n"
        open("src/app/inline.inko", text)
        fun inline(marker: String): Result<InkoInlineVariable.Plan> {
            val declaration = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(myFixture.editor.document.text.indexOf(marker)), InkoDeclaration::class.java, false)!!
            val uses = InkoUsages.inFile(declaration).filter { !it.second && it.first != declaration }.map { it.first.textRange }
            return InkoInlineVariable.plan(InkoTokenStream(myFixture.editor.document.text), declaration.textRange.startOffset, uses)
        }
        assertEquals("`sum` is assigned a new value, so it can't be inlined.", inline("sum = 1").exceptionOrNull()?.message)
        InkoInlineVariable.apply(project, myFixture.editor, inline("step = 2").getOrThrow())
        assertEquals("fn pub total -> Int {\n  let mut sum = 1\n  sum += 2\n  sum *= 2\n  sum\n}\n", myFixture.editor.document.text)
        assertCompiles()
    }

    // AUDIT.md finding 1.1: names in `${…}` are inputs like any other.
    fun testExtractingInterpolatedCodeStillCompiles() = extractAndCompile(
        "src/app/greet.inko",
        "fn pub greet(name: String) -> String {\n  let greeting: String = 'Hello'\n  <selection>'\${greeting}, \${name}!'</selection>\n}\n",
    )

    // AUDIT.md findings 1.2 and 1.3: loop and pattern variables are locals, and an enum's methods aren't.
    fun testRenamedPatternVariablesStillCompile() {
        putTempDirInProjectContent()
        val text = "type pub enum Shape {\n  case Circle(Int)\n  case Square(Int)\n\n  fn pub area -> Int {\n    match self {\n      case Circle(r) -> r * r\n      case Square(s) -> s * s\n    }\n  }\n}\n\n" +
            "fn pub total(items: Array[Int]) -> Int {\n  let mut sum = 0\n  for (index, value) in items.iter.with_index { sum += value + index }\n  let Some(first) = items.first else return 0\n  sum + first\n}\n"
        open("src/app/shapes.inko", text)
        for ((marker, name) in listOf("value)" to "item", "first)" to "head", "r) ->" to "radius")) {
            val declaration = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(myFixture.file.text.indexOf(marker)), InkoDeclaration::class.java, false)!!
            myFixture.renameElement(declaration, name)
        }
        assertTrue(myFixture.file.text, myFixture.file.text.contains("sum += item + index") && myFixture.file.text.contains("sum + head") && myFixture.file.text.contains("radius * radius"))
        assertFalse(declaration("src/app/shapes.inko", "area").isLocal)
        assertCompiles()
    }

    // AUDIT.md finding 1.4: a method named after a keyword is a method, so Implement Methods doesn't offer it again.
    fun testImplementMethodsSeesKeywordNamedMethods() {
        val text = "import std.iter (Iter)\n\ntype Countdown {\n  let mut @n: Int\n}\n\nimpl Iter[Int] for Countdown {\n  fn pub mut next -> Option[Int] {\n    if @n == 0 { return Option.None }\n    @n -= 1\n    Option.Some(@n)\n  }\n}\n\nfn pub first -> Int {\n  Countdown(n: 3).next.or(0)\n}\n"
        open("src/app/countdown.inko", text)
        val impl = InkoTraitImpl.at(myFixture.file, text.indexOf("@n -= 1"))!!
        assertEquals("Iter", impl.trait.name)
        assertEmpty(impl.missing(required = true).map { it.name })
        assertCompiles()
    }

    // AUDIT.md finding 1.5: a new import goes after an import's build tags and after `import extern`.
    fun testImportsAddedAfterTaggedAndExternImportsStillCompile() {
        myFixture.addFileToProject("src/app/util.inko", "type pub Helper {}\n\nlet pub LIMIT = 1\n")
        open("src/app/main.inko", "import std.stdio (Stdout) if unix\nimport extern \"m\"\n\nfn pub make -> Int {\n  Stdout.new\n  let _ = Helper()\n  LIMIT\n}\n")
        WriteCommandAction.runWriteCommandAction(project) {
            InkoImportEdits.add(myFixture.editor.document, "app.util", "Helper")
            InkoImportEdits.add(myFixture.editor.document, "app.util", "LIMIT")
        }
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.startsWith("import std.stdio (Stdout) if unix\nimport extern \"m\"\nimport app.util (Helper, LIMIT)\n"))
        assertCompiles()
    }

    private val counter = "type pub Counter {\n  let mut @value: Int\n\n  fn pub mut value=(v: Int) {\n    @value = v\n  }\n\n  fn pub value -> Int {\n    @value\n  }\n}\n"
    private val bump = "import app.counter (Counter)\n\nfn pub bump(c: mut Counter) {\n  c.value += 1\n}\n\nfn pub set(c: mut Counter) {\n  c.value = 2\n}\n\nfn pub get(c: ref Counter) -> Int {\n  c.value\n}\n"

    // The method, not the field `@value`, which is also named `value`.
    private fun getter() = PsiTreeUtil.findChildrenOfType(PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("src/app/counter.inko"))!!, InkoDeclaration::class.java)
        .single { it.text == "value" }

    /** The names a search for [declaration] finds, as `line:text`, searched in the background as Find Usages does. */
    private fun searched(declaration: InkoDeclaration): List<String> {
        val future = com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread<List<String>> {
            com.intellij.openapi.application.ReadAction.compute<List<String>, Throwable> {
                com.intellij.psi.search.searches.ReferencesSearch.search(declaration).findAll().map { reference ->
                    val element = reference.element
                    "${element.containingFile.viewProvider.document!!.getLineNumber(element.textOffset) + 1}:${element.text}"
                }.sorted()
            }
        }
        com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching("the search", { future.isDone }, 120)
        return future.get()
    }

    // `c.value += 1` calls the getter `value` and then the setter `value=` (compiler/src/hir.rs, `binary_assign_setter`).
    fun testACompoundAssignmentIsAUseOfTheGetterAndTheSetter() {
        putTempDirInProjectContent()
        if (!askingCuttlebone) return
        myFixture.addFileToProject("src/app/counter.inko", counter)
        myFixture.addFileToProject("src/app/bump.inko", bump)
        assertCompiles()
        askCuttleboneAbout("src/app/counter.inko", "src/app/bump.inko")
        // By Cuttlebone's references.
        assertEquals(listOf("4:value", "8:value"), searched(declaration("src/app/counter.inko", "value=")))
        assertEquals(listOf("12:value", "4:value"), searched(getter()))
        // A module that doesn't parse leaves references without an answer, so each candidate is resolved instead.
        myFixture.addFileToProject("src/app/broken.inko", "fn pub broken( {\n")
        askCuttleboneAbout("src/app/counter.inko", "src/app/bump.inko")
        assertEquals(listOf("4:value", "8:value"), searched(declaration("src/app/counter.inko", "value=")))
        assertEquals(listOf("12:value", "4:value"), searched(getter()))
    }

    fun testRenamingOneAccessorOfACompoundAssignmentIsAConflict() {
        putTempDirInProjectContent()
        if (!askingCuttlebone) return
        myFixture.addFileToProject("src/app/counter.inko", counter)
        myFixture.addFileToProject("src/app/bump.inko", bump)
        askCuttleboneAbout("src/app/counter.inko", "src/app/bump.inko")
        // Only the getter: the offer to rename the setter too is declined.
        com.intellij.openapi.ui.TestDialogManager.setTestDialog(com.intellij.openapi.ui.TestDialog.NO, testRootDisposable)
        try {
            myFixture.renameElement(getter(), "amount")
            fail("expected a conflict")
        } catch (e: com.intellij.refactoring.BaseRefactoringProcessor.ConflictsInTestsException) {
            assertEquals(listOf("`c.value += 1` at bump.inko:4 also calls `value=`, which isn't renamed, so it would call `amount=` instead."), e.messages.toList())
        }
        assertCompiles()
    }

    // Renaming the getter with the setter, as offered: one rename, from Cuttlebone's fresh answers before anything changed.
    fun testRenamedAccessorPairsStillCompile() {
        putTempDirInProjectContent()
        if (!askingCuttlebone) return
        myFixture.addFileToProject("src/app/counter.inko", counter)
        myFixture.addFileToProject("src/app/bump.inko", bump)
        askCuttleboneAbout("src/app/counter.inko", "src/app/bump.inko")
        com.intellij.openapi.ui.TestDialogManager.setTestDialog(com.intellij.openapi.ui.TestDialog.YES, testRootDisposable)
        myFixture.renameElement(getter(), "amount")
        assertEquals(bump.replace("c.value", "c.amount"), PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("src/app/bump.inko"))!!.text)
        assertCompiles()
    }

    // AUDIT.md finding 1.6: `recv.value = x` calls the setter `value=`, so renaming the setter renames the call.
    fun testRenamedSettersStillCompile() {
        // What changes code takes only the compiler's fresh answers.
        putTempDirInProjectContent()
        if (!askingCuttlebone) return
        myFixture.addFileToProject("src/app/counter.inko", "type pub Counter {\n  let mut @value: Int\n\n  fn pub mut value=(v: Int) {\n    @value = v\n  }\n}\n")
        myFixture.addFileToProject("src/main.inko", "import app.counter (Counter)\n\nfn bump(c: mut Counter) {\n  c.value = 2\n}\n")
        askCuttleboneAbout("src/app/counter.inko", "src/main.inko")
        myFixture.renameElement(declaration("src/app/counter.inko", "value="), "amount=")
        assertTrue(PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("src/main.inko"))!!.text.contains("c.amount = 2"))
        assertCompiles()
    }

    // AUDIT.md finding 1.9: `(self)` imports the module under its last name.
    fun testNavigatesThroughASelfImport() {
        val text = "import std.fmt (self, Format)\n\nfn pub show(v: Int) -> String {\n  fmt.fmt(v)\n}\n"
        open("src/app/show.inko", text)
        myFixture.editor.caretModel.moveToOffset(text.indexOf("fmt(v)"))
        val target = (myFixture.getReferenceAtCaretPositionWithAssertion() as InkoReference).multiResolve(false).single().element!!
        assertEquals("fmt.inko:fmt", "${target.containingFile.name}:${target.text}")
        assertCompiles()
    }

    // AUDIT.md finding 1.10: inko doc leaves a trait's requirements out of its type, so they come from source.
    fun testTypeHierarchyShowsRequiredTraitsFromInkoDoc() {
        open("src/app/traits.inko", "trait pub Show {}\n\ntrait pub Debug: Show {}\n\ntype pub Thing {}\n\nimpl Show for Thing {}\n\nimpl Debug for Thing {}\n")
        FileDocumentManager.getInstance().saveAllDocuments()
        val debug = declaration("src/app/traits.inko", "Debug")
        assertEquals(listOf("Show"), InkoTypeHierarchy.related(debug, supertypes = true).map { InkoTypeHierarchy.definition(it)!!.name })
    }

    // AUDIT.md section 3: the Code Style preview must be valid Inko.
    fun testTheCodeStyleSampleCompiles() {
        val sample = InkoCodeStyleSettingsProvider().getCodeSample(com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider.SettingsType.INDENT_SETTINGS)
        myFixture.addFileToProject("src/sample.inko", sample)
        assertCompiles()
    }

    fun testInlinedMethodsStillCompile() {
        // What changes code takes only the compiler's fresh answers.
        putTempDirInProjectContent()
        if (!askingCuttlebone) return
        myFixture.addFileToProject(
            "src/app/util.inko",
            "let pub SCALE = 3\n\nfn pub double(value: Int) -> Int {\n  value * 2\n}\n\nfn pub area(width: Int, height: Int) -> Int {\n  let w = width * SCALE\n  w * height\n}\n\nfn pub repeat(value: Int) -> Int {\n  value + value\n}\n",
        )
        myFixture.addFileToProject(
            "src/main.inko",
            "import app.util (area, double, repeat)\n\nfn run -> Int {\n  let w = 10\n  let a = area(w, 2)\n  let r = repeat(w + 1)\n  double(w + 1) * 3 + a + r\n}\n",
        )
        for (name in listOf("area", "double", "repeat")) {
            // Each inlining changes the code, so the next one asks again.
            askCuttleboneAbout("src/app/util.inko", "src/main.inko")
            InkoInlineMethodProcessor(project, declaration("src/app/util.inko", name), null, keepMethod = false).run()
        }
        assertCompiles()
    }

    fun testQuickFixesUseTheCompilersDiagnostics() {
        open("src/app/util.inko", "import std.stdio (Stdout, Stderr)\n\nfn pub helper {\n  Stdout.new\n}\n")
        myFixture.launchAction(myFixture.getAllQuickFixes().single { it.text == "Remove unused import 'Stderr'" })
        myFixture.checkResult("import std.stdio (Stdout)\n\nfn pub helper {\n  Stdout.new\n}\n")
    }

    fun testQuickFixesForMissingFieldsAndUnreachableCode() {
        open("src/app/util.inko", "type pub Point {\n  let @x: Int\n  let @y: Int\n}\n\nfn pub point -> Point {\n  Point(x: 1)\n}\n")
        myFixture.launchAction(myFixture.getAllQuickFixes().single { it.text == "Add field 'y'" })
        myFixture.checkResult("type pub Point {\n  let @x: Int\n  let @y: Int\n}\n\nfn pub point -> Point {\n  Point(x: 1, y: <caret>)\n}\n")

        open("src/app/early.inko", "fn pub early -> Int {\n  return 1\n  let a = 2\n  a + 3\n}\n")
        myFixture.launchAction(myFixture.getAllQuickFixes().single { it.text == "Remove unreachable code" })
        myFixture.checkResult("fn pub early -> Int {\n  return 1\n}\n")
    }

    fun testImplementsTheCompilersMissingMethods() {
        open("src/app/shape.inko", "trait pub Shape {\n  fn pub area -> Int\n}\n\ntype pub Box {}\n\nimpl Shape for Box {}\n")
        myFixture.launchAction(myFixture.getAllQuickFixes().first { it.text == "Implement missing methods" })
        assertTrue(myFixture.editor.document.text, "  fn pub area -> Int {\n    panic('not implemented')\n  }" in myFixture.editor.document.text)
    }

    fun testOptimizeImportsRemovesUnusedAndSorts() {
        open("src/app/util.inko", "import std.stdio (Stdout, Stderr)\nimport std.fmt (Format)\nimport std.array (Array)\n\nfn pub helper -> Array[Int] {\n  Stdout.new\n  []\n}\n")
        val runnable = InkoImportOptimizer().processFile(myFixture.file)
        WriteCommandAction.runWriteCommandAction(project, runnable)
        myFixture.checkResult("import std.array (Array)\nimport std.stdio (Stdout)\n\nfn pub helper -> Array[Int] {\n  Stdout.new\n  []\n}\n")
    }

    fun testFormatsWithInkoFmt() {
        open("src/main.inko", "fn   main   {\nlet a=1\n}\n")
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
        assertEquals("fn main {\n  let a = 1\n}\n", myFixture.editor.document.text)
    }

    fun testNavigatesWithInkoDoc() {
        // Parakeet's own reading of the code, with the real `inko doc`: it stands in only where Cuttlebone can't check the code.
        cuttleboneCantCheckTheCode()
        myFixture.addFileToProject("src/app/util.inko", "type pub Helper {}\n")
        val text = "import app.util (Helper)\n\nfn a(h: Helper) -> String {\n  ''\n}\n"
        open("src/main.inko", text)

        fun targetAt(marker: String): String {
            myFixture.editor.caretModel.moveToOffset(text.indexOf(marker))
            val target = (myFixture.getReferenceAtCaretPositionWithAssertion() as InkoReference).multiResolve(false).single().element!!
            return "${target.containingFile.name}:${target.text}"
        }
        assertEquals("util.inko:Helper", targetAt("Helper)"))
        assertEquals("string.inko:String", targetAt("String"))

        val original = myFixture.file.findElementAt(text.indexOf("String"))!!
        val target = InkoResolver.resolve(original.parent as InkoName).single()
        val html = (InkoDocumentationTargetProvider().documentationTarget(target, original) as InkoDocumentationTarget).html()
        assertTrue(html, html.contains("String</code>") && html.contains("std.string") && html.contains("UTF-8"))
    }

    fun testRunsAProgram() {
        val text = "import std.stdio (Stdout)\n\ntype async Main {\n  fn async main {\n    Stdout.new.print('hello from inko')\n  }\n}\n"
        open("src/hello.inko", text)
        val leaf: PsiElement = myFixture.file.findElementAt(text.indexOf("main {"))!!
        @Suppress("UNCHECKED_CAST")
        val context = ConfigurationContext.createEmptyContextForLocation(PsiLocation(leaf) as Location<PsiElement>)
        val configuration = RunConfigurationProducer.getInstance(InkoRunConfigurationProducer::class.java)
            .createConfigurationFromContext(context)!!.configuration as InkoRunConfiguration
        val environment = ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), configuration).build()
        val handler = (configuration.getState(environment.executor, environment) as InkoRunState).execute(environment.executor, environment.runner).processHandler
        val output = ProcessOutput()
        handler.addProcessListener(CapturingProcessAdapter(output))
        handler.startNotify()
        assertTrue("inko run timed out", handler.waitFor(120_000))
        assertTrue(output.stdout + output.stderr, output.stdout.contains("hello from inko"))
    }

    fun testRunsTestsInTheTestRunner() {
        myFixture.addFileToProject("src/math.inko", "fn pub add(a: Int, b: Int) -> Int {\n  a + b\n}\n")
        val source = "import math (add)\nimport std.test (Tests)\n\nfn pub tests(t: mut Tests) {\n" +
            "  t.test('adds numbers', fn (t) { t.equal(add(1, 1), 2) })\n" +
            "  t.test(\"it's [odd]\", fn (t) { t.equal(add(2, 2), 5) })\n}\n"
        val file = myFixture.addFileToProject("test/test_math.inko", source)
        val before = Files.walk(Path.of(myFixture.tempDirPath)).use { it.toList().toSet() }

        val configuration = InkoRunConfigurationType.getInstance().configurationFactories[0].createTemplateConfiguration(project) as InkoRunConfiguration
        configuration.options.command = InkoCommand.TEST.cli
        configuration.options.workingDirectory = myFixture.tempDirPath
        val environment = ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), configuration).build()
        val state = configuration.getState(environment.executor, environment) as InkoTestRunState
        val result = state.execute(environment.executor, environment.runner)
        val console = result.executionConsole as com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
        try {
            result.processHandler!!.startNotify()
            assertTrue("tests timed out", result.processHandler!!.waitFor(180_000))
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()

            val tests = console.resultsViewer.testsRootNode.allTests.filter { it.isLeaf }
            assertEquals(setOf("adds numbers", "it's [odd]"), tests.map { it.name }.toSet())
            assertTrue(tests.single { it.name == "adds numbers" }.isPassed)
            val failed = tests.single { it.name == "it's [odd]" }
            assertTrue(failed.isDefect)
            assertTrue(failed.errorMessage, failed.errorMessage.orEmpty().startsWith(file.virtualFile.path + ":6"))
            // The tree links to the real test file.
            assertEquals("test_math.inko", console.resultsViewer.testsRootNode.children.single().name)
            assertEquals(file.virtualFile, failed.getLocation(project, GlobalSearchScope.allScope(project))!!.virtualFile)
        } finally {
            com.intellij.openapi.util.Disposer.dispose(console)
        }
        // The project itself gained no files.
        assertEquals(before, Files.walk(Path.of(myFixture.tempDirPath)).use { it.toList().toSet() })
    }

    fun testRerunsOnlyTheFailedTests() {
        myFixture.addFileToProject("src/math.inko", "fn pub add(a: Int, b: Int) -> Int {\n  a + b\n}\n")
        myFixture.addFileToProject("test/test_a.inko", "import math (add)\nimport std.test (Tests)\n\nfn pub tests(t: mut Tests) {\n" +
            "  t.test('a passes', fn (t) { t.equal(add(1, 1), 2) })\n  t.test('a fails', fn (t) { t.equal(add(1, 1), 3) })\n}\n")
        myFixture.addFileToProject("test/test_b.inko", "import math (add)\nimport std.test (Tests)\n\nfn pub tests(t: mut Tests) {\n" +
            "  t.test(\"b's failure\", fn (t) { t.equal(add(2, 2), 5) })\n}\n")
        val configuration = InkoRunConfigurationType.getInstance().configurationFactories[0].createTemplateConfiguration(project) as InkoRunConfiguration
        configuration.options.command = InkoCommand.TEST.cli
        configuration.options.workingDirectory = myFixture.tempDirPath

        /** Runs [state] and returns the test tree's leaves, as "file > name: passed|failed". */
        fun run(state: (com.intellij.execution.runners.ExecutionEnvironment) -> InkoTestRunState): Pair<List<String>, List<com.intellij.execution.testframework.AbstractTestProxy>> {
            val environment = ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), configuration).build()
            val result = state(environment).execute(environment.executor, environment.runner)
            val console = result.executionConsole as com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
            try {
                result.processHandler!!.startNotify()
                assertTrue("tests timed out", result.processHandler!!.waitFor(180_000))
                com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
                val root = console.resultsViewer.testsRootNode
                val leaves = root.children.flatMap { suite -> suite.children.map { "${suite.name} > ${it.name}: ${if (it.isDefect) "failed" else "passed"}" } }
                return leaves.sorted() to root.allTests.filter { it.isLeaf && it.isDefect }
            } finally {
                com.intellij.openapi.util.Disposer.dispose(console)
            }
        }

        val (first, failed) = run { configuration.getState(it.executor, it) as InkoTestRunState }
        assertEquals(listOf("test_a.inko > a fails: failed", "test_a.inko > a passes: passed", "test_b.inko > b's failure: failed"), first)

        val rerun = InkoRerunFailedTestsAction.failedTests(failed)
        val (second, _) = run { InkoTestRunState(it, configuration, Path.of(myFixture.tempDirPath), rerun) }
        // Only the failed tests, each file once.
        assertEquals(listOf("test_a.inko > a fails: failed", "test_b.inko > b's failure: failed"), second)
    }

    fun testRunsTestsInReleaseModeAndListsTargets() {
        myFixture.addFileToProject("src/math.inko", "fn pub add(a: Int, b: Int) -> Int {\n  a + b\n}\n")
        myFixture.addFileToProject("test/test_math.inko", "import math (add)\nimport std.test (Tests)\n\nfn pub tests(t: mut Tests) {\n  t.test('adds', fn (t) { t.equal(add(1, 1), 2) })\n}\n")
        val configuration = InkoRunConfigurationType.getInstance().configurationFactories[0].createTemplateConfiguration(project) as InkoRunConfiguration
        configuration.options.command = InkoCommand.TEST.cli
        configuration.options.workingDirectory = myFixture.tempDirPath
        configuration.options.release = true
        val environment = ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), configuration).build()
        val result = (configuration.getState(environment.executor, environment) as InkoTestRunState).execute(environment.executor, environment.runner)
        val console = result.executionConsole as com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
        try {
            result.processHandler!!.startNotify()
            assertTrue("tests timed out", result.processHandler!!.waitFor(300_000))
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            assertTrue(console.resultsViewer.testsRootNode.allTests.single { it.isLeaf }.isPassed)
        } finally {
            com.intellij.openapi.util.Disposer.dispose(console)
        }
        val cli = InkoCli.getInstance(project)
        assertTrue(InkoTargets.list(cli).contains(InkoTargets.host(cli)))
    }

    fun testCreatesAProjectWithInkoInit() {
        val directory = Files.createDirectories(Path.of(myFixture.tempDirPath, "From IntelliJ"))
        InkoProjectGenerator.generate(project, directory, InkoNewProjectSettings(github = true, moduleName = "from_intellij"))
        assertTrue(Files.isRegularFile(directory.resolve("inko.pkg")))
        assertTrue(Files.readString(directory.resolve("src/from_intellij.inko")).contains("type async Main"))
        assertTrue(Files.isRegularFile(directory.resolve(".github/workflows/push.yml")))
    }

    fun testEachIvmVersionRunsThatVersion() {
        for (version in Ivm.installedVersions()) {
            val result = InkoProcessCli { Ivm.executableOf(version) }.run(listOf("--version"), null)!!
            assertEquals("inko $version", result.stdout.trim())
        }
    }
}
