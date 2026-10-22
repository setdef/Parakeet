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
import com.intellij.openapi.observable.properties.ObservableMutableProperty
import com.intellij.openapi.observable.properties.PropertyGraph
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import javax.swing.event.DocumentEvent
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
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Icon
import javax.swing.JComponent

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
                val inko = settings.toolchain.executable() ?: return fail("No inko executable found for ${settings.toolchain.label}.")
                val command = GeneralCommandLine(inko.toString(), "init", ".")
                    .withWorkDirectory(directory.toFile())
                    .withCharset(StandardCharsets.UTF_8)
                if (settings.library) command.addParameter("--lib")
                if (settings.github) command.addParameter("--github")
                val output = CapturingProcessHandler(command).runProcess(30_000)
                if (output.exitCode != 0) return fail(output.stderr.ifBlank { "inko init exited with code ${output.exitCode}" })
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

    fun Panel.settingsUI(settings: InkoNewProjectSettings, moduleName: ObservableMutableProperty<String>) {
        row("Module name:") {
            textField().bindText(moduleName).columns(COLUMNS_MEDIUM)
                .validationOnInput { field -> InkoModuleNames.problem(field.text)?.let(::error) }
                .validationOnApply { field -> InkoModuleNames.problem(field.text)?.let(::error) }
                .comment("The main module, created as <code>src/&lt;module name&gt;.inko</code>.")
        }
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

        // Follows the project name until the user edits it.
        private val moduleName = propertyGraph.lazyProperty { InkoModuleNames.suggest(base.name) }.apply {
            dependsOn(base.nameProperty) { InkoModuleNames.suggest(base.name) }
        }

        override fun setupUI(builder: Panel) {
            with(InkoProjectGenerator) { builder.settingsUI(settings, moduleName) }
        }

        override fun setupProject(project: Project) {
            settings.moduleName = moduleName.get()
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
        private val moduleName = PropertyGraph().property("")
        private var edited = false
        private val form: DialogPanel = panel { with(InkoProjectGenerator) { settingsUI(settings, moduleName) } }

        override fun getSettings(): InkoNewProjectSettings {
            form.apply()
            settings.moduleName = moduleName.get()
            return settings
        }

        // Follows the last segment of the location until the user edits the module name.
        override fun getComponent(myLocationField: TextFieldWithBrowseButton, checkValid: Runnable): JComponent {
            fun suggest() {
                if (!edited) moduleName.set(InkoModuleNames.suggest(Path.of(myLocationField.text.ifBlank { "main" }).fileName?.toString().orEmpty()))
            }
            suggest()
            myLocationField.textField.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    suggest()
                    checkValid.run()
                }
            })
            moduleName.afterChange { name ->
                if (name != InkoModuleNames.suggest(Path.of(myLocationField.text.ifBlank { "main" }).fileName?.toString().orEmpty())) edited = true
                checkValid.run()
            }
            return form
        }

        override fun validate(): ValidationInfo? = InkoModuleNames.problem(moduleName.get())?.let { ValidationInfo(it) }
    }

    override fun validate(baseDirPath: String): ValidationResult = ValidationResult.OK

    override fun generateProject(project: Project, baseDir: VirtualFile, settings: InkoNewProjectSettings, module: Module) {
        InkoProjectGenerator.generate(project, baseDir.toNioPath(), settings)
    }
}
