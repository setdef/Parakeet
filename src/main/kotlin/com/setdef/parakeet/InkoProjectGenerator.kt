package com.setdef.parakeet

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path

/** What the user chose for a new Inko project. An empty [moduleName] means "derive it from the directory". */
data class InkoNewProjectSettings(
    var toolchain: InkoToolchain = InkoToolchain.Auto,
    var library: Boolean = false,
    var github: Boolean = false,
    var moduleName: String = "",
)

object InkoModuleNames {
    private val VALID = Regex("[a-z_][a-z0-9_]*")

    /** A valid module name based on a project name, e.g. `from_intellij` for "From IntelliJ". */
    fun suggest(projectName: String): String {
        val name = projectName.trim().removePrefix("inko-").lowercase()
            .replace(Regex("[^a-z0-9]+"), "_").trim('_')
        return when {
            name.isEmpty() -> "main"
            name.first().isDigit() -> "_$name"
            else -> name
        }
    }

    /** Why [name] can't be a module name, or null if it can. */
    fun problem(name: String): String? = when {
        name.isEmpty() -> "Enter a module name."
        !VALID.matches(name) -> "Use lowercase letters, digits, and underscores, not starting with a digit (e.g. ${suggest(name)})."
        else -> null
    }
}

/** Creates an Inko project with `inko init .` and opens its main file. */
object InkoProjectGenerator {
    fun generate(project: Project, directory: Path, settings: InkoNewProjectSettings) {
        InkoProjectSettings.getInstance(project).toolchain = settings.toolchain
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Creating Inko project", false) {
            override fun run(indicator: ProgressIndicator) {
                val arguments = listOf("init", ".") + listOfNotNull("--lib".takeIf { settings.library }, "--github".takeIf { settings.github })
                val result = InkoCli.getInstance(project).run(arguments, directory)
                    ?: return fail("No inko executable found for ${settings.toolchain.label}.")
                if (!result.succeeded) return fail(result.stderr.ifBlank { "inko init exited with code ${result.exitCode}" })
                renameMainModule(directory, settings.moduleName)
                val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory) ?: return
                VfsUtil.markDirtyAndRefresh(false, true, true, root)
            }

            override fun onSuccess() {
                val main = mainFile(directory, settings.moduleName) ?: return
                FileEditorManager.getInstance(project).openFile(main, true)
            }

            private fun fail(message: String) {
                NotificationGroupManager.getInstance().getNotificationGroup(Inko.NOTIFICATION_GROUP)
                    .createNotification("Couldn't create the Inko project", message, NotificationType.ERROR)
                    .notify(project)
            }
        })
    }

    /** The main module inko init creates: `src/<directory name>.inko`, minus any `inko-` prefix. */
    private fun initMainFile(directory: Path): Path =
        directory.resolve("src").resolve("${directory.fileName.toString().removePrefix("inko-")}.${Inko.EXTENSION}")

    /**
     * inko init names the main module after the directory, which may not be a
     * valid module name (e.g. "From IntelliJ"), so rename it to [moduleName].
     */
    private fun renameMainModule(directory: Path, moduleName: String) {
        if (moduleName.isEmpty()) return
        val created = initMainFile(directory)
        val wanted = directory.resolve("src").resolve("$moduleName.${Inko.EXTENSION}")
        if (created != wanted && Files.isRegularFile(created) && !Files.exists(wanted)) Files.move(created, wanted)
    }

    private fun mainFile(directory: Path, moduleName: String): VirtualFile? {
        val path = if (moduleName.isEmpty()) initMainFile(directory) else directory.resolve("src").resolve("$moduleName.${Inko.EXTENSION}")
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
    }

    /** IntelliJ IDEA projects need a module for the project's files to show up. */
    fun ensureModule(project: Project, directory: Path) {
        if (ModuleManager.getInstance(project).modules.isNotEmpty()) return
        val module: Module = WriteAction.computeAndWait<Module, RuntimeException> {
            ModuleManager.getInstance(project).newModule(directory.resolve(".idea").resolve("${directory.fileName}.iml"), "GENERAL_MODULE")
        }
        val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory) ?: return
        if (ModuleRootManager.getInstance(module).contentRoots.isEmpty()) ModuleRootModificationUtil.addContentRoot(module, root)
    }
}
