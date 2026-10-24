package com.setdef.parakeet

import junit.framework.TestCase
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class InkoIssueReportTest : TestCase() {
    private val environment = InkoIssueReport.Environment("0.6.0", "IntelliJ IDEA 2026.2.3 (IU-262.10968.63)", "Mac OS X 27.0 (aarch64)", "21.0.12")

    private fun parameters(url: String): Map<String, String> = URI(url).rawQuery.split('&').associate { pair ->
        val (key, value) = pair.split('=', limit = 2)
        key to URLDecoder.decode(value, StandardCharsets.UTF_8)
    }

    fun testPrefillsTitleDescriptionEnvironmentAndTrace() {
        val trace = "java.lang.RuntimeException: Read access is allowed from inside read-action only\n\tat com.setdef.parakeet.X.y(X.kt:1)"
        val url = InkoIssueReport.url(listOf(trace), "Opened a project & waited", environment)
        assertTrue(url, url.startsWith("https://github.com/setdef/Parakeet/issues/new?"))
        val query = parameters(url)
        assertEquals("bug", query["labels"])
        assertEquals("Error: java.lang.RuntimeException: Read access is allowed from inside read-action only", query["title"])
        val body = query.getValue("body")
        assertTrue(body, body.contains("Opened a project & waited"))
        assertTrue(body, body.contains("- Parakeet: 0.6.0") && body.contains("- IDE: IntelliJ IDEA 2026.2.3 (IU-262.10968.63)"))
        assertTrue(body, body.contains("```\n$trace\n```"))
    }

    fun testReportsThePluginsVersion() {
        val version = java.nio.file.Files.readAllLines(java.nio.file.Path.of("gradle.properties")).single { it.startsWith("version") }.substringAfter('=').trim()
        assertEquals(version, InkoErrorReportSubmitter.version)
    }

    fun testAsksForADescriptionWhenThereIsNone() {
        assertTrue(parameters(InkoIssueReport.url(listOf("boom"), null, environment)).getValue("body").contains("<!-- Describe what you were doing. -->"))
    }

    fun testCutsLongTracesToFitInAUrl() {
        val trace = "java.lang.IllegalStateException: boom\n" + (1..2_000).joinToString("\n") { "\tat com.example.Frame$it.call(Frame.kt:$it)" }
        val url = InkoIssueReport.url(listOf(trace), "x", environment)
        assertTrue(url.length.toString(), url.length <= InkoIssueReport.MAX_URL_LENGTH)
        val body = parameters(url).getValue("body")
        assertTrue(body.contains("java.lang.IllegalStateException: boom"))
        assertTrue(body.contains("… (cut to fit in a URL)"))
    }
}
