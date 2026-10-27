package com.setdef.parakeet

import junit.framework.TestCase
import java.io.File

/** The standing rules in AGENTS.md that a test can check. */
class GuardrailsTest : TestCase() {
    /** Gradle runs tests in the project directory. */
    private val root = File(".").canonicalFile

    /** Tracked and untracked (but not ignored) files, with their text. */
    private fun repositoryFiles(): List<Pair<String, String>> {
        val process = ProcessBuilder("git", "ls-files", "--cached", "--others", "--exclude-standard")
            .directory(root)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals("git ls-files failed: $output", 0, process.waitFor())
        return output.lines().filter { it.isNotBlank() }.mapNotNull { path ->
            // A file deleted but not yet staged can't be read.
            val file = File(root, path).takeIf { it.isFile } ?: return@mapNotNull null
            path to String(file.readBytes(), Charsets.UTF_8)
        }
    }

    fun testNoPathsFromOneMachine() {
        val problems = repositoryFiles().flatMap { (path, text) ->
            // This file spells out what it looks for.
            if (path == THIS_FILE) return@flatMap emptyList()
            text.lines().mapIndexedNotNull { index, line ->
                homePath(line, ciRunner = path.startsWith(".github/workflows/"))?.let { "$path:${index + 1}: $it" }
            }
        }
        assertTrue("use repository-relative paths; these only work on one machine:\n${problems.joinToString("\n")}", problems.isEmpty())
    }

    fun testHomePathsAreRecognised() {
        for (line in listOf(
            "see " + "/Users/" + "kerrick/Developer/x",
            "cd " + "/home/" + "runner/work",
            "C:" + "\\Users\\" + "kerrick\\x",
            "relative to `" + "~/" + "Developer/inko-lang/inko`",
        )) {
            assertNotNull(line, homePath(line, ciRunner = false))
        }
        // CI workflows run on hosted runners, whose home directory is the same everywhere.
        assertNull(homePath("  " + "~/" + ".cargo/bin/ivm", ciRunner = true))
        assertNotNull(homePath("  " + "/Users/" + "runner/x", ciRunner = true))
        for (line in listOf("System.getProperty(\"user.home\")", "a / b", "src/main/kotlin", "x ~ y", "\$HOME/.local/share")) {
            assertNull(line, homePath(line, ciRunner = false))
        }
    }

    fun testNothingIsNamedAfterInko() {
        val pluginXml = File(root, "src/main/resources/META-INF/plugin.xml").readText()
        val id = Regex("<id>(.*?)</id>").find(pluginXml)!!.groupValues[1]
        val name = Regex("<name>(.*?)</name>").find(pluginXml)!!.groupValues[1]
        val project = Regex("""rootProject\.name\s*=\s*"(.*?)"""").find(File(root, "settings.gradle.kts").readText())!!.groupValues[1]
        val storages = repositoryFiles().filter { it.first.endsWith(".kt") && it.first != THIS_FILE }
            .flatMap { (_, text) -> Regex("""Storage\("(.*?)"\)""").findAll(text).map { it.groupValues[1] } }

        assertEquals("the product is named this", "Parakeet for Inko", name)
        val named = (listOf(id, project) + storages).filter { it.contains("inko", ignoreCase = true) }
        assertTrue("never name anything after Inko (trademark): $named", named.isEmpty())
        assertTrue(id, id.contains("parakeet"))
    }

    // Gradle's file-system watching missed changes on a busy machine (a new test file, and two changed sources
    // after `git apply`), so the checks ran stale code: SESSION-0.13.1-and-stale-answers.md.
    fun testGradleDoesntWatchTheFileSystem() {
        val properties = java.util.Properties().apply { File(root, "gradle.properties").reader().use(::load) }
        assertEquals("org.gradle.vfs.watch in gradle.properties", "false", properties.getProperty("org.gradle.vfs.watch"))
    }

    private companion object {
        const val THIS_FILE = "src/test/kotlin/com/setdef/parakeet/GuardrailsTest.kt"

        // Built from pieces so the patterns don't match themselves.
        private val HOME = Regex("/" + "Users/[^/\\s]+|/" + "home/[a-z_][a-z0-9_.-]*/|[A-Za-z]:\\\\" + "Users\\\\")
        private val TILDE = Regex("(^|[\\s`'\"(=:])" + "~/")

        /** The home-directory path in [line], if any. `~/` is allowed in CI workflows, where it's the runner's. */
        fun homePath(line: String, ciRunner: Boolean): String? =
            HOME.find(line)?.value ?: TILDE.find(line)?.takeIf { !ciRunner }?.value?.trim()
    }
}
