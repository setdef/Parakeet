package com.setdef.parakeet

import java.nio.file.Path

/** The banner for an inko.pkg that requires a newer Inko than the project uses. */
class InkoRequiredVersionTest : InkoTestCase(onDisk = true) {
    private val root: Path get() = Path.of(myFixture.tempDirPath)

    private fun inko(version: String) = useFakeInko(FakeInkoCli { call ->
        if (call.args == listOf("--version")) FakeInkoCli.ok(stdout = "inko $version\n") else FakeInkoCli.ok(stderr = "[]")
    })

    private fun banner() = InkoRequiredVersionBanner().collectNotificationData(
        project, myFixture.addFileToProject("src/main.inko", "").virtualFile,
    )

    fun testWarnsWhenTheProjectsInkoIsTooOld() {
        myFixture.addFileToProject("inko.pkg", "require https://github.com/a/b 1.0.0 abc\nrequire inko 0.21.1\n")
        inko("0.20.0")
        assertNotNull(banner())
        assertEquals(
            "This project requires Inko 0.21.1 or newer (in inko.pkg), but it's set up to use Inko 0.20.0.",
            InkoRequiredVersionBanner.mismatch(project, root)!!.message,
        )
    }

    fun testNoBannerWhenNewEnoughOrUnspecified() {
        myFixture.addFileToProject("inko.pkg", "require inko 0.9.0\n")
        inko("0.10.0") // Numerically newer, though "0.10.0" < "0.9.0" as text.
        assertNull(banner())

        myFixture.addFileToProject("inko.pkg", "")
        inko("0.1.0")
        assertNull(banner())
    }

    fun testOffersTheNewestSuitableInstalledVersion() {
        val mismatch = InkoRequiredVersionBanner.Mismatch(required = "0.21.0", current = "0.20.0")
        val actions = InkoRequiredVersionBanner.actions(project, mismatch, installed = listOf("0.22.0", "0.21.1", "0.20.0"))
        assertEquals(listOf("Use Inko 0.22.0 (ivm)", "Choose toolchain…"), actions.map { it.first })
    }

    fun testOffersToInstallWhenNoInstalledVersionIsNewEnough() {
        val mismatch = InkoRequiredVersionBanner.Mismatch(required = "0.21.0", current = "0.20.0")
        useFakeIvm(FakeInkoCli(available = true))
        assertEquals(
            listOf("Install a newer Inko with ivm…", "Choose toolchain…"),
            InkoRequiredVersionBanner.actions(project, mismatch, installed = listOf("0.20.0")).map { it.first },
        )
        useFakeIvm(FakeInkoCli(available = false))
        assertEquals(
            listOf("How to install Inko", "Choose toolchain…"),
            InkoRequiredVersionBanner.actions(project, mismatch, installed = emptyList()).map { it.first },
        )
    }

    fun testUsingAVersionSetsTheProjectsToolchain() {
        val settings = InkoProjectSettings.getInstance(project)
        try {
            InkoRequiredVersionBanner.use(project, "0.21.1")
            assertEquals(InkoToolchain.IvmVersion("0.21.1"), settings.toolchain)
        } finally {
            settings.toolchain = InkoToolchain.Auto
        }
    }

    fun testReadsTheRequirementFromTheManifest() {
        myFixture.addFileToProject("inko.pkg", "# comment\n  require   inko   0.21.1  \n")
        assertEquals("0.21.1", Inko.requiredVersion(root))
    }
}
