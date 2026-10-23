package com.setdef.parakeet

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import java.nio.file.Path
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
                ?: InkoIvm.knownVersionsWithProgress(project).firstOrNull()
                ?: return Messages.showErrorDialog(project, "ivm didn't list any Inko versions.", "Install Inko")
            InkoIvm.confirmAndInstall(project, version)
        }
    }
}

/**
 * A banner on Inko files when the project's `inko.pkg` requires a newer Inko
 * (`require inko X`) than the project's toolchain, which the compiler would
 * refuse with "this project requires Inko X or newer".
 */
class InkoRequiredVersionBanner : EditorNotificationProvider {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        val path = Inko.pathOf(file) ?: return null
        val mismatch = mismatch(project, Inko.projectRoot(path)) ?: return null
        return Function { editor ->
            EditorNotificationPanel(editor, EditorNotificationPanel.Status.Warning).apply {
                text = mismatch.message
                for ((label, action) in actions(project, mismatch)) createActionLabel(label, action)
            }
        }
    }

    data class Mismatch(val required: String, val current: String) {
        val message: String
            get() = "This project requires Inko $required or newer (in inko.pkg), but it's set up to use Inko $current."
    }

    companion object {
        fun mismatch(project: Project, root: Path): Mismatch? {
            val required = Inko.requiredVersion(root) ?: return null
            val current = InkoCli.getInstance(project).version() ?: return null
            return if (Ivm.isAtLeast(current, required)) null else Mismatch(required, current)
        }

        /** Switch to the newest installed ivm version that's new enough, or install one, or choose a toolchain. */
        fun actions(project: Project, mismatch: Mismatch, installed: List<String> = Ivm.installedVersions()): List<Pair<String, Runnable>> = buildList {
            val suitable = installed.firstOrNull { Ivm.isAtLeast(it, mismatch.required) }
            when {
                suitable != null -> add("Use Inko $suitable (ivm)" to Runnable { use(project, suitable) })
                IvmCli.getInstance().isAvailable -> add("Install a newer Inko with ivm…" to Runnable { offerInstall(project, mismatch.required) })
                else -> add("How to install Inko" to Runnable { BrowserUtil.browse(InkoIvm.INSTALL_DOCS) })
            }
            add("Choose toolchain…" to Runnable { ShowSettingsUtil.getInstance().showSettingsDialog(project, InkoConfigurable::class.java) })
        }

        fun use(project: Project, version: String) {
            InkoProjectSettings.getInstance(project).toolchain = InkoToolchain.IvmVersion(version)
            InkoIvm.toolchainsChanged()
        }

        private fun offerInstall(project: Project, required: String) {
            val version = InkoIvm.knownVersionsWithProgress(project).firstOrNull { Ivm.isAtLeast(it, required) }
                ?: return Messages.showErrorDialog(project, "ivm doesn't know of Inko $required or newer.", "Install Inko")
            InkoIvm.confirmAndInstall(project, version) { use(project, version) }
        }
    }
}
