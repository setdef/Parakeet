package com.setdef.parakeet

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.toolbar.floating.FloatingToolbarComponent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Disposer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** inko.pkg: reading it like the compiler, sync state, editing, and the dependency actions. */
class InkoManifestTest : InkoTestCase(onDisk = true) {
    private val idoc = InkoManifestTest::class.java.getResourceAsStream("/blake3/idoc.pkg")!!.readBytes().toString(StandardCharsets.UTF_8)

    private val root: Path get() = Path.of(myFixture.tempDirPath)

    fun testReadsDependenciesAndTheInkoVersion() {
        val manifest = InkoManifest.parse(idoc)
        assertEquals("0.21.1", manifest.inkoVersion)
        assertEquals(
            listOf("inko-wobsite", "inko-builder", "inko-markdown", "inko-syntax"),
            manifest.dependencies.map { it.url.substringAfterLast('/') },
        )
        assertEquals("0.26.0", manifest.dependencies.first().version)
        assertEmpty(manifest.problems)
    }

    fun testReportsWhatTheCompilerWouldReject() {
        val text = "# fine\nrequire inko 0.21\nrequire inko 0.21.1\nrequire inko 0.21.1\nneed x\nrequire github.com/a/b 1.0.0 short\nrequire github.com/a/b 01.0.0 ${"a".repeat(40)}\nrequire x\n"
        val problems = InkoManifest.parse(text).problems.map { text.substring(it.range.startOffset, it.range.endOffset) to it.message }
        assertEquals(
            listOf(
                "require inko 0.21" to "The version '0.21' is invalid (expected e.g. 1.2.3)",
                // Like the compiler, the first `require inko` line counts even when its version is invalid.
                "require inko 0.21.1" to "The Inko version is already required on line 2",
                "require inko 0.21.1" to "The Inko version is already required on line 2",
                "need x" to "Expected the line to start with 'require', not 'need'",
                "require github.com/a/b 1.0.0 short" to "The checksum is invalid (expected 40 characters)",
                "require github.com/a/b 01.0.0 ${"a".repeat(40)}" to "The version '01.0.0' is invalid (expected e.g. 1.2.3)",
                "require x" to "Expected 'require inko VERSION' or 'require URL VERSION CHECKSUM'",
            ),
            problems,
        )
    }

    fun testSyncIsNeededWhenDepHashDoesNotMatch() {
        Files.writeString(root.resolve("inko.pkg"), idoc)
        assertTrue("no dep/ yet", InkoDependencySync.needed(root))
        Files.createDirectories(root.resolve("dep"))
        Files.writeString(root.resolve("dep/hash"), InkoBlake3.hex(idoc.toByteArray()))
        assertFalse("synced", InkoDependencySync.needed(root))
        assertTrue("edited, unsaved", InkoDependencySync.needed(root, "$idoc\n"))
    }

    fun testProjectsWithoutDependenciesNeverNeedSyncing() {
        Files.writeString(root.resolve("inko.pkg"), "require inko 0.21.1\n")
        assertFalse(InkoDependencySync.needed(root))
    }

    fun testHighlightsInvalidLines() {
        // Annotators only run on files in the project, as a real inko.pkg is.
        putTempDirInProjectContent()
        myFixture.configureByText("inko.pkg", "# Dependencies\nrequire inko 0.21.1\n<error descr=\"Expected the line to start with 'require', not 'need'\">need x</error>\n")
        assertEquals(InkoManifestFileType.INSTANCE, myFixture.file.fileType)
        myFixture.checkHighlighting()
    }

    fun testLexesEachPartOfALine() {
        val lexer = InkoManifestLexer()
        val text = "require github.com/a/b 1.0.0 ${"a".repeat(40)}\nrequire inko 0.21.1\n# hi"
        lexer.start(text, 0, text.length, 0)
        val tokens = buildList {
            while (lexer.tokenType != null) {
                if (lexer.tokenType != com.intellij.psi.TokenType.WHITE_SPACE) add(lexer.tokenType.toString())
                lexer.advance()
            }
        }
        assertEquals(listOf("KEYWORD", "URL", "VERSION", "CHECKSUM", "KEYWORD", "KEYWORD", "VERSION", "COMMENT"), tokens)
    }

    fun testAddingADependencyRunsAddThenSync() {
        val fake = useFakeInko(FakeInkoCli())
        Files.writeString(root.resolve("inko.pkg"), "require inko 0.21.1\n")
        InkoDependencies.run(project, root, "Adding", listOf("add", "github.com/a/b", "1.0.0"))
        val pkg = fake.calls.filter { it.args.first() == "pkg" }
        assertEquals(listOf(listOf("pkg", "add", "github.com/a/b", "1.0.0"), listOf("pkg", "sync")), pkg.map { it.args })
        assertTrue(pkg.all { it.workingDirectory == root })
    }

    fun testActionsActOnTheProjectOfTheFileInContext() {
        val manifest = myFixture.addFileToProject("inko.pkg", "require inko 0.21.1\n").virtualFile
        val source = myFixture.addFileToProject("src/app/util.inko", "fn a {}\n").virtualFile
        assertEquals(root, InkoDependencies.rootFor(project, manifest))
        assertEquals(root, InkoDependencies.rootFor(project, source))
    }

    fun testSyncingRunsSyncOnce() {
        val fake = useFakeInko(FakeInkoCli())
        InkoDependencies.run(project, root, "Syncing", listOf("sync"))
        assertEquals(listOf(listOf("pkg", "sync")), fake.calls.filter { it.args.first() == "pkg" }.map { it.args })
    }

    fun testAFailedCommandStopsBeforeSyncing() {
        val fake = useFakeInko(FakeInkoCli { FakeInkoCli.failed("no such package") })
        InkoDependencies.run(project, root, "Adding", listOf("add", "github.com/a/b", "1.0.0"))
        assertEquals(1, fake.calls.count { it.args.first() == "pkg" })
    }

    fun testTheFloatingSyncButtonFollowsTheManifestsText() {
        Files.createDirectories(root.resolve("dep"))
        Files.writeString(root.resolve("dep/hash"), InkoBlake3.hex(idoc.toByteArray()))
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("inko.pkg", idoc).virtualFile)
        val component = RecordingToolbar()
        val context = SimpleDataContext.builder().add(CommonDataKeys.EDITOR, myFixture.editor).build()
        val provider = InkoManifestFloatingToolbarProvider()
        val disposable = Disposer.newDisposable().also { Disposer.register(testRootDisposable, it) }
        provider.register(context, component, disposable)
        assertFalse("synced", component.shown)
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(0, "# changed\n") }
        assertTrue("edited", component.shown)
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.deleteString(0, "# changed\n".length) }
        assertFalse("edited back", component.shown)
    }

    // Editors use \n, but inko hashes the file's bytes: hash the text with the file's own line separator.
    fun testHashesEditorTextAsItWouldBeSaved() {
        val crlf = idoc.replace("\n", "\r\n")
        Files.createDirectories(root.resolve("dep"))
        Files.writeString(root.resolve("dep/hash"), InkoBlake3.hex(crlf.toByteArray()))
        Files.writeString(root.resolve("inko.pkg"), crlf)
        assertTrue("as the editor holds it", InkoDependencySync.needed(root, idoc))
        assertFalse("as it would be saved", InkoDependencySync.needed(root, InkoDependencySync.asSaved(idoc, "\r\n")))
        assertFalse("on disk", InkoDependencySync.needed(root))
    }

    fun testOtherEditorsNeverShowTheSyncButton() {
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("src/main.inko", "fn main {}\n").virtualFile)
        val component = RecordingToolbar()
        InkoManifestFloatingToolbarProvider().register(SimpleDataContext.builder().add(CommonDataKeys.EDITOR, myFixture.editor).build(), component, testRootDisposable)
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(0, "# changed\n") }
        assertFalse(component.shown)
    }

    fun testInkoFilesShowABannerUntilSynced() {
        Files.writeString(root.resolve("inko.pkg"), idoc)
        val file = myFixture.addFileToProject("src/main.inko", "fn main {}\n").virtualFile
        myFixture.configureFromExistingVirtualFile(file)
        val editor = FileEditorManager.getInstance(project).getSelectedEditor(file)!!
        assertNotNull(InkoDependenciesBanner().collectNotificationData(project, file)?.apply(editor))
        Files.createDirectories(root.resolve("dep"))
        Files.writeString(root.resolve("dep/hash"), InkoBlake3.hex(idoc.toByteArray()))
        assertNull(InkoDependenciesBanner().collectNotificationData(project, file))
    }

    private class RecordingToolbar : FloatingToolbarComponent {
        var shown = false
        override var backgroundAlpha = 1f
        override var showingTime = 0
        override var hidingTime = 0
        override var retentionTime = 0
        override var autoHideable = false
        override fun scheduleShow() { shown = true }
        override fun scheduleHide() { shown = false }
        override fun hideImmediately() { shown = false }
    }
}
