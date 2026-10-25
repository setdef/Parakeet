package com.setdef.parakeet

import com.intellij.notification.Notification
import com.intellij.notification.NotificationsManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.testFramework.PlatformTestUtil
import java.nio.file.Files

/**
 * Offering Cuttlebone's build for the project's Inko when there's none, and starting it once the user
 * clicks to download it: the fake Cuttlebone, in an archive like a release's, served locally.
 */
class CuttleboneDownloadOfferTest : CuttleboneTestCase() {
    private lateinit var server: CuttleboneReleaseServer
    private val text = "fn pub main {\n  1.to_string\n}\n"

    @Volatile private var inko: String? = "0.21.1"
    override val inkoVersion: String? get() = inko

    override fun setUp() {
        super.setUp()
        server = CuttleboneReleaseServer()
        cuttleboneLocations.releasesSource = server.releasesSource
        // Nothing on PATH, and nothing set.
        cuttleboneLocations.onPath = { null }
        expireAll()
        useCuttlebone("")
    }

    override fun tearDown() {
        try {
            expireAll()
            server.close()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** The fake Cuttlebone, saying it's [version] for [inko], as an archive's binary. */
    private fun fake(version: String, inko: String): ByteArray {
        val executable = FakeCuttleboneScript.write(fakeDirectory.resolve("build-$version-$inko"), FakeCuttlebone.Script(log = log.toString()), "cuttlebone $version (Inko $inko)")
        return Files.readAllBytes(executable)
    }

    private fun notifications(title: String): List<Notification> = NotificationsManager.getNotificationsManager()
        .getNotificationsOfType(Notification::class.java, project).filter { it.title == title && !it.isExpired }

    private fun expireAll() = NotificationsManager.getNotificationsManager()
        .getNotificationsOfType(Notification::class.java, project).forEach { it.expire() }

    private fun needed(): Notification {
        waitFor("the setup notification") { notifications("Parakeet needs Cuttlebone").isNotEmpty() }
        return notifications("Parakeet needs Cuttlebone").single()
    }

    private fun click(notification: Notification, action: String) {
        Notification.fire(notification, notification.actions.single { it.templateText == action }, null)
    }

    private val archives get() = server.requests.filter { it.endsWith(".tar.gz") }

    private val service get() = CuttleboneService.getInstance(project)

    fun testOffersTheBuildForThisInkoAndDownloadsItOnlyWhenClicked() {
        server.publish("0.10.0", listOf("0.20.0", "0.21.1"), fake("0.10.0", "0.21.1"))
        open("src/main.inko", text)
        val notification = needed()
        assertTrue(notification.content, notification.content.contains("Parakeet can download Cuttlebone 0.10.0 for Inko 0.21.1 (macOS on ARM64"))
        assertEquals(listOf("Download Cuttlebone 0.10.0", "How to set up Cuttlebone", "Settings…"), notification.actions.map { it.templateText })
        Thread.sleep(500)
        assertEquals("nothing is downloaded without the click", emptyList<String>(), archives)
        assertEquals(0, launches())

        click(notification, "Download Cuttlebone 0.10.0")
        waitFor("the download to start") { service.located?.source == Cuttlebone.Source.DOWNLOADED && service.status is Cuttlebone.Status.Running }
        assertEquals(listOf("/releases/download/v0.10.0/cuttlebone-0.10.0-inko-0.21.1-aarch64-apple-darwin.tar.gz"), archives)
        assertEquals(cuttleboneLocations.downloads.resolve("cuttlebone-0.10.0-inko-0.21.1/cuttlebone"), service.located!!.path)
        assertEquals("cuttlebone 0.10.0 (Inko 0.21.1)", service.versionOutput)
        runningServer()
        assertEquals(1, launches())
        assertTrue(notification.isExpired)
        assertEquals(1, notifications("Cuttlebone 0.10.0 is installed").size)
    }

    fun testNoBuildForThisInkoIsSaidAndNothingIsOffered() {
        inko = "0.19.0"
        server.publish("0.10.0", listOf("0.21.1"), fake("0.10.0", "0.21.1"))
        open("src/main.inko", text)
        val notification = needed()
        assertTrue(notification.content, notification.content.contains("No release of Cuttlebone has a build for Inko 0.19.0 on macOS on ARM64"))
        assertEquals(listOf("How to set up Cuttlebone", "Settings…"), notification.actions.map { it.templateText })
    }

    fun testOfflineItSaysItCouldntLook() {
        cuttleboneLocations.releasesSource = TestCuttleboneLocations.OFFLINE
        open("src/main.inko", text)
        val notification = needed()
        assertTrue(notification.content, notification.content.contains("isn't installed") && notification.content.contains("couldn't look for a Cuttlebone to download"))
        assertEquals(listOf("How to set up Cuttlebone", "Settings…"), notification.actions.map { it.templateText })
    }

    fun testAFailedDownloadSaysWhyAndOffersToTryAgain() {
        val (build, _) = server.publish("0.10.0", listOf("0.21.1"), fake("0.10.0", "0.21.1")).builds.single()
        server.respond(java.net.URI(build.url).path, CuttleboneReleaseServer.Response(200, ByteArray(build.size.toInt())))
        open("src/main.inko", text)
        click(needed(), "Download Cuttlebone 0.10.0")
        waitFor("the failure") { notifications("Couldn't download Cuttlebone").isNotEmpty() }
        val failure = notifications("Couldn't download Cuttlebone").single()
        assertTrue(failure.content, failure.content.contains("SHA-256"))
        assertEquals(listOf("Try Again"), failure.actions.map { it.templateText })
        assertTrue(service.status is Cuttlebone.Status.NotFound)
        assertEquals(emptyList<CuttleboneInstall>(), CuttleboneDownloads.all(cuttleboneLocations.downloads))
    }

    fun testWhenInkoChangesTheMatchingBuildIsOfferedOrUsed() {
        server.publish("0.9.5", listOf("0.20.0"), fake("0.9.5", "0.20.0"))
        server.publish("0.10.0", listOf("0.21.1"), fake("0.10.0", "0.21.1"))
        CuttleboneDownloads.install(server.releasesSource.find("0.21.1", CuttleboneReleaseServer.MAC_ARM, null).let { (it as CuttleboneLookup.Found).build }, EmptyProgressIndicator(), cuttleboneLocations.downloads)
        open("src/main.inko", text)
        waitFor("the download in use") { service.status is Cuttlebone.Status.Running }
        assertEquals(Cuttlebone.Source.DOWNLOADED, service.located?.source)

        // ivm switched to 0.20.0: there's no download for it, so it's offered, from the older release that has one.
        inko = "0.20.0"
        service.inkoMayHaveChanged()
        val notification = needed()
        assertTrue(notification.content, notification.content.contains("Parakeet can download Cuttlebone 0.9.5 for Inko 0.20.0"))
        click(notification, "Download Cuttlebone 0.9.5")
        waitFor("the 0.20.0 build in use") { service.versionOutput == "cuttlebone 0.9.5 (Inko 0.20.0)" && service.status is Cuttlebone.Status.Running }

        // And back: its download is there, so it's used without asking.
        expireAll()
        inko = "0.21.1"
        service.inkoMayHaveChanged()
        waitFor("the 0.21.1 build in use") { service.versionOutput == "cuttlebone 0.10.0 (Inko 0.21.1)" && service.status is Cuttlebone.Status.Running }
        assertEquals(emptyList<Notification>(), notifications("Parakeet needs Cuttlebone"))
    }

    fun testAnUnchangedInkoChecksNothingAgain() {
        server.publish("0.10.0", listOf("0.21.1"), fake("0.10.0", "0.21.1"))
        open("src/main.inko", text)
        needed()
        val status = service.status
        service.inkoMayHaveChanged()
        Thread.sleep(500)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertSame(status, service.status)
        assertEquals(1, notifications("Parakeet needs Cuttlebone").size)
    }

    fun testCuttleboneOnPathComesBeforeADownload() {
        server.publish("0.10.0", listOf("0.21.1"), fake("0.10.0", "0.21.1"))
        CuttleboneDownloads.install((server.releasesSource.find("0.21.1", CuttleboneReleaseServer.MAC_ARM, null) as CuttleboneLookup.Found).build, EmptyProgressIndicator(), cuttleboneLocations.downloads)
        val onPath = FakeCuttleboneScript.write(fakeDirectory.resolve("on-path"), FakeCuttlebone.Script(log = log.toString()), "cuttlebone 0.9.0 (Inko 0.21.1)")
        cuttleboneLocations.onPath = { Cuttlebone.Located(onPath, Cuttlebone.Source.PATH) }
        useCuttlebone("")
        open("src/main.inko", text)
        waitFor("Cuttlebone on PATH in use") { service.status is Cuttlebone.Status.Running }
        assertEquals(Cuttlebone.Located(onPath, Cuttlebone.Source.PATH), service.located)
        assertEquals("cuttlebone 0.9.0 (Inko 0.21.1)", service.versionOutput)
    }

    fun testTheSettingComesBeforeADownload() {
        server.publish("0.10.0", listOf("0.21.1"), fake("0.10.0", "0.21.1"))
        CuttleboneDownloads.install((server.releasesSource.find("0.21.1", CuttleboneReleaseServer.MAC_ARM, null) as CuttleboneLookup.Found).build, EmptyProgressIndicator(), cuttleboneLocations.downloads)
        useFake(version = "cuttlebone 0.0.0-fake (Inko 0.21.1)")
        open("src/main.inko", text)
        waitFor("the set Cuttlebone in use") { service.status is Cuttlebone.Status.Running }
        assertEquals(Cuttlebone.Source.SETTING, service.located?.source)
        assertEquals("cuttlebone 0.0.0-fake (Inko 0.21.1)", service.versionOutput)
    }

    fun testANewerBuildIsOfferedOnceAndNeverInstalledUnasked() {
        server.publish("0.10.0", listOf("0.21.1"), fake("0.10.0", "0.21.1"))
        CuttleboneDownloads.install((server.releasesSource.find("0.21.1", CuttleboneReleaseServer.MAC_ARM, null) as CuttleboneLookup.Found).build, EmptyProgressIndicator(), cuttleboneLocations.downloads)
        server.publish("0.11.0", listOf("0.21.1"), fake("0.11.0", "0.21.1"))
        open("src/main.inko", text)
        waitFor("the update notification") { notifications("Cuttlebone 0.11.0 is available").isNotEmpty() }
        val update = notifications("Cuttlebone 0.11.0 is available").single()
        assertEquals(listOf("Download Cuttlebone 0.11.0"), update.actions.map { it.templateText })
        assertEquals("still the installed one", "cuttlebone 0.10.0 (Inko 0.21.1)", service.versionOutput)
        assertEquals(1, archives.size)

        // Said once: not again after a restart, nor in the next session.
        update.expire()
        useCuttlebone("")
        open("src/other.inko", text)
        waitFor("a restart") { service.status is Cuttlebone.Status.Running }
        CuttleboneDownloads.forgetUpdatesThisSession()
        useCuttlebone("")
        open("src/third.inko", text)
        waitFor("another restart") { service.status is Cuttlebone.Status.Running }
        Thread.sleep(1000)
        assertEquals(emptyList<Notification>(), notifications("Cuttlebone 0.11.0 is available"))
        assertEquals(1, archives.size)
    }

    fun testDownloadingAnUpdateStartsIt() {
        server.publish("0.10.0", listOf("0.21.1"), fake("0.10.0", "0.21.1"))
        CuttleboneDownloads.install((server.releasesSource.find("0.21.1", CuttleboneReleaseServer.MAC_ARM, null) as CuttleboneLookup.Found).build, EmptyProgressIndicator(), cuttleboneLocations.downloads)
        server.publish("0.11.0", listOf("0.21.1"), fake("0.11.0", "0.21.1"))
        open("src/main.inko", text)
        waitFor("the update notification") { notifications("Cuttlebone 0.11.0 is available").isNotEmpty() }
        click(notifications("Cuttlebone 0.11.0 is available").single(), "Download Cuttlebone 0.11.0")
        waitFor("the update in use") { service.versionOutput == "cuttlebone 0.11.0 (Inko 0.21.1)" && service.status is Cuttlebone.Status.Running }
        assertEquals(cuttleboneLocations.downloads.resolve("cuttlebone-0.11.0-inko-0.21.1/cuttlebone"), service.located!!.path)
    }
}
