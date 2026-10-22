package com.setdef.parakeet

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path

class InkoToolchainTest : BasePlatformTestCase() {
    fun testIdsRoundTrip() {
        for (toolchain in listOf(InkoToolchain.Auto, InkoToolchain.IvmDefault, InkoToolchain.IvmVersion("0.20.0"), InkoToolchain.Custom("/x/inko"))) {
            assertEquals(toolchain, InkoToolchain.fromId(toolchain.id))
        }
    }

    fun testProjectSettingSelectsTheExecutable() {
        val settings = InkoProjectSettings.getInstance(project)
        assertEquals(InkoToolchain.Auto, settings.toolchain)
        assertNotNull(InkoExecutable.find(project))

        settings.toolchain = InkoToolchain.Custom("/does/not/exist")
        assertNull(InkoExecutable.find(project))
        settings.toolchain = InkoToolchain.Auto
    }

    fun testIvmVersionsAreOfferedNewestFirst() {
        val versions = Ivm.installedVersions()
        val offered = InkoToolchain.available().filterIsInstance<InkoToolchain.IvmVersion>().map { it.version }
        assertEquals(versions, offered)
        offered.forEach { assertTrue(Files.isExecutable(InkoToolchain.IvmVersion(it).executable()!!)) }
        assertEquals(offered.sortedByDescending { v -> v.split('.').joinToString("") { it.padStart(4, '0') } }, offered)
    }

    fun testAnIvmVersionRunsThatVersion() {
        val version = Ivm.installedVersions().firstOrNull() ?: return
        val executable: Path = InkoToolchain.IvmVersion(version).executable()!!
        assertEquals("inko $version", InkoToolchain.versionOf(executable))
    }
}
