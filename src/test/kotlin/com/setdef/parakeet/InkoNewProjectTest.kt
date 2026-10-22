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

    fun testGeneratesALibrary() {
        val directory = Files.createDirectories(Path.of(myFixture.tempDirPath, "mylib"))
        InkoProjectGenerator.generate(project, directory, InkoNewProjectSettings(library = true))
        assertEquals("", Files.readString(directory.resolve("src/mylib.inko")))
    }
}
