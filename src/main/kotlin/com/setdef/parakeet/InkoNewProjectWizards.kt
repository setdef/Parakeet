package com.setdef.parakeet

import com.intellij.facet.ui.ValidationResult
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.GeneratorNewProjectWizard
import com.intellij.ide.wizard.NewProjectWizardBaseStep
import com.intellij.ide.wizard.NewProjectWizardChainStep.Companion.nextStep
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.RootNewProjectWizardStep
import com.intellij.openapi.module.Module
import com.intellij.openapi.observable.properties.PropertyGraph
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.DirectoryProjectGeneratorBase
import com.intellij.platform.GeneratorPeerImpl
import com.intellij.platform.ProjectGeneratorPeer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.panel
import java.nio.file.Path
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

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
            with(InkoNewProjectForm) { builder.inkoProjectSettings(settings, moduleName) }
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
        private val form: DialogPanel = panel { with(InkoNewProjectForm) { inkoProjectSettings(settings, moduleName) } }

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
