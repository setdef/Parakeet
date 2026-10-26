package com.setdef.parakeet

import com.intellij.codeInsight.daemon.impl.MockWolfTheProblemSolver
import com.intellij.codeInsight.daemon.impl.WolfTheProblemSolverImpl
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.AccessToken
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.problems.WolfTheProblemSolver
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.PlatformTestUtil
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.io.path.invariantSeparatorsPathString

/**
 * Real-world Inko projects, opened against the real `inko` and `cuttlebone`: Yorick Peterse's public projects
 * and Inko's own, cloned beside this repository (`../yorickpeterse/<name>` and `../inko-lang/<name>`), or under
 * PARAKEET_FIXTURES. Each is copied (without `.git`, `build` and `dep`), synced when it has dependencies, and
 * used only when it type checks with the installed Inko; a project that isn't there, can't be synced or doesn't
 * type check is skipped, with a message. Then nothing may log an error, the whole project's check must find
 * none, and in a sample of its files highlighting finds no error, Cuttlebone has hints, every member call
 * (`x.name`, but a closure's `call`, which nothing declares) and type name resolves (member calls through
 * Cuttlebone's definition), and a sample of member calls have a hover, documentation and completion that
 * offers them. scripts/check.sh --full runs these
 * (`-PrealWorld`); the per-commit check doesn't.
 */
class RealWorldProjectsIntegrationTest : InkoTestCase(onDisk = true, realInko = true) {
    fun testOpenflow() = project("yorickpeterse", "openflow")
    fun testKvi() = project("yorickpeterse", "kvi")
    fun testShost() = project("yorickpeterse", "shost")
    fun testClogs() = project("yorickpeterse", "clogs")
    fun testInkoMarkdown() = project("yorickpeterse", "inko-markdown")
    fun testInkoSyntax() = project("yorickpeterse", "inko-syntax")
    fun testInkoBuilder() = project("yorickpeterse", "inko-builder")
    fun testInkoWobsite() = project("yorickpeterse", "inko-wobsite")
    fun testCodeFormattingInInko() = project("yorickpeterse", "code-formatting-in-inko")
    fun testYorickpeterseCom() = project("yorickpeterse", "yorickpeterse.com")
    fun testIdoc() = project("inko-lang", "idoc")
    fun testWebsite() = project("inko-lang", "website")

    private val errors = CopyOnWriteArrayList<String>()
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

    private fun skip(name: String, why: String) = println("$name: skipped, $why")

    private val cli get() = InkoCli.getInstance(project)

    private fun project(owner: String, name: String) {
        val source = source(owner, name)
        if (!Files.isDirectory(source)) {
            return skip(name, "as $source isn't there (clone it there, or set PARAKEET_FIXTURES to the directory holding $owner/)")
        }
        val cuttlebone = System.getenv("CUTTLEBONE")?.takeIf { it.isNotEmpty() }
            ?: run { InkoProjectSettings.getInstance(project).cuttlebone = ""; Cuttlebone.executable(project)?.toString() }
            ?: return skip(name, "as it needs cuttlebone (set CUTTLEBONE)")
        val root = Path.of(myFixture.tempDirPath)
        copy(source, root)
        if (Files.readAllLines(root.resolve(Inko.MANIFEST)).any { it.trimStart().startsWith("require ") }) {
            val sync = cli.run(listOf("pkg", "sync"), root, timeoutMs = 600_000)
            if (sync?.succeeded != true) return skip(name, "as `inko pkg sync` failed: ${sync?.stderr?.trim()?.take(500)}")
        }
        val check = cli.run(listOf("check"), root, timeoutMs = 600_000)
        if (check?.succeeded != true) {
            val error = check?.stderr?.replace(Regex("\u001B\\[[0-9;]*m"), "")?.trim()?.lines()?.firstOrNull()
            return skip(name, "as it doesn't type check with ${cli.version()}: $error")
        }
        println("$name: checking against ${cli.version()} and $cuttlebone")

        VfsUtil.markDirtyAndRefresh(false, true, true, LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)!!)
        putTempDirInProjectContent()
        useCuttlebone(cuttlebone)
        val problems = mutableListOf<String>()

        wholeProject(root, problems)
        val files = sample(root)
        assertFalse("$name has no Inko files with member calls in src/", files.isEmpty())
        for (file in files) inFile(name, root, file, problems)

        assertTrue("$name logged errors:\n${errors.joinToString("\n\n")}", errors.isEmpty())
        assertTrue("$name:\n${problems.joinToString("\n")}", problems.isEmpty())
    }

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

    /** What runs for the whole project when it opens and after saves, on the threads it runs on in the IDE. */
    private fun wholeProject(root: Path, problems: MutableList<String>) {
        // The project-wide check, reported to the IDE's real problem-file service (light tests have a mock).
        val wolf = WolfTheProblemSolver.getInstance(project) as MockWolfTheProblemSolver
        wolf.setDelegate(WolfTheProblemSolverImpl.createTestInstance(project))
        try {
            val job = InkoProjectCheck.getInstance(project).launch(root, immediately = true)
            PlatformTestUtil.waitWithEventsDispatching("the project check didn't finish", { job.isCompleted }, 600)
            runBlocking { job.join() }
            val marked = InkoProjectCheck.getInstance(project).problemFiles(root)
            if (marked.isNotEmpty()) problems += "the project check found errors in ${marked.map { it.name }}, where inko check found none"
        } finally {
            wolf.resetDelegate()
        }
        // Libraries, from a background thread as after a sync or a toolchain change.
        ApplicationManager.getApplication().executeOnPooledThread { InkoLibraries.getInstance(project).refresh() }
        PlatformTestUtil.waitWithEventsDispatching("the libraries weren't found", { InkoLibraries.getInstance(project).libraries.isNotEmpty() }, 120)
        val symbols = InkoDocIndex.getInstance(project).symbols(root)
        if (symbols == null) problems += "inko doc indexed nothing"
    }

    /** Up to three of the project's source files with member calls, spread through them by path, and a test's. */
    private fun sample(root: Path): List<Path> {
        fun withCalls(directory: String): List<Path> {
            val dir = root.resolve(directory).takeIf(Files::isDirectory) ?: return emptyList()
            return Files.walk(dir).use { paths -> paths.filter { it.toString().endsWith(".${Inko.EXTENSION}") }.sorted().toList() }
                .filter { Regex("""\w\.[a-z_]""").containsMatchIn(Files.readString(it)) }
        }
        val src = withCalls("src")
        val sources = if (src.size <= 3) src else listOf(src.first(), src[src.size / 2], src.last()).distinct()
        return sources + withCalls("test").let { tests -> listOfNotNull(tests.getOrNull(tests.size / 2)) }
    }

    private fun <T> inBackground(action: () -> T): T {
        val future = ApplicationManager.getApplication().executeOnPooledThread<T> { ReadAction.compute<T, Throwable> { action() } }
        PlatformTestUtil.waitWithEventsDispatching("background action", { future.isDone }, 120)
        return future.get(1, TimeUnit.SECONDS)
    }

    private fun inFile(projectName: String, root: Path, path: Path, problems: MutableList<String>) {
        val relative = root.relativize(path).invariantSeparatorsPathString
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        myFixture.configureFromExistingVirtualFile(virtualFile)
        PlatformTestUtil.waitWithEventsDispatching({ "Cuttlebone didn't start: ${CuttleboneService.getInstance(project).status?.message}" }, {
            val server = CuttleboneService.getInstance(project).server()
            server != null && CuttleboneService.getInstance(project).sentVersions[server.getDocumentIdentifier(virtualFile).uri] ==
                server.getDocumentVersion(myFixture.editor.document)
        }, 120)

        val document = myFixture.editor.document
        fun at(offset: Int) = "$relative:${document.getLineNumber(offset) + 1}"

        for (info in myFixture.doHighlighting().filter { it.severity >= HighlightSeverity.ERROR }) {
            problems += "${at(info.startOffset)}: highlighted as an error: ${info.description}"
        }

        val file = PsiManager.getInstance(project).findFile(virtualFile)!!
        val queries = CuttleboneQueries.getInstance(project)
        // The file's first question waits for its whole check.
        inBackground { queries.hover(file, 0, 60_000) }
        val hints = inBackground { queries.inlayHints(file, 10_000) }
        if (hints?.value == null) problems += "$relative: Cuttlebone has no hints (${hints?.status})"

        // Every member call and type name: Parakeet resolves it, and Cuttlebone has a definition for a member call.
        // Except a closure's `call`, which the compiler provides: nothing declares it, so neither has a target.
        val names = names(file)
        val undefined = inBackground {
            names.filter { isMemberCall(it) }.map { it.textOffset to it.text }
                .filter { (offset, _) -> queries.definition(file, offset, 10_000)?.value.isNullOrEmpty() }
        }
        val builtIn = undefined.filter { it.second == "call" }.map { it.first }.toSet()
        if (builtIn.isNotEmpty()) println("$projectName: $relative: ${builtIn.size} `.call` with no declaration, as the compiler has it")
        for ((offset, text) in undefined - undefined.filter { it.first in builtIn }.toSet()) problems += "${at(offset)} `.$text`: Cuttlebone has no definition"
        val unresolved = inBackground {
            names.filter { name -> name.textOffset !in builtIn && (name.reference as InkoReference).multiResolve(false).none { it.element != null } }
                .map { it.textOffset to it.text }
        }
        for ((offset, text) in unresolved) problems += "${at(offset)} `$text`: doesn't resolve"

        val calls = sampleOf(names.filter { isMemberCall(it) })
        println("$projectName: $relative: ${names.size} names resolved; sampled ${calls.joinToString { "${at(it.textOffset)} .${it.text}" }}")
        for (name in calls) {
            val offset = name.textOffset
            val where = "${at(offset)} `.${name.text}`"
            val targets = inBackground { (name.reference as InkoReference).multiResolve(false).mapNotNull { it.element } }
            val hover = inBackground { queries.hover(file, offset, 10_000) }
            if (hover?.value == null) problems += "$where: Cuttlebone has no hover (${hover?.status})"
            targets.firstOrNull()?.let { target ->
                val html = inBackground { (InkoDocumentationTargetProvider().documentationTarget(target, name.firstChild) as? InkoDocumentationTarget)?.html() }
                if (html.isNullOrBlank()) problems += "$where: no documentation"
            }
            val completion = inBackground { queries.completion(file, offset, 10_000) }
            val labels = completion?.value.orEmpty().map { it.label }
            if (name.text !in labels) problems += "$where: completion there doesn't offer it (${labels.size} items, ${completion?.status})"
        }
    }

    /** The names in [file] that member calls (`x.name`, not in an import) and type names (`Foo`) are. */
    private fun names(file: com.intellij.psi.PsiFile): List<InkoName> =
        PsiTreeUtil.findChildrenOfType(file, InkoName::class.java).filter { name ->
            name !is InkoDeclaration && (isMemberCall(name) || name.tokenType == InkoTokens.CONSTANT && name.text.any(Char::isLowerCase))
        }

    private fun isMemberCall(name: InkoName): Boolean {
        val text = name.containingFile.text
        return name.tokenType == InkoTokens.IDENTIFIER && PsiTreeUtil.prevLeaf(name)?.node?.elementType == InkoTokens.DOT &&
            !text.substring(text.lastIndexOf('\n', name.textOffset - 1) + 1).trimStart().startsWith("import ")
    }

    /** Up to five of [names], spread through them. */
    private fun sampleOf(names: List<InkoName>): List<InkoName> =
        if (names.size <= 5) names else (0 until 5).map { names[it * (names.size - 1) / 4] }.distinct()
}
