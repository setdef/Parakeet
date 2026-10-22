package com.setdef.parakeet

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessAdapter
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessOutput
import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService
import com.intellij.psi.PsiFile
import java.nio.charset.StandardCharsets

/** Formats Inko files with `inko fmt` for Reformat Code and Actions on Save. */
class InkoFormattingService : AsyncDocumentFormattingService() {
    override fun getFeatures(): Set<FormattingService.Feature> = emptySet()

    override fun canFormat(file: PsiFile): Boolean = Inko.isInkoFile(file.virtualFile)

    override fun getNotificationGroupId(): String = Inko.NOTIFICATION_GROUP

    override fun getName(): String = "inko fmt"

    override fun createFormattingTask(request: AsyncFormattingRequest): FormattingTask? {
        val inko = InkoExecutable.find(request.context.project) ?: return null
        val command = GeneralCommandLine(inko.toString(), "fmt", "-")
            .withWorkDirectory(request.ioFile?.parentFile)
            .withCharset(StandardCharsets.UTF_8)
        val handler = OSProcessHandler(command)
        val output = ProcessOutput()
        handler.addProcessListener(CapturingProcessAdapter(output))

        return object : FormattingTask {
            override fun run() {
                handler.startNotify()
                handler.processInput.use { it.write(request.documentText.toByteArray(StandardCharsets.UTF_8)) }
                if (!handler.waitFor(TIMEOUT_MS)) {
                    handler.destroyProcess()
                    request.onError("inko fmt", "inko fmt timed out")
                } else if (output.exitCode == 0) {
                    request.onTextReady(output.stdout)
                } else {
                    request.onError("inko fmt", output.stderr.ifBlank { "inko fmt exited with code ${output.exitCode}" })
                }
            }

            override fun cancel(): Boolean {
                handler.destroyProcess()
                return true
            }

            override fun isRunUnderProgress(): Boolean = true
        }
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }
}
