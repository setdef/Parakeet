package com.setdef.parakeet

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PlatformTestUtil
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * How long Find Usages takes on a common name (`Array.size`) in a project the
 * size of std, with Cuttlebone and without. Only runs with PARAKEET_MEASURE_STD
 * set to a directory holding std's `src` and `test` (for example
 * `git -C <inko checkout> archive v0.21.1 std/src std/test | tar -x`, then
 * `<there>/std`); CUTTLEBONE picks the server, as for the other integration tests.
 */
class FindUsagesMeasurementIntegrationTest : InkoTestCase(onDisk = true, realInko = true) {
    private val std: Path? = System.getenv("PARAKEET_MEASURE_STD")?.takeIf { it.isNotEmpty() }?.let(Path::of)

    private fun copyStd() {
        for (dir in listOf("src", "test")) {
            Files.walk(std!!.resolve(dir)).use { paths ->
                paths.filter { it.toString().endsWith(".inko") }.forEach { file ->
                    myFixture.addFileToProject("$dir/${std.resolve(dir).relativize(file)}", Files.readString(file))
                }
            }
        }
        putTempDirInProjectContent()
    }

    private fun <T> inBackground(action: () -> T): T {
        val future = ApplicationManager.getApplication().executeOnPooledThread<T> { ReadAction.compute<T, Throwable> { action() } }
        PlatformTestUtil.waitWithEventsDispatching("background action", { future.isDone }, 600)
        return future.get(1, TimeUnit.SECONDS)
    }

    /** Finds `Array.size`'s usages [times] times, printing each run's time and what it found. */
    private fun measure(label: String, times: Int = 3) {
        val array = myFixture.findFileInTempDir("src/std/array.inko")!!
        myFixture.configureFromExistingVirtualFile(array)
        val text = myFixture.editor.document.text
        val declaration = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(text.indexOf("fn pub size -> Int") + 7), InkoName::class.java, false)!!
        // The definitions index (inko doc) is built first, as it would be in the background.
        inBackground { InkoDocIndex.getInstance(project).symbols(Inko.projectRoot(Inko.pathOf(array)!!)) }
        val queries = CuttleboneQueries.getInstance(project)
        repeat(times) { run ->
            val sentBefore = queries.sent.get()
            val start = System.nanoTime()
            val found = inBackground { ReferencesSearch.search(declaration, GlobalSearchScope.projectScope(project)).findAll().size }
            val ms = (System.nanoTime() - start) / 1_000_000
            println("MEASURE $label, run ${run + 1}: $ms ms, $found usages, ${queries.sent.get() - sentBefore} Cuttlebone requests")
        }
    }

    fun testFindUsagesOfACommonName() {
        if (std == null) {
            println("Skipped: set PARAKEET_MEASURE_STD to std's directory (with src and test) to measure")
            return
        }
        copyStd()
        useCuttlebone("/nonexistent/cuttlebone")
        measure("without Cuttlebone")

        InkoProjectSettings.getInstance(project).cuttlebone = ""
        useCuttlebone(System.getenv("CUTTLEBONE")?.takeIf { it.isNotEmpty() } ?: Cuttlebone.executable(project)!!.toString())
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("src/std/array.inko")!!)
        PlatformTestUtil.waitWithEventsDispatching({ "no server: ${CuttleboneService.getInstance(project).status?.message}" }, { CuttleboneService.getInstance(project).server() != null }, 60)
        measure("with Cuttlebone")

        // A whole file's inlay hints: one request, where parameter hints used to ask about each call.
        val calls = myFixture.editor.document.text.count { it == '(' }
        repeat(2) { run ->
            val sentBefore = CuttleboneQueries.getInstance(project).sent.get()
            val start = System.nanoTime()
            val hints = inBackground { InkoParameterHints.inFile(myFixture.file).size + InkoParameterHints.types(myFixture.file).size }
            println("MEASURE array.inko's inlay hints, run ${run + 1}: ${(System.nanoTime() - start) / 1_000_000} ms, $hints hints, " +
                "${CuttleboneQueries.getInstance(project).sent.get() - sentBefore} requests ($calls parentheses in the file)")
        }
    }
}
