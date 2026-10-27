package com.setdef.parakeet

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.util.system.CpuArch
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Finding and installing Cuttlebone's prebuilt builds, against releases served locally
 * ([CuttleboneReleaseServer]): nothing here reaches the network.
 */
class CuttleboneDownloadTest : InkoTestCase() {
    private lateinit var server: CuttleboneReleaseServer
    private val root: Path get() = cuttleboneLocations.downloads
    private val mac = CuttleboneReleaseServer.MAC_ARM

    override fun setUp() {
        super.setUp()
        server = CuttleboneReleaseServer()
    }

    override fun tearDown() {
        try {
            server.close()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun find(inko: String, platform: CuttlebonePlatform = mac) = server.releasesSource.find(inko, platform, null)

    private fun binary(version: String, inko: String) = CuttleboneReleaseServer.versionOnly(version, inko)

    private fun install(build: CuttleboneBuild) = CuttleboneDownloads.install(build, EmptyProgressIndicator(), root)

    private fun installFails(build: CuttleboneBuild, reason: String) {
        val error = try {
            install(build)
            null
        } catch (e: IOException) {
            e
        }
        assertNotNull("installed a broken download", error)
        assertTrue(error!!.message, error.message!!.contains(reason))
        assertNothingInstalled()
    }

    /** Nothing installed, and nothing left behind: a failed or partial download is never found. */
    private fun assertNothingInstalled() {
        assertEquals(emptyList<CuttleboneInstall>(), CuttleboneDownloads.all(root))
        if (Files.isDirectory(root)) assertEquals(emptyList<String>(), root.listDirectoryEntries().map { it.name })
    }

    // Platforms

    fun testPlatformsAsTheManifestNamesThem() {
        assertEquals(CuttlebonePlatform("macos", "arm64", "15.1"), CuttlebonePlatform.of("Mac OS X", "15.1", CpuArch.ARM64, false))
        assertEquals(CuttlebonePlatform("macos", "amd64", "15.1"), CuttlebonePlatform.of("Mac OS X", "15.1", CpuArch.X86_64, false))
        assertEquals("an Intel JVM under Rosetta: the ARM64 build runs natively",
            CuttlebonePlatform("macos", "arm64", "15.1"), CuttlebonePlatform.of("Mac OS X", "15.1", CpuArch.X86_64, true))
        assertEquals(CuttlebonePlatform("linux", "amd64", "6.8.0"), CuttlebonePlatform.of("Linux", "6.8.0", CpuArch.X86_64, false))
        assertEquals(CuttlebonePlatform("linux", "arm64", "6.8.0"), CuttlebonePlatform.of("Linux", "6.8.0", CpuArch.ARM64, false))
        assertEquals(CuttlebonePlatform("freebsd", "amd64", "14.1"), CuttlebonePlatform.of("FreeBSD", "14.1", CpuArch.X86_64, false))
        assertNull(CuttlebonePlatform.of("Windows 11", "10.0", CpuArch.X86_64, false))
        assertNull(CuttlebonePlatform.of("Linux", "6.8.0", CpuArch.ARM32, false))
        assertNull(CuttlebonePlatform.of("Linux", "6.8.0", CpuArch.X86, false))
    }

    fun testThisMachinesPlatform() {
        val platform = CuttlebonePlatform.current
        if (System.getProperty("os.name").startsWith("Mac") || System.getProperty("os.name").startsWith("Linux")) {
            assertNotNull(platform)
            assertTrue(platform!!.arch in listOf("arm64", "amd64"))
        }
    }

    fun testMinimumOsVersions() {
        assertTrue(CuttlebonePlatform.meets("15.1", "15.0"))
        assertTrue(CuttlebonePlatform.meets("27.0", "15.0"))
        assertFalse(CuttlebonePlatform.meets("14.6.1", "15.0"))
        assertTrue("a Linux kernel's version", CuttlebonePlatform.meets("6.8.0-45-generic", "4.11"))
        assertFalse(CuttlebonePlatform.meets("4.9.337", "4.11"))
    }

    // The manifest

    fun testReadsAManifest() {
        server.publish("0.14.0", listOf("0.21.1"), binary("0.14.0", "0.21.1"), listOf(mac, CuttleboneReleaseServer.LINUX_AMD))
        val builds = CuttleboneManifest.parse(String(server.let { java.net.URI(it.latestManifest).toURL().readBytes() }))
        assertEquals(listOf("aarch64-apple-darwin", "x86_64-unknown-linux-musl"), builds.map { it.target })
        assertEquals(setOf("0.14.0"), builds.map { it.cuttlebone }.toSet())
    }

    fun testRefusesAManifestOfAnotherSchema() {
        server.respond("/releases/latest/download/manifest.json", CuttleboneReleaseServer.manifest("0.14.0", emptyList(), schema = 2))
        val lookup = find("0.21.1")
        assertTrue(lookup.toString(), lookup is CuttleboneLookup.Failed && lookup.reason.contains("schema is 2"))
    }

    // Finding the build

    fun testFindsTheLatestBuildForThisInkoAndPlatform() {
        server.publish("0.14.0", listOf("0.21.1"), binary("0.14.0", "0.21.1"), listOf(mac, CuttleboneReleaseServer.LINUX_AMD))
        val mac = find("0.21.1") as CuttleboneLookup.Found
        assertEquals("aarch64-apple-darwin", mac.build.target)
        val linux = find("0.21.1", CuttleboneReleaseServer.LINUX_AMD) as CuttleboneLookup.Found
        assertEquals("x86_64-unknown-linux-musl", linux.build.target)
        assertEquals("the latest has it: the older releases aren't asked", listOf("/releases/latest/download/manifest.json", "/releases/latest/download/manifest.json"), server.requests)
    }

    fun testTheReleaseIndexFindsAnOlderReleaseForAnOlderInko() {
        server.publish("0.13.0", listOf("0.20.0"), binary("0.13.0", "0.20.0"))
        server.publish("0.13.5", listOf("0.20.0"), binary("0.13.5", "0.20.0"))
        server.publish("0.14.0", listOf("0.21.1"), binary("0.14.0", "0.21.1"))
        server.serveIndex()
        assertEquals("0.13.5", (find("0.20.0") as CuttleboneLookup.Found).build.cuttlebone)
        assertEquals(
            "the index, then the one manifest it names: not GitHub's API",
            listOf("/releases/latest/download/manifest.json", "/releases/latest/download/index.json", "/releases/download/v0.13.5/manifest.json"),
            server.requests,
        )
    }

    fun testTheReleaseIndexSaysWhenNoReleaseHasThisInko() {
        server.publish("0.14.0", listOf("0.21.1"), binary("0.14.0", "0.21.1"))
        server.serveIndex()
        assertTrue(find("0.19.0") is CuttleboneLookup.NoBuild)
    }

    fun testAReleaseInTheIndexWithoutThisPlatformLeadsToTheNext() {
        server.publish("0.13.0", listOf("0.20.0"), binary("0.13.0", "0.20.0"), listOf(mac))
        server.publish("0.13.5", listOf("0.20.0"), binary("0.13.5", "0.20.0"), listOf(CuttleboneReleaseServer.LINUX_AMD))
        server.publish("0.14.0", listOf("0.21.1"), binary("0.14.0", "0.21.1"))
        server.serveIndex()
        assertEquals("0.13.0", (find("0.20.0") as CuttleboneLookup.Found).build.cuttlebone)
        assertEquals("0.13.5", (find("0.20.0", CuttleboneReleaseServer.LINUX_AMD) as CuttleboneLookup.Found).build.cuttlebone)
    }

    // Every release Parakeet uses (0.13.0 or newer) has an index: without one it can read, only the latest counts.
    fun testAnIndexOfAnotherSchemaLeavesOnlyTheLatest() {
        server.publish("0.13.5", listOf("0.20.0"), binary("0.13.5", "0.20.0"))
        server.publish("0.14.0", listOf("0.21.1"), binary("0.14.0", "0.21.1"))
        server.serveIndex(schema = 2)
        assertTrue(find("0.20.0") is CuttleboneLookup.NoBuild)
        assertEquals("0.14.0", (find("0.21.1") as CuttleboneLookup.Found).build.cuttlebone)
    }

    // Parakeet needs Cuttlebone 0.13.0 or newer: an older release with a build for this Inko isn't one to offer.
    fun testAReleaseOlderThanTheMinimumIsntOffered() {
        server.publish("0.12.0", listOf("0.20.0"), binary("0.12.0", "0.20.0"))
        server.publish("0.13.0", listOf("0.21.1"), binary("0.13.0", "0.21.1"))
        server.serveIndex()
        val lookup = find("0.20.0")
        assertTrue(lookup.toString(), lookup is CuttleboneLookup.NoBuild)
        assertFalse("/releases/download/v0.12.0/manifest.json" in server.requests)
    }

    fun testNoReleaseHasABuildForThisInko() {
        server.publish("0.14.0", listOf("0.21.1"), binary("0.14.0", "0.21.1"))
        val lookup = find("0.19.0")
        assertTrue(lookup.toString(), lookup is CuttleboneLookup.NoBuild)
        assertTrue(lookup.message, lookup.message.contains("No release of Cuttlebone has a build for Inko 0.19.0 on macOS on ARM64"))
    }

    fun testNoBuildForThisPlatform() {
        server.publish("0.14.0", listOf("0.21.1"), binary("0.14.0", "0.21.1"))
        val lookup = find("0.21.1", CuttlebonePlatform("freebsd", "amd64", "14.1"))
        assertTrue(lookup.toString(), lookup is CuttleboneLookup.NoBuild)
        assertTrue(lookup.message, lookup.message.contains("FreeBSD"))
    }

    fun testABuildForANewerOsIsntOffered() {
        server.publish("0.14.0", listOf("0.21.1"), binary("0.14.0", "0.21.1"))
        val lookup = find("0.21.1", CuttlebonePlatform("macos", "arm64", "14.6"))
        assertTrue(lookup.toString(), lookup is CuttleboneLookup.NeedsNewerOs)
        assertTrue(lookup.message, lookup.message.contains("needs version 15.0 or newer, and this is 14.6"))
    }

    fun testNoManifest() {
        val lookup = find("0.21.1")
        assertTrue(lookup.toString(), lookup is CuttleboneLookup.Failed && lookup.reason.contains("HTTP 404"))
    }

    fun testOffline() {
        server.publish("0.14.0", listOf("0.21.1"), binary("0.14.0", "0.21.1"))
        val source = server.releasesSource
        server.close()
        val lookup = source.find("0.21.1", mac, null)
        assertTrue(lookup.toString(), lookup is CuttleboneLookup.Failed)
        assertTrue(lookup.message, lookup.message.startsWith("Parakeet couldn't look for a Cuttlebone to download: couldn't get"))
    }

    fun testTestsNeverReachTheNetwork() {
        val releases = CuttleboneLocations.getInstance().releases
        assertNotSame(CuttleboneReleases.GITHUB, releases)
        assertTrue(releases.find("0.21.1", mac, null) is CuttleboneLookup.Failed)
    }

    // Installing it

    fun testInstallsAVerifiedBuild() {
        val build = server.publish("0.14.0", listOf("0.21.1"), binary("0.14.0", "0.21.1")).builds.single().first
        val installed = install(build)
        assertEquals(root.resolve("cuttlebone-0.14.0-inko-0.21.1").resolve("cuttlebone"), installed.executable)
        assertTrue("made executable", Files.isExecutable(installed.executable))
        assertTrue("with its papers", Files.exists(installed.executable.resolveSibling("LICENSE-MPL-2.0")))
        assertEquals("the installed one, and nothing else", listOf("cuttlebone-0.14.0-inko-0.21.1"), root.listDirectoryEntries().map { it.name })
        assertEquals(installed, CuttleboneDownloads.installed("0.21.1", root))
        assertNull(CuttleboneDownloads.installed("0.20.0", root))
    }

    fun testTheNewestDownloadForAnInkoIsUsed() {
        install(server.publish("0.13.0", listOf("0.21.1"), binary("0.13.0", "0.21.1")).builds.single().first)
        install(server.publish("0.14.0", listOf("0.21.1"), binary("0.14.0", "0.21.1")).builds.single().first)
        install(server.publish("0.13.5", listOf("0.20.0"), binary("0.13.5", "0.20.0")).builds.single().first)
        assertEquals("0.14.0", CuttleboneDownloads.installed("0.21.1", root)!!.cuttlebone)
        assertEquals("0.13.5", CuttleboneDownloads.installed("0.20.0", root)!!.cuttlebone)
    }

    fun testAChecksumMismatchIsntInstalled() {
        val build = server.publish("0.14.0", listOf("0.21.1"), binary("0.14.0", "0.21.1")).builds.single().first
        installFails(build.copy(sha256 = "0".repeat(64)), "SHA-256")
    }

    fun testATruncatedDownloadIsntInstalled() {
        val (build, archive) = server.publish("0.14.0", listOf("0.21.1"), binary("0.14.0", "0.21.1")).builds.single()
        val path = java.net.URI(build.url).path
        // Cut off mid-body: the connection drops short of the length it announced.
        server.respond(path, CuttleboneReleaseServer.Response(200, archive, cutAt = archive.size / 2))
        installFails(build, "")
        // Complete, but shorter than the manifest says.
        server.respond(path, CuttleboneReleaseServer.Response(200, archive.copyOf(archive.size / 2)))
        installFails(build, "bytes, but the manifest says ${build.size}")
    }

    fun testAMissingArchiveIsntInstalled() {
        val build = server.publish("0.14.0", listOf("0.21.1"), binary("0.14.0", "0.21.1")).builds.single().first
        installFails(build.copy(url = "${server.base}/releases/download/v0.14.0/gone.tar.gz"), "HTTP 404")
    }

    fun testOfflineNothingIsInstalled() {
        val build = server.publish("0.14.0", listOf("0.21.1"), binary("0.14.0", "0.21.1")).builds.single().first
        server.close()
        installFails(build, "")
    }

    fun testABuildThatSaysAnotherInkoIsntInstalled() {
        // Its manifest says 0.21.1, but the binary links 0.20.0.
        val build = server.publish("0.14.0", listOf("0.21.1"), binary("0.14.0", "0.20.0")).builds.single().first
        installFails(build, "not a build for Inko 0.21.1")
    }

    fun testAnArchiveWithoutTheBinaryIsntInstalled() {
        val build = server.publish("0.14.0", listOf("0.21.1"), binary("0.14.0", "0.21.1")).builds.single().first
        installFails(build.copy(binary = "elsewhere/cuttlebone"), "has no elsewhere/cuttlebone")
        installFails(build.copy(binary = "../../cuttlebone"), "has no ../../cuttlebone")
    }

    fun testCancellingInstallsNothing() {
        val build = server.publish("0.14.0", listOf("0.21.1"), binary("0.14.0", "0.21.1")).builds.single().first
        val indicator = EmptyProgressIndicator().apply { cancel() }
        try {
            CuttleboneDownloads.install(build, indicator, root)
            fail("installed a cancelled download")
        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            // As it should.
        }
        assertNothingInstalled()
    }

    fun testADownloadInterruptedLongAgoIsCleanedUp() {
        val build = server.publish("0.14.0", listOf("0.21.1"), binary("0.14.0", "0.21.1")).builds.single().first
        Files.createDirectories(root)
        // The IDE was killed mid-download yesterday; another IDE is downloading now.
        val abandoned = Files.createDirectory(root.resolve(".download-1"))
        Files.setLastModifiedTime(abandoned, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 25 * 60 * 60 * 1000L))
        val current = Files.createDirectory(root.resolve(".download-2"))
        install(build)
        assertEquals(listOf(".download-2", "cuttlebone-0.14.0-inko-0.21.1"), root.listDirectoryEntries().map { it.name }.sorted())
        assertTrue(Files.isDirectory(current))
    }

    // Which Cuttlebone is used

    private val onPath = Path.of("/usr/local/bin/cuttlebone")
    private val downloaded = Path.of("/downloads/cuttlebone-0.14.0-inko-0.21.1/cuttlebone")

    private fun locate(setting: String, path: Boolean, download: Boolean, inko: String? = "0.21.1") = Cuttlebone.locate(
        setting,
        inko,
        { if (path) Cuttlebone.Located(onPath, Cuttlebone.Source.PATH) else null },
        { if (download && it == "0.21.1") downloaded else null },
        { if (it == "my-cuttlebone") Path.of("/bin/my-cuttlebone") else null },
    )

    fun testTheSettingComesFirst() {
        val executable = Files.createTempFile("cuttlebone", "").also { it.toFile().setExecutable(true) }
        try {
            assertEquals(Cuttlebone.Located(executable, Cuttlebone.Source.SETTING), locate(executable.toString(), path = true, download = true))
            assertEquals("a name in the setting is looked up on PATH",
                Cuttlebone.Located(Path.of("/bin/my-cuttlebone"), Cuttlebone.Source.SETTING), locate("my-cuttlebone", path = true, download = true))
        } finally {
            Files.delete(executable)
        }
    }

    fun testAMissingSettingIsntReplacedByADownload() {
        assertNull(locate("/nowhere/cuttlebone", path = true, download = true))
    }

    fun testPathComesBeforeADownload() {
        assertEquals(Cuttlebone.Located(onPath, Cuttlebone.Source.PATH), locate("", path = true, download = true))
    }

    fun testADownloadForThisInkoIsUsedWhenThereIsNothingElse() {
        assertEquals(Cuttlebone.Located(downloaded, Cuttlebone.Source.DOWNLOADED), locate("", path = false, download = true))
        assertNull("a download for another Inko isn't", locate("", path = false, download = true, inko = "0.20.0"))
        assertNull("nor one for an unknown Inko", locate("", path = false, download = true, inko = null))
        assertNull(locate("", path = false, download = false))
    }
}
