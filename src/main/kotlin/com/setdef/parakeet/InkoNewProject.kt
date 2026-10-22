package com.setdef.parakeet

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.facet.ui.ValidationResult
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.GeneratorNewProjectWizard
import com.intellij.ide.wizard.NewProjectWizardBaseStep
import com.intellij.ide.wizard.NewProjectWizardChainStep.Companion.nextStep
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.RootNewProjectWizardStep
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.DirectoryProjectGeneratorBase
import com.intellij.platform.GeneratorPeerImpl
import com.intellij.platform.ProjectGeneratorPeer
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import javax.swing.Icon
import javax.swing.JComponent

/** What the user chose for a new Inko project. */
data class InkoNewProjectSettings(
    var toolchain: InkoToolchain = InkoToolchain.Auto,
    var library: Boolean = false,
    var github: Boolean = false,
)

/** Creates an Inko project with `inko init .` and opens its main file. */
object InkoProjectGenerator {
    fun generate(project: Project, directory: Path, settings: InkoNewProjectSettings) {
        InkoProjectSettings.getInstance(project).toolchain = settings.toolchain
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Creating Inko project", false) {
            override fun run(indicator: ProgressIndicator) {
                val inko = settings.toolchain.executable() ?: return fail("No inko executable found for ${settings.toolchain.label}.")
                val command = GeneralCommandLine(inko.toString(), "init", ".")
                    .withWorkDirectory(directory.toFile())
                    .withCharset(StandardCharsets.UTF_8)
                if (settings.library) command.addParameter("--lib")
                if (settings.github) command.addParameter("--github")
                val output = CapturingProcessHandler(command).runProcess(30_000)
                if (output.exitCode != 0) return fail(output.stderr.ifBlank { "inko init exited with code ${output.exitCode}" })
                val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory) ?: return
                VfsUtil.markDirtyAndRefresh(false, true, true, root)
            }

            override fun onSuccess() {
                val main = mainFile(directory) ?: return
                FileEditorManager.getInstance(project).openFile(main, true)
            }

            private fun fail(message: String) {
                NotificationGroupManager.getInstance().getNotificationGroup(Inko.NOTIFICATION_GROUP)
                    .createNotification("Couldn't create the Inko project", message, NotificationType.ERROR)
                    .notify(project)
            }
        })
    }

    /** `src/<name>.inko`, where inko init strips an `inko-` prefix from the name. */
    private fun mainFile(directory: Path): VirtualFile? {
        val name = directory.fileName.toString().removePrefix("inko-")
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory.resolve("src").resolve("$name.${Inko.EXTENSION}"))
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

    fun Panel.settingsUI(settings: InkoNewProjectSettings) {
        row("Toolchain:") {
            comboBox(InkoToolchain.available(), textListCellRenderer { toolchain ->
                toolchain?.let { t -> t.executable()?.let { "${t.label} — $it" } ?: "${t.label} (not found)" }
            }).bindItem({ settings.toolchain }, { settings.toolchain = it ?: InkoToolchain.Auto })
        }
        row {
            checkBox("Library").bindSelected(settings::library)
                .comment("Create a library instead of an executable (<code>inko init --lib</code>).")
        }
        row {
            checkBox("GitHub Actions workflow").bindSelected(settings::github)
                .comment("Add a workflow that runs <code>inko fmt --check</code> and <code>inko test</code>.")
        }
    }
}

/** File | New | Project | Inko, in IntelliJ IDEA. */
class InkoNewProjectWizard : GeneratorNewProjectWizard {
    override val id: String = "inko"
    override val name: String = "Inko"
    override val icon: Icon = InkoIcons.FILE

    override fun createStep(context: WizardContext): NewProjectWizardStep =
        RootNewProjectWizardStep(context).nextStep(::NewProjectWizardBaseStep).nextStep(::InkoNewProjectStep)

    class InkoNewProjectStep(private val base: NewProjectWizardBaseStep) : AbstractNewProjectWizardStep(base) {
        private val settings = InkoNewProjectSettings()

        override fun setupUI(builder: Panel) {
            with(InkoProjectGenerator) { builder.settingsUI(settings) }
        }

        override fun setupProject(project: Project) {
            val directory = Path.of(base.path, base.name)
            InkoProjectGenerator.ensureModule(project, directory)
            InkoProjectGenerator.generate(project, directory, settings)
        }
    }
}

/** New Project | Inko, in IDEs with the lightweight project dialog (RubyMine, WebStorm, …). */
class InkoDirectoryProjectGenerator : DirectoryProjectGeneratorBase<InkoNewProjectSettings>() {
    override fun getName(): String = "Inko"
    override fun getLogo(): Icon = InkoIcons.FILE

    override fun createPeer(): ProjectGeneratorPeer<InkoNewProjectSettings> = object : GeneratorPeerImpl<InkoNewProjectSettings>() {
        private val settings = InkoNewProjectSettings()
        private val form: DialogPanel = panel { with(InkoProjectGenerator) { settingsUI(settings) } }

        override fun getSettings(): InkoNewProjectSettings {
            form.apply()
            return settings
        }

        override fun getComponent(myLocationField: TextFieldWithBrowseButton, checkValid: Runnable): JComponent = form

        override fun validate() = null
    }

    override fun validate(baseDirPath: String): ValidationResult = ValidationResult.OK

    override fun generateProject(project: Project, baseDir: VirtualFile, settings: InkoNewProjectSettings, module: Module) {
        InkoProjectGenerator.generate(project, baseDir.toNioPath(), settings)
    }
}
