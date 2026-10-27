package com.setdef.parakeet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.platform.lsp.api.LspServer
import com.intellij.testFramework.PlatformTestUtil
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Tests with a [FakeCuttlebone] as the project's Cuttlebone, run through
 * IntelliJ's real LSP client. [fakeInko] stands in for `inko` (Cuttlebone only
 * starts when there is one), answering `inko doc` with [docModules].
 */
abstract class CuttleboneTestCase : InkoTestCase(onDisk = true) {
    protected val fakeDirectory: Path get() = Path.of(myFixture.tempDirPath).resolve("fake-cuttlebone")
    protected val log: Path get() = fakeDirectory.resolve("log.jsonl")

    protected open val inkoVersion: String? = null
    protected open fun docModules(mirror: Path): List<String> = emptyList()

    override fun setUp() {
        super.setUp()
        // IntelliJ's LSP client only serves files in the project's content, as a real project's are.
        putTempDirInProjectContent()
        useFakeInko(FakeInkoCli { call ->
            when (call.args.firstOrNull()) {
                "--version" -> FakeInkoCli.ok(stdout = inkoVersion?.let { "inko $it\n" }.orEmpty())
                "doc" -> {
                    val docs = Files.createDirectories(call.workingDirectory!!.resolve("build/docs"))
                    docModules(call.workingDirectory).forEachIndexed { index, json -> Files.writeString(docs.resolve("module_$index.json"), json) }
                    Files.writeString(docs.resolve("\$meta.json"), """{"readme": ""}""")
                    FakeInkoCli.ok(stderr = "[]")
                }
                else -> FakeInkoCli.ok(stderr = "[]")
            }
        })
    }

    /** Makes the fake the project's Cuttlebone. It starts when an Inko file opens. */
    protected fun useFake(script: FakeCuttlebone.Script = FakeCuttlebone.Script(log = ""), version: String = "cuttlebone 0.13.0-fake (Inko 0.21.1)") {
        val executable = FakeCuttleboneScript.write(fakeDirectory, script.copy(log = log.toString()), version)
        useCuttlebone(executable.toString())
    }

    /** Opens [path] in an editor, as the user would, which starts Cuttlebone. */
    protected fun open(path: String, text: String) {
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject(path, text).virtualFile)
    }

    protected fun waitFor(what: String, timeoutSeconds: Int = 30, condition: () -> Boolean) {
        PlatformTestUtil.waitWithEventsDispatching({
            val states = com.intellij.platform.lsp.api.LspServerManager.getInstance(project)
                .getServersForProvider(CuttleboneSupportProvider::class.java).map { it.state }
            "timed out waiting for $what (status: ${CuttleboneService.getInstance(project).status?.message}; servers: $states; launches: ${launches()})"
        }, condition, TimingLimits.seconds(timeoutSeconds))
    }

    /** Waits for the server to start, and to have the open file's text. */
    protected fun runningServer(waitForOpen: Boolean = true): LspServer {
        waitFor("Cuttlebone to start") { CuttleboneService.getInstance(project).server() != null }
        if (waitForOpen) waitFor("the editor's text to be synced") { synced() }
        return CuttleboneService.getInstance(project).server()!!
    }

    /** Whether the server has the open file's current text. */
    protected fun synced(): Boolean {
        val server = CuttleboneService.getInstance(project).server() ?: return false
        return CuttleboneService.getInstance(project).isSynced(server, myFixture.file.virtualFile, myFixture.editor.document)
    }

    /** Every message the fake received, in order. */
    protected fun messages(): List<JsonObject> =
        if (!Files.exists(log)) emptyList() else Files.readAllLines(log).filter { it.isNotBlank() }.map { JsonParser.parseString(it).asJsonObject }

    protected fun received(method: String): List<JsonObject> = messages().filter { it.get("method")?.asString == method }

    protected fun launches(): Int = messages().count { it.get("fake")?.asString == "launch" }

    /** Runs [action] in a read action on a pooled thread, where Cuttlebone queries may wait, as IntelliJ's background work does. */
    protected fun <T> inBackground(action: () -> T): T {
        val future = ApplicationManager.getApplication().executeOnPooledThread<T> { ReadAction.compute<T, Throwable> { action() } }
        PlatformTestUtil.waitWithEventsDispatching("background action", { future.isDone }, TimingLimits.seconds(30))
        return future.get(1, TimeUnit.SECONDS)
    }

    /** An answer for the fake: [method] at the position of [marker] (plus [shift]) in [text]. */
    protected fun answerAt(text: String, marker: String, method: String, result: Any?, shift: Int = 0, status: String = "fresh"): FakeCuttlebone.Answer {
        val offset = text.indexOf(marker).also { check(it >= 0) { "no $marker" } } + shift
        val line = text.substring(0, offset).count { it == '\n' }
        return FakeCuttlebone.Answer(method, line, offset - (text.lastIndexOf('\n', offset - 1) + 1), result, status)
    }

    /** A location in [path] (relative to the project) at [marker] in [text]. */
    protected fun locationAt(path: String, text: String, marker: String, shift: Int = 0): Map<String, Any> {
        val offset = text.indexOf(marker).also { check(it >= 0) { "no $marker" } } + shift
        val line = text.substring(0, offset).count { it == '\n' }
        val character = offset - (text.lastIndexOf('\n', offset - 1) + 1)
        val uri = Path.of(myFixture.tempDirPath).resolve(path).toUri().toString()
        val position = mapOf("line" to line, "character" to character)
        val end = mapOf("line" to line, "character" to character + marker.length - shift)
        return mapOf("uri" to uri, "range" to mapOf("start" to position, "end" to end))
    }
}
