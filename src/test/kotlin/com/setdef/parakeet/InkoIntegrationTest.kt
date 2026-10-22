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
import com.intellij.psi.PsiElement
import java.nio.file.Files
import java.nio.file.Path

/**
 * Each feature end to end against the real `inko` (on PATH or via ivm).
 * Skipped by `./gradlew test -PunitOnly`.
 */
class InkoIntegrationTest : InkoTestCase(onDisk = true, realInko = true) {
    override fun setUp() {
        super.setUp()
        assertTrue("inko must be installed to run integration tests", InkoCli.getInstance(project).isAvailable)
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

    fun testFormatsWithInkoFmt() {
        open("src/main.inko", "fn   main   {\nlet a=1\n}\n")
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
        assertEquals("fn main {\n  let a = 1\n}\n", myFixture.editor.document.text)
    }

    fun testNavigatesWithInkoDoc() {
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
