package com.setdef.parakeet

import java.nio.file.Files
import java.nio.file.Path

class InkoProjectGeneratorTest : InkoTestCase(onDisk = true) {
    /** Does what `inko init .` does: names the main module after the directory, minus `inko-`. */
    private val fakeInit = FakeInkoCli { call ->
        val dir = call.workingDirectory!!
        Files.createDirectories(dir.resolve("src"))
        Files.writeString(dir.resolve("inko.pkg"), "require inko 0.21.1\n")
        Files.writeString(dir.resolve("src/${dir.fileName.toString().removePrefix("inko-")}.inko"), "")
        FakeInkoCli.ok()
    }

    private fun directory(name: String): Path = Files.createDirectories(Path.of(myFixture.tempDirPath, name))

    fun testRunsInkoInitWithTheChosenOptions() {
        useFakeInko(fakeInit)
        val dir = directory("inko-greeter")
        InkoProjectGenerator.generate(project, dir, InkoNewProjectSettings(toolchain = InkoToolchain.IvmVersion("0.20.0"), library = true, github = true))
        // Opening the new project's main file also indexes and checks it, so look only at init.
        val call = fakeInit.calls.single { it.args.first() == "init" }
        assertEquals(listOf("init", ".", "--lib", "--github"), call.args)
        assertEquals(dir, call.workingDirectory)
        assertEquals(InkoToolchain.IvmVersion("0.20.0"), InkoProjectSettings.getInstance(project).toolchain)
        InkoProjectSettings.getInstance(project).toolchain = InkoToolchain.Auto
    }

    fun testRenamesTheMainModuleToTheChosenName() {
        useFakeInko(fakeInit)
        val dir = directory("From IntelliJ")
        InkoProjectGenerator.generate(project, dir, InkoNewProjectSettings(moduleName = "from_intellij"))
        assertTrue(Files.isRegularFile(dir.resolve("src/from_intellij.inko")))
        assertFalse(Files.exists(dir.resolve("src/From IntelliJ.inko")))
    }

    fun testLeavesTheProjectAloneWhenInitFails() {
        useFakeInko(FakeInkoCli { FakeInkoCli.failed("nope") })
        val dir = directory("broken")
        InkoProjectGenerator.generate(project, dir, InkoNewProjectSettings(moduleName = "broken"))
        assertFalse(Files.exists(dir.resolve("src")))
    }

    fun testSuggestsValidModuleNames() {
        assertEquals("from_intellij", InkoModuleNames.suggest("From IntelliJ"))
        assertEquals("greeter", InkoModuleNames.suggest("inko-greeter"))
        assertEquals("my_app", InkoModuleNames.suggest("my-app"))
        assertEquals("_2048", InkoModuleNames.suggest("2048"))
        assertEquals("main", InkoModuleNames.suggest("!!!"))
        listOf("From IntelliJ", "inko-greeter", "my-app", "2048", "!!!").forEach { assertNull(InkoModuleNames.problem(InkoModuleNames.suggest(it))) }
    }

    fun testRejectsInvalidModuleNames() {
        listOf("", "From IntelliJ", "my-app", "1abc").forEach { assertNotNull(it, InkoModuleNames.problem(it)) }
        assertNull(InkoModuleNames.problem("my_app2"))
    }
}
