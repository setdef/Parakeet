package com.setdef.parakeet

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class InkoToolchainTest : BasePlatformTestCase() {
    fun testIdsRoundTrip() {
        for (toolchain in listOf(InkoToolchain.Auto, InkoToolchain.IvmDefault, InkoToolchain.IvmVersion("0.20.0"), InkoToolchain.Custom("/x/inko"))) {
            assertEquals(toolchain, InkoToolchain.fromId(toolchain.id))
        }
        assertEquals(InkoToolchain.Auto, InkoToolchain.fromId("nonsense"))
    }

    fun testTheProjectsToolchainChoosesItsInko() {
        val settings = InkoProjectSettings.getInstance(project)
        settings.toolchain = InkoToolchain.Custom("/does/not/exist")
        try {
            assertFalse(InkoCli.getInstance(project).isAvailable)
            assertNull(InkoCli.getInstance(project).run(listOf("--version"), null))
        } finally {
            settings.toolchain = InkoToolchain.Auto
        }
    }

    fun testVersionsSortNumericallyNewestFirst() {
        assertEquals(listOf("0.21.1", "0.10.0", "0.9.2", "0.9"), Ivm.newestFirst(listOf("0.9", "0.10.0", "0.9.2", "0.21.1")))
    }
}
