package com.setdef.parakeet

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.TempDirTestFixture
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import java.nio.file.Files
import java.nio.file.Path

class InkoNewProjectTest : BasePlatformTestCase() {
    override fun createTempDirTestFixture(): TempDirTestFixture = TempDirTestFixtureImpl()

    fun testGeneratesAProjectWithInkoInit() {
        val directory = Files.createDirectories(Path.of(myFixture.tempDirPath, "inko-greeter"))
        InkoProjectGenerator.generate(project, directory, InkoNewProjectSettings(toolchain = InkoToolchain.Auto, github = true))
        assertTrue(Files.isRegularFile(directory.resolve("inko.pkg")))
        assertTrue(Files.readString(directory.resolve("src/greeter.inko")).contains("type async Main"))
        assertTrue(Files.isRegularFile(directory.resolve(".github/workflows/push.yml")))
        assertEquals(InkoToolchain.Auto, InkoProjectSettings.getInstance(project).toolchain)
    }

    fun testRenamesTheMainModuleToTheChosenName() {
        val directory = Files.createDirectories(Path.of(myFixture.tempDirPath, "From IntelliJ"))
        InkoProjectGenerator.generate(project, directory, InkoNewProjectSettings(library = true, moduleName = "from_intellij"))
        assertTrue(Files.isRegularFile(directory.resolve("src/from_intellij.inko")))
        assertFalse(Files.exists(directory.resolve("src/From IntelliJ.inko")))
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
        assertNotNull(InkoModuleNames.problem(""))
        assertNotNull(InkoModuleNames.problem("From IntelliJ"))
        assertNotNull(InkoModuleNames.problem("my-app"))
        assertNotNull(InkoModuleNames.problem("1abc"))
        assertNull(InkoModuleNames.problem("my_app2"))
    }

    fun testGeneratesALibrary() {
        val directory = Files.createDirectories(Path.of(myFixture.tempDirPath, "mylib"))
        InkoProjectGenerator.generate(project, directory, InkoNewProjectSettings(library = true))
        assertEquals("", Files.readString(directory.resolve("src/mylib.inko")))
    }
}
