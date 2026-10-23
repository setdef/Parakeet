package com.setdef.parakeet

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.diagnostic.ErrorReportSubmitter
import com.intellij.openapi.diagnostic.IdeaLoggingEvent
import com.intellij.openapi.diagnostic.SubmittedReportInfo
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.Consumer
import java.awt.Component
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * "Report" in the IDE's error dialog: opens a new issue on setdef/Parakeet in
 * the browser, prefilled with the error, for the user to review and submit
 * under their own GitHub account. Nothing is sent from the IDE.
 */
class InkoErrorReportSubmitter : ErrorReportSubmitter() {
    override fun getReportActionText(): String = "Open a GitHub Issue"

    override fun getPrivacyNoticeText(): String =
        "Opens a new issue on GitHub in your browser, prefilled with this error. Nothing is sent until you submit it there, " +
            "so you can review it first; stack traces can include file paths."

    override fun submit(
        events: Array<out IdeaLoggingEvent>,
        additionalInfo: String?,
        parentComponent: Component,
        consumer: Consumer<in SubmittedReportInfo>,
    ): Boolean {
        val environment = InkoIssueReport.Environment(
            plugin = pluginDescriptor?.version ?: "unknown",
            ide = ApplicationInfo.getInstance().let { "${it.fullApplicationName} (${it.build.asString()})" },
            os = "${SystemInfo.OS_NAME} ${SystemInfo.OS_VERSION} (${SystemInfo.OS_ARCH})",
            java = "${SystemInfo.JAVA_RUNTIME_VERSION} (${SystemInfo.JAVA_VENDOR})",
        )
        val url = InkoIssueReport.url(events.map { it.throwableText }, additionalInfo, environment)
        BrowserUtil.browse(url)
        consumer.consume(SubmittedReportInfo(url, "the issue in your browser", SubmittedReportInfo.SubmissionStatus.NEW_ISSUE))
        return true
    }
}

/** A prefilled "new issue" URL for setdef/Parakeet. */
object InkoIssueReport {
    data class Environment(val plugin: String, val ide: String, val os: String, val java: String)

    private const val NEW_ISSUE = "https://github.com/setdef/Parakeet/issues/new"

    /** GitHub rejects very long URLs, so stack traces are cut to keep the URL under this. */
    const val MAX_URL_LENGTH = 7_500

    fun url(stackTraces: List<String>, description: String?, environment: Environment): String {
        val title = "Error: " + (stackTraces.firstOrNull()?.lineSequence()?.firstOrNull()?.trim()?.take(120) ?: "IDE error")
        var traceLength = stackTraces.sumOf { it.length }
        while (true) {
            val url = "$NEW_ISSUE?labels=bug&title=${encode(title)}&body=${encode(body(stackTraces, description, environment, traceLength))}"
            if (url.length <= MAX_URL_LENGTH || traceLength == 0) return url
            traceLength = (traceLength * 0.8).toInt()
        }
    }

    private fun body(stackTraces: List<String>, description: String?, environment: Environment, traceLength: Int): String {
        val perTrace = if (stackTraces.isEmpty()) 0 else traceLength / stackTraces.size
        val traces = stackTraces.joinToString("\n\n") { trace ->
            if (trace.length <= perTrace) trace else trace.take(perTrace) + "\n… (cut to fit in a URL)"
        }
        return """
            |**What were you doing when this happened?**
            |
            |${description?.takeIf { it.isNotBlank() } ?: "<!-- Describe what you were doing. -->"}
            |
            |**Environment**
            |
            |- Parakeet: ${environment.plugin}
            |- IDE: ${environment.ide}
            |- OS: ${environment.os}
            |- Java: ${environment.java}
            |
            |**Stack trace**
            |
            |```
            |$traces
            |```
            """.trimMargin()
    }

    private fun encode(text: String): String = URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20")
}
