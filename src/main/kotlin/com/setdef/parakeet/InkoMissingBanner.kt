package com.setdef.parakeet

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import java.util.function.Function
import javax.swing.JComponent

/** A banner on Inko files when the project's `inko` can't be found, offering to install or choose one. */
class InkoMissingBanner : EditorNotificationProvider {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        if (file.extension != Inko.EXTENSION || InkoCli.getInstance(project).isAvailable) return null
        return Function { editor ->
            EditorNotificationPanel(editor, EditorNotificationPanel.Status.Warning).apply {
                text = message(project)
                for ((label, action) in actions(project)) createActionLabel(label, action)
            }
        }
    }

    companion object {
        fun message(project: Project): String = when (val toolchain = InkoProjectSettings.getInstance(project).toolchain) {
            is InkoToolchain.IvmVersion -> "Inko ${toolchain.version} isn't installed, so errors, formatting, navigation, and running are unavailable."
            else -> "Parakeet can't find the inko executable, so errors, formatting, navigation, and running are unavailable."
        }

        /** The banner's actions, as labels and what they do. */
        fun actions(project: Project): List<Pair<String, Runnable>> = buildList {
            if (IvmCli.getInstance().isAvailable) {
                add("Install Inko with ivm…" to Runnable { offerInstall(project) })
            } else {
                add("How to install Inko" to Runnable { BrowserUtil.browse(InkoIvm.INSTALL_DOCS) })
            }
            add("Choose toolchain…" to Runnable { ShowSettingsUtil.getInstance().showSettingsDialog(project, InkoConfigurable::class.java) })
        }

        /** Installs the project's ivm version if it names one, else the newest, after confirming. */
        private fun offerInstall(project: Project) {
            val version = (InkoProjectSettings.getInstance(project).toolchain as? InkoToolchain.IvmVersion)?.version
                ?: InkoIvm.knownVersions().firstOrNull()
                ?: return Messages.showErrorDialog(project, "ivm didn't list any Inko versions.", "Install Inko")
            val answer = Messages.showYesNoDialog(
                project,
                "Install Inko $version with ivm?\n\nivm builds Inko from source, which needs Rust and LLVM 18 and can take several minutes.",
                "Install Inko",
                Messages.getQuestionIcon(),
            )
            if (answer == Messages.YES) InkoIvm.install(project, version)
        }
    }
}
