package com.setdef.parakeet

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.testFramework.replaceService
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * `inko check`, `inko build` and `inko doc` sync dependencies first whenever
 * `dep/hash` isn't the hash of `inko.pkg` (compiler/src/compiler.rs, prepare),
 * and a sync runs `git`. Checks and indexing run in a mirror of the project,
 * and must never sync there: they use the project's own `dep/` as it is.
 *
 * The real `inko` runs here with a `git` first on PATH that records each call
 * and fails, as git would offline.
 */
class InkoDependencySyncIntegrationTest : InkoTestCase(onDisk = true, realInko = true) {
    private val root: Path get() = Path.of(myFixture.tempDirPath)
    private lateinit var gitLog: Path

    /** A dependency that only exists as a URL: fetching it would need git. */
    private val url = "https://example.invalid/parakeet/inko-greet"

    override fun setUp() {
        super.setUp()
        val bin = Files.createTempDirectory("fake-git")
        gitLog = bin.resolve("calls.log")
        val git = bin.resolve("git")
        Files.writeString(git, "#!/bin/sh\necho \"git \$*\" >> '$gitLog'\necho 'fatal: no network' >&2\nexit 128\n")
        git.toFile().setExecutable(true)
        val inko = InkoToolchain.Auto.executable() ?: error("inko must be installed to run integration tests")
        val cli = object : InkoProcessCli({ inko }) {
            override fun commandLine(args: List<String>, workingDirectory: Path?): GeneralCommandLine? =
                super.commandLine(args, workingDirectory)?.withEnvironment("PATH", "$bin:${System.getenv("PATH")}")
        }
        project.replaceService(InkoCli::class.java, cli, testRootDisposable)
    }

    private fun gitCalls(): List<String> = if (Files.exists(gitLog)) Files.readAllLines(gitLog) else emptyList()

    private val manifest = "require $url 1.0.0 ${"a".repeat(40)}\n"

    /** The project's `dep/`, as `inko pkg sync` lays it out, synced for [syncedManifest]. */
    private fun installDependency(syncedManifest: String) {
        val src = Files.createDirectories(root.resolve("dep/${InkoBlake3.hex(url.toByteArray())}/v1/src"))
        Files.writeString(src.resolve("greet.inko"), "fn pub hello -> String {\n  'hello'\n}\n")
        Files.writeString(root.resolve("dep/hash"), InkoBlake3.hex(syncedManifest.toByteArray(StandardCharsets.UTF_8)))
    }

    private val main = "import greet (hello)\n\nfn pub a -> Int {\n  hello.size + nope\n}\n"

    private fun check(): List<String> {
        myFixture.addFileToProject("inko.pkg", Files.readString(root.resolve("inko.pkg")))
        val file = root.resolve("src/main.inko")
        return InkoCheck.run(project, file, main).map { it.message }
    }

    fun testChecksUseTheProjectsDependenciesWhenTheManifestChangedSinceTheLastSync() {
        Files.writeString(root.resolve("inko.pkg"), "# edited since the sync\n$manifest")
        installDependency(syncedManifest = manifest)
        assertEquals(listOf("the symbol 'nope' is undefined"), check())
        assertEquals(emptyList<String>(), gitCalls())
    }

    fun testChecksOfANeverSyncedProjectDoNotSync() {
        Files.writeString(root.resolve("inko.pkg"), manifest)
        val messages = check()
        assertEquals(emptyList<String>(), gitCalls())
        // Without the dependency, its import can't resolve; the rest is still checked.
        assertTrue(messages.toString(), messages.any { it.contains("'greet'") })
    }

    fun testProjectChecksDoNotSync() {
        Files.writeString(root.resolve("inko.pkg"), "# edited\n$manifest")
        installDependency(syncedManifest = manifest)
        myFixture.addFileToProject("src/main.inko", main)
        val result = InkoProjectCheck.getInstance(project).check(root)!!
        assertEquals(listOf("the symbol 'nope' is undefined"), result.diagnostics.values.flatten().map { it.message })
        assertEquals(emptyList<String>(), gitCalls())
    }

    fun testIndexingDoesNotSync() {
        Files.writeString(root.resolve("inko.pkg"), "# edited\n$manifest")
        installDependency(syncedManifest = manifest)
        myFixture.addFileToProject("src/main.inko", "import greet (hello)\n\nfn pub a -> String {\n  hello\n}\n")
        val symbols = InkoDocIndex.getInstance(project).symbols(root)!!
        assertEquals(emptyList<String>(), gitCalls())
        assertEquals(listOf("greet"), symbols.named("hello").map { it.module })
    }
}
