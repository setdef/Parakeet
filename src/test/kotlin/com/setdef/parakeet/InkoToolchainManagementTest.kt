package com.setdef.parakeet

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.ui.components.ActionLink
import com.intellij.util.ui.UIUtil
import javax.swing.JButton

class InkoToolchainManagementTest : InkoTestCase() {
    private val notifications = mutableListOf<Notification>()

    override fun setUp() {
        super.setUp()
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                notifications += notification
            }
        })
    }

    private fun banner() = InkoMissingBanner().collectNotificationData(project, myFixture.configureByText("main.inko", "").virtualFile)

    fun testBannerOnlyWhenInkoIsMissing() {
        assertNotNull(banner())
        useFakeInko(FakeInkoCli(available = true))
        assertNull(banner())
    }

    fun testBannerNamesAMissingIvmVersion() {
        val settings = InkoProjectSettings.getInstance(project)
        try {
            settings.toolchain = InkoToolchain.IvmVersion("0.19.0")
            assertTrue(InkoMissingBanner.message(project).startsWith("Inko 0.19.0 isn't installed"))
        } finally {
            settings.toolchain = InkoToolchain.Auto
        }
        assertTrue(InkoMissingBanner.message(project).startsWith("Parakeet can't find the inko executable"))
    }

    fun testBannerOffersIvmWhenItsInstalled() {
        useFakeIvm(FakeInkoCli(available = true))
        assertEquals(listOf("Install Inko with ivm…", "Choose toolchain…"), InkoMissingBanner.actions(project).map { it.first })
        useFakeIvm(FakeInkoCli(available = false))
        assertEquals(listOf("How to install Inko", "Choose toolchain…"), InkoMissingBanner.actions(project).map { it.first })
    }

    fun testKnownVersionsAreNewestFirst() {
        useFakeIvm(FakeInkoCli { FakeInkoCli.ok(stdout = "0.9.0\n0.10.0\n0.21.1\n\n") })
        assertEquals(listOf("0.21.1", "0.10.0", "0.9.0"), InkoIvm.knownVersions())
    }

    fun testInstallingRunsIvmAndReportsSuccess() {
        val ivm = useFakeIvm(FakeInkoCli())
        var done = false
        InkoIvm.install(project, "0.21.1") { done = true }
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(listOf("install", "0.21.1"), ivm.calls.first().args)
        assertTrue(done)
        assertEquals("Installed Inko 0.21.1." to NotificationType.INFORMATION, notifications.single().let { it.content to it.type })
    }

    fun testFailedInstallsExplainWhyAndLinkToTheGuide() {
        useFakeIvm(FakeInkoCli { FakeInkoCli.failed("error: llvm-config not found") })
        var done = false
        InkoIvm.install(project, "0.21.1") { done = true }
        UIUtil.dispatchAllInvocationEvents()
        assertFalse(done)
        val notification = notifications.single()
        assertEquals(NotificationType.ERROR, notification.type)
        assertTrue(notification.content, notification.content.contains("needs Rust and LLVM 18") && notification.content.contains("llvm-config not found"))
        assertEquals("Installation guide", notification.actions.single().templatePresentation.text)
    }

    fun testRemovingRunsIvm() {
        val ivm = useFakeIvm(FakeInkoCli())
        InkoIvm.remove(project, "0.20.0")
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(listOf("remove", "0.20.0"), ivm.calls.single().args)
        assertEquals("Removed Inko 0.20.0.", notifications.single().content)
    }

    fun testSettingsOfferIvmManagementOnlyWithIvm() {
        useFakeIvm(FakeInkoCli(available = true))
        val withIvm = InkoConfigurable(project).let { configurable ->
            try {
                UIUtil.findComponentsOfType(configurable.createComponent(), JButton::class.java).map { it.text }
            } finally {
                configurable.disposeUIResources()
            }
        }
        assertTrue(withIvm.toString(), withIvm.containsAll(listOf("Install Version…", "Remove Selected Version")))

        useFakeIvm(FakeInkoCli(available = false))
        InkoConfigurable(project).let { configurable ->
            try {
                val links = UIUtil.findComponentsOfType(configurable.createComponent(), ActionLink::class.java).map { it.text }
                assertEquals(listOf("Install ivm to manage Inko versions"), links)
            } finally {
                configurable.disposeUIResources()
            }
        }
    }
}
