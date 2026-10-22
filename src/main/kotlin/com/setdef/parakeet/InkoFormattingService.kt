package com.setdef.parakeet

import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.psi.PsiFile

/** Formats Inko files with `inko fmt` for Reformat Code and Actions on Save. */
class InkoFormattingService : AsyncDocumentFormattingService() {
    override fun getFeatures(): Set<FormattingService.Feature> = emptySet()

    // inko fmt reads from stdin, so the file needn't be saved (or even on disk).
    override fun canFormat(file: PsiFile): Boolean = file is InkoFile

    override fun getNotificationGroupId(): String = Inko.NOTIFICATION_GROUP

    override fun getName(): String = "inko fmt"

    override fun createFormattingTask(request: AsyncFormattingRequest): FormattingTask? {
        val cli = InkoCli.getInstance(request.context.project).takeIf { it.isAvailable } ?: return null
        val indicator = EmptyProgressIndicator()

        return object : FormattingTask {
            override fun run() {
                val result = cli.run(listOf("fmt", "-"), request.ioFile?.parentFile?.toPath(), request.documentText, indicator = indicator)
                when {
                    result == null || indicator.isCanceled -> request.onError("inko fmt", "inko fmt didn't run")
                    result.interrupted -> request.onError("inko fmt", "inko fmt timed out")
                    result.succeeded -> request.onTextReady(result.stdout)
                    else -> request.onError("inko fmt", result.stderr.ifBlank { "inko fmt exited with code ${result.exitCode}" })
                }
            }

            override fun cancel(): Boolean {
                indicator.cancel()
                return true
            }

            override fun isRunUnderProgress(): Boolean = true
        }
    }
}
