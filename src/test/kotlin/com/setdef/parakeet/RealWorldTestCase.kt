package com.setdef.parakeet

import com.intellij.openapi.application.AccessToken
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.PlatformTestUtil
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Real-world Inko projects, opened against the real `inko` and `cuttlebone`: Yorick Peterse's public projects
 * and Inko's own, cloned beside this repository (`../yorickpeterse/<name>` and `../inko-lang/<name>`), or under
 * PARAKEET_FIXTURES. Each is copied (without `.git`, `build` and `dep`) into the test's temporary directory,
 * synced when it has dependencies, and used only when it type checks with the installed Inko; a project that
 * isn't there, can't be synced (offline, say) or doesn't type check is skipped, with a message. Every error
 * logged on any thread is recorded in [errors]. The originals are only read.
 */
abstract class RealWorldTestCase : InkoTestCase(onDisk = true, realInko = true) {
    protected val errors = CopyOnWriteArrayList<String>()
    private var recording: AccessToken? = null

    override fun setUp() {
        super.setUp()
        // Every error logged on any thread, recorded rather than thrown where it happened, so each test can list them.
        recording = LoggedErrorProcessor.executeWith(object : LoggedErrorProcessor() {
            override fun processError(category: String, message: String, details: Array<String>, t: Throwable?): Set<Action> {
                errors += "$category: $message" + (t?.let { "\n" + it.stackTraceToString().lines().take(30).joinToString("\n") }.orEmpty())
                return Action.NONE
            }
        })
    }

    override fun tearDown() {
        try {
            recording?.close()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun source(owner: String, name: String): Path {
        val base = System.getenv("PARAKEET_FIXTURES")?.takeIf { it.isNotEmpty() }?.let(Path::of) ?: Path.of("").toAbsolutePath().parent
        return base.resolve(owner).resolve(name)
    }

    protected fun skip(name: String, why: String) = println("$name: skipped, $why")

    /**
     * [skip], unless PARAKEET_FIXTURES_REQUIRED is set (CI, before a release): then a project that isn't there,
     * or can't be synced, or no Cuttlebone, fails, since a skip would pass the release untested. One that doesn't
     * type check with this Inko is still skipped: three of them don't with 0.21.1.
     */
    private fun missing(name: String, why: String) {
        if (System.getenv("PARAKEET_FIXTURES_REQUIRED")?.isNotEmpty() == true) fail("$name: $why, and PARAKEET_FIXTURES_REQUIRED is set")
        skip(name, why)
    }

    protected val cli get() = InkoCli.getInstance(project)

    /**
     * Copies the project [owner]/[name] into the test's directory, syncs and type checks it, and opens it with
     * Cuttlebone: its root, or null when it's skipped (having said why).
     */
    protected fun open(owner: String, name: String): Path? {
        val source = source(owner, name)
        if (!Files.isDirectory(source)) {
            missing(name, "as $source isn't there (clone it there, or set PARAKEET_FIXTURES to the directory holding $owner/)")
            return null
        }
        val cuttlebone = System.getenv("CUTTLEBONE")?.takeIf { it.isNotEmpty() }
            ?: run { InkoProjectSettings.getInstance(project).cuttlebone = ""; Cuttlebone.executable(project)?.toString() }
            ?: run { missing(name, "as it needs cuttlebone (set CUTTLEBONE)"); return null }
        val root = Path.of(myFixture.tempDirPath)
        copy(source, root)
        if (Files.readAllLines(root.resolve(Inko.MANIFEST)).any { it.trimStart().startsWith("require ") }) {
            val sync = cli.run(listOf("pkg", "sync"), root, timeoutMs = 600_000)
            if (sync?.succeeded != true) {
                missing(name, "as `inko pkg sync` failed (it needs the network): ${sync?.stderr?.trim()?.take(500)}")
                return null
            }
        }
        val check = cli.run(listOf("check"), root, timeoutMs = 600_000)
        if (check?.succeeded != true) {
            skip(name, "as it doesn't type check with ${cli.version()}: ${firstLine(check?.stderr)}")
            return null
        }
        println("$name: checking against ${cli.version()} and $cuttlebone")

        VfsUtil.markDirtyAndRefresh(false, true, true, LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)!!)
        putTempDirInProjectContent()
        // PARAKEET_PROTOCOL: a directory to keep what Cuttlebone is sent and answers in, a directory per project.
        val recorded = System.getenv("PARAKEET_PROTOCOL")?.takeIf { it.isNotEmpty() }
            ?.let { CuttleboneProtocolRecorder.script(cuttlebone, Path.of(it).resolve(name)).toString() }
        useCuttlebone(recorded ?: cuttlebone)
        return root
    }

    /** The first line of `inko`'s [output], without color codes. */
    protected fun firstLine(output: String?): String? = withoutColors(output)?.trim()?.lines()?.firstOrNull()

    protected fun withoutColors(output: String?): String? = output?.replace(Regex("\u001B\\[[0-9;]*m"), "")

    /** [source]'s files at [root], but not what's generated or fetched: `.git`, `build` and `dep`. */
    private fun copy(source: Path, root: Path) {
        val skipped = setOf(".git", "build", "dep")
        Files.walk(source).use { paths ->
            paths.filter { path -> source.relativize(path).none { it.toString() in skipped } }.forEach { path ->
                val target = root.resolve(source.relativize(path).toString())
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(target)
                else Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS)
            }
        }
    }

    protected fun <T> inBackground(timeoutSeconds: Int = 120, action: () -> T): T {
        val future = ApplicationManager.getApplication().executeOnPooledThread<T> { ReadAction.compute<T, Throwable> { action() } }
        PlatformTestUtil.waitWithEventsDispatching("background action", { future.isDone }, timeoutSeconds)
        return future.get(1, TimeUnit.SECONDS)
    }

    /** Opens [virtualFile] in the editor, and waits until Cuttlebone has its text. */
    protected fun openInEditor(virtualFile: VirtualFile) {
        myFixture.configureFromExistingVirtualFile(virtualFile)
        PlatformTestUtil.waitWithEventsDispatching({
            val server = CuttleboneService.getInstance(project).server()
            "Cuttlebone didn't get ${virtualFile.name}'s text: ${CuttleboneService.getInstance(project).status?.message} (sent version " +
                "${server?.let { CuttleboneService.getInstance(project).sentVersions[it.getDocumentIdentifier(virtualFile).uri] }}, " +
                "document's ${server?.getDocumentVersion(myFixture.editor.document)})"
        }, {
            val server = CuttleboneService.getInstance(project).server()
            server != null && CuttleboneService.getInstance(project).sentVersions[server.getDocumentIdentifier(virtualFile).uri] ==
                server.getDocumentVersion(myFixture.editor.document)
        }, 120)
    }
}
