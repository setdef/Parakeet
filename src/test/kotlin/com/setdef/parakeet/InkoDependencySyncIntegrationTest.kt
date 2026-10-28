package com.setdef.parakeet

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.testFramework.replaceService
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * `inko check`, `inko build` and `inko doc` sync dependencies first whenever
 * `dep/hash` isn't the hash of `inko.pkg` (compiler/src/compiler.rs, prepare),
 * and a sync runs `git`. Indexing must never sync: it uses the project's own
 * `dep/` as it is. (Errors are Cuttlebone's, which never syncs.)
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

    fun testIndexingDoesNotSync() {
        Files.writeString(root.resolve("inko.pkg"), "# edited\n$manifest")
        installDependency(syncedManifest = manifest)
        myFixture.addFileToProject("src/main.inko", "import greet (hello)\n\nfn pub a -> String {\n  hello\n}\n")
        val symbols = InkoDefinitionIndex.getInstance(project).symbols(root)!!
        assertEquals(emptyList<String>(), gitCalls())
        assertEquals(listOf("greet"), symbols.named("hello").map { it.module })
    }
}
