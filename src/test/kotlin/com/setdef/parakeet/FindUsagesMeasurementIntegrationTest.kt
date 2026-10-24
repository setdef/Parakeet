package com.setdef.parakeet

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PlatformTestUtil
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * How long Find Usages takes on a common name, by each way of asking Cuttlebone: `textDocument/references`
 * once per search, or each candidate through `textDocument/definition` (a file's requests together, as when
 * references have no answer). Only runs with these set; CUTTLEBONE picks the server, as for the other
 * integration tests:
 *
 * - PARAKEET_MEASURE_STD: a directory holding std's `src` and `test` (for example
 *   `git -C <inko checkout> archive v0.21.1 std/src std/test | tar -x`, then `<there>/std`), to search for
 *   `Array.size`.
 * - PARAKEET_MEASURE_PROJECT and PARAKEET_MEASURE_USE: a project's directory (with `src`, `test`, `inko.pkg`
 *   and a synced `dep`), and the name to search for, as `path:marker`: a declaration, or a use (the
 *   declaration it resolves to is searched for).
 */
class FindUsagesMeasurementIntegrationTest : InkoTestCase(onDisk = true, realInko = true) {
    private fun env(name: String) = System.getenv(name)?.takeIf { it.isNotEmpty() }

    /** Copies [from]'s [dirs] (and `inko.pkg`) into the test project, on disk. */
    private fun copy(from: Path, vararg dirs: String) {
        val to = Path.of(myFixture.tempDirPath)
        for (dir in dirs) {
            if (!Files.isDirectory(from.resolve(dir))) continue
            Files.walk(from.resolve(dir)).use { paths ->
                paths.forEach { path ->
                    val target = to.resolve(from.relativize(path).toString())
                    if (Files.isDirectory(path)) Files.createDirectories(target) else Files.copy(path, target)
                }
            }
        }
        from.resolve(Inko.MANIFEST).takeIf(Files::isRegularFile)?.let { Files.copy(it, to.resolve(Inko.MANIFEST)) }
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(to)!!.refresh(false, true)
        putTempDirInProjectContent()
    }

    private fun <T> inBackground(action: () -> T): T {
        val future = ApplicationManager.getApplication().executeOnPooledThread<T> { ReadAction.compute<T, Throwable> { action() } }
        PlatformTestUtil.waitWithEventsDispatching("background action", { future.isDone }, 600)
        return future.get(1, TimeUnit.SECONDS)
    }

    /** A new Cuttlebone, with the file at [path] open: no checks kept from before. */
    private fun startCuttlebone(path: String) {
        InkoProjectSettings.getInstance(project).cuttlebone = ""
        useCuttlebone(env("CUTTLEBONE") ?: Cuttlebone.executable(project)!!.toString())
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir(path)!!)
        PlatformTestUtil.waitWithEventsDispatching({ "no server: ${CuttleboneService.getInstance(project).status?.message}" }, { CuttleboneService.getInstance(project).server() != null }, 60)
    }

    /** Finds [declaration]'s usages [times] times, printing each run's time, what it found, and what it asked. */
    private fun measure(label: String, declaration: () -> InkoDeclaration, times: Int = 3) {
        val queries = CuttleboneQueries.getInstance(project)
        repeat(times) { run ->
            val sentBefore = queries.sent.get()
            val start = System.nanoTime()
            val found = inBackground { ReferencesSearch.search(declaration(), GlobalSearchScope.projectScope(project)).findAll().map { it.element as InkoName } }
            val ms = (System.nanoTime() - start) / 1_000_000
            val possible = inBackground { InkoSearch.readOnly(declaration()) { found.count(InkoSearch::isPossible) } }
            println("MEASURE $label, run ${run + 1}: $ms ms, ${found.size} usages ($possible possible), ${queries.sent.get() - sentBefore} Cuttlebone requests")
        }
    }

    /** Both ways, each with a Cuttlebone of its own, the first run of each from no kept checks. */
    private fun measureBothWays(label: String, open: String, declaration: () -> InkoDeclaration) {
        // The definitions index (inko doc) is built first, as it would be in the background.
        inBackground { InkoDocIndex.getInstance(project).symbols(Path.of(myFixture.tempDirPath)) }
        startCuttlebone(open)
        measure("$label, references once", declaration)
        startCuttlebone(open)
        InkoCompilerReferences.getInstance(project).eachCandidate = true
        try {
            measure("$label, each candidate's definition", declaration)
        } finally {
            InkoCompilerReferences.getInstance(project).eachCandidate = false
        }
    }

    fun testFindUsagesOfArraySizeInStd() {
        val std = env("PARAKEET_MEASURE_STD")?.let(Path::of) ?: return println("Skipped: set PARAKEET_MEASURE_STD to std's directory (with src and test) to measure")
        copy(std, "src", "test")
        val declaration = {
            val file = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("src/std/array.inko")!!)!!
            PsiTreeUtil.getParentOfType(file.findElementAt(file.text.indexOf("fn pub size -> Int") + 7), InkoDeclaration::class.java, false)!!
        }
        measureBothWays("Array.size in std", "src/std/array.inko", declaration)
    }

    fun testFindUsagesInAProject() {
        val dir = env("PARAKEET_MEASURE_PROJECT")?.let(Path::of) ?: return println("Skipped: set PARAKEET_MEASURE_PROJECT and PARAKEET_MEASURE_USE to measure")
        val (path, marker) = env("PARAKEET_MEASURE_USE")!!.split(':', limit = 2)
        copy(dir, "src", "test", "dep")
        val use = {
            val file = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir(path)!!)!!
            PsiTreeUtil.getParentOfType(file.findElementAt(file.text.indexOf(marker)), InkoName::class.java, false)!!
        }
        startCuttlebone(path)
        val declaration = inBackground { use() as? InkoDeclaration ?: InkoResolver.resolveExactly(use()).single() as InkoDeclaration }
        println("MEASURE searching for ${declaration.text} in ${declaration.containingFile.virtualFile.path}")
        measureBothWays("${declaration.text} in ${dir.fileName}", path) { declaration }
    }
}
