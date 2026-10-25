package com.setdef.parakeet

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.ui.EditorNotifications

/** The `ivm` command (Inko's version manager), as a seam so tests can fake it. */
interface IvmCli : InkoCli {
    companion object {
        fun getInstance(): IvmCli = service()
    }
}

class IvmProcessCli : InkoProcessCli({ Ivm.executable() }), IvmCli

/** Installing and removing Inko versions with ivm, in the background, with notifications. */
object InkoIvm {
    const val INSTALL_DOCS = "https://docs.inko-lang.org/manual/latest/setup/installation/"
    const val IVM_DOCS = "https://docs.inko-lang.org/manual/latest/setup/ivm/"

    /** Versions ivm can install, newest first. */
    fun knownVersions(): List<String> {
        val result = IvmCli.getInstance().run(listOf("known"), null, timeoutMs = 30_000)?.takeIf { it.succeeded } ?: return emptyList()
        return Ivm.newestFirst(result.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() })
    }

    /** [knownVersions] behind a modal progress dialog, for UI actions (it fetches ivm's release list). */
    fun knownVersionsWithProgress(project: Project?): List<String> =
        ProgressManager.getInstance().runProcessWithProgressSynchronously<List<String>, RuntimeException>(
            { knownVersions() }, "Finding Inko versions", true, project,
        )

    /** Asks before installing (it builds from source), then installs [version] and calls [onSuccess]. */
    fun confirmAndInstall(project: Project, version: String, onSuccess: () -> Unit = {}) {
        val answer = Messages.showYesNoDialog(
            project,
            "Install Inko $version with ivm?\n\nivm builds Inko from source, which needs Rust and LLVM 18 and can take several minutes.",
            "Install Inko",
            Messages.getQuestionIcon(),
        )
        if (answer == Messages.YES) install(project, version, onSuccess)
    }

    /**
     * Installs [version] in the background (ivm builds Inko from source, which
     * needs Rust and LLVM 18 and takes a while), making it ivm's default if
     * there wasn't one, then calls [onSuccess] on the EDT.
     */
    fun install(project: Project?, version: String, onSuccess: () -> Unit = {}) {
        run(project, "Installing Inko $version with ivm", listOf("install", version), onSuccess) { result ->
            if (result.succeeded && InkoToolchain.IvmDefault.executable() == null) {
                IvmCli.getInstance().run(listOf("default", version), null)
            }
            if (result.succeeded) "Installed Inko $version." to NotificationType.INFORMATION
            else "Couldn't install Inko $version. ivm builds Inko from source, which needs Rust and LLVM 18.\n\n${result.stderr.takeLast(1_500)}" to NotificationType.ERROR
        }
    }

    fun remove(project: Project?, version: String, onSuccess: () -> Unit = {}) {
        run(project, "Removing Inko $version with ivm", listOf("remove", version), onSuccess) { result ->
            if (result.succeeded) "Removed Inko $version." to NotificationType.INFORMATION
            else "Couldn't remove Inko $version.\n\n${result.stderr.takeLast(1_500)}" to NotificationType.ERROR
        }
    }

    private fun run(
        project: Project?,
        title: String,
        args: List<String>,
        onSuccess: () -> Unit,
        outcome: (InkoCliResult) -> Pair<String, NotificationType>,
    ) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            @Volatile private var succeeded = false

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val result = IvmCli.getInstance().run(args, null, timeoutMs = 60 * 60_000, indicator = indicator)
                    ?: InkoCliResult(-1, "", "ivm isn't installed.", false)
                if (indicator.isCanceled) return
                succeeded = result.succeeded
                val (message, type) = outcome(result)
                val notification = NotificationGroupManager.getInstance().getNotificationGroup(Inko.NOTIFICATION_GROUP)
                    .createNotification("Inko", message, type)
                if (!succeeded) notification.addAction(NotificationAction.createSimple("Installation guide") { BrowserUtil.browse(INSTALL_DOCS) })
                notification.notify(project)
            }

            override fun onFinished() {
                if (!succeeded) return
                toolchainsChanged()
                onSuccess()
            }
        })
    }

    /** Re-checks everything that depends on which `inko` exists. */
    fun toolchainsChanged() {
        ApplicationManager.getApplication().invokeLater {
            for (project in ProjectManager.getInstance().openProjects) {
                if (project.isDisposed) continue
                DaemonCodeAnalyzer.getInstance(project).settingsChanged()
                InkoDocIndex.getInstance(project).invalidateAll()
                InkoLibraries.getInstance(project).refresh()
                EditorNotifications.getInstance(project).updateAllNotifications()
                // ivm may have changed the default Inko, which needs its own Cuttlebone.
                CuttleboneService.getInstance(project).inkoMayHaveChanged()
            }
        }
    }
}
