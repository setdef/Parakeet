package com.setdef.parakeet

import com.intellij.execution.configuration.EnvironmentVariablesTextFieldWithBrowseButton
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.layout.ComponentPredicate
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.fields.ExpandableTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import javax.swing.JComponent

class InkoRunConfigurationEditor(project: Project) : SettingsEditor<InkoRunConfiguration>() {
    private val command = ComboBox(InkoCommand.entries.toTypedArray()).apply {
        renderer = textListCellRenderer { it?.label }
    }
    private val target = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("Select Inko File"))
    }
    private val arguments = ExpandableTextField()
    private val release = JBCheckBox("Release build (--release)")
    private val staticLinking = JBCheckBox("Statically link C libraries (--static)")
    private val compileTarget = ComboBox<String>().apply {
        isEditable = true
        // The host by default; `inko targets` lists the rest, fetched off the UI thread.
        ApplicationManager.getApplication().executeOnPooledThread {
            val targets = InkoTargets.list(InkoCli.getInstance(project))
            ApplicationManager.getApplication().invokeLater({
                val current = item
                targets.forEach { if ((0 until itemCount).none { i -> getItemAt(i) == it }) addItem(it) }
                item = current
            }, ModalityState.any())
        }
    }
    private val workingDirectory = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Select Working Directory"))
    }
    // The (Project) constructor that replaces this one doesn't exist in 2025.2, the oldest supported version.
    @Suppress("DEPRECATION")
    private val environment = EnvironmentVariablesTextFieldWithBrowseButton()

    override fun resetEditorFrom(configuration: InkoRunConfiguration) {
        val options = configuration.options
        command.selectedItem = configuration.command
        target.text = options.target.orEmpty()
        arguments.text = options.arguments.orEmpty()
        release.isSelected = options.release
        staticLinking.isSelected = options.staticLinking
        compileTarget.item = options.compileTarget.orEmpty()
        workingDirectory.text = options.workingDirectory.orEmpty()
        environment.envs = options.environment
        environment.isPassParentEnvs = options.passParentEnvironment
    }

    override fun applyEditorTo(configuration: InkoRunConfiguration) {
        val options = configuration.options
        options.command = (command.selectedItem as? InkoCommand ?: InkoCommand.RUN).cli
        options.target = target.text.trim().ifEmpty { null }
        options.arguments = arguments.text.trim().ifEmpty { null }
        options.release = release.isSelected
        options.staticLinking = staticLinking.isSelected
        options.compileTarget = (compileTarget.editor.item as? String ?: compileTarget.item)?.trim()?.ifEmpty { null }
        options.workingDirectory = workingDirectory.text.trim().ifEmpty { null }
        options.environment = environment.envs.toMutableMap()
        options.passParentEnvironment = environment.isPassParentEnvs
    }

    override fun createEditor(): JComponent = panel {
        row("Command:") { cell(command) }
        row("File or filter:") {
            cell(target).align(AlignX.FILL)
                .comment("The file to run, build, or check. For test, optionally a test file or a test name to filter by.")
        }
        row("Arguments:") { cell(arguments).align(AlignX.FILL) }
        // Each command only offers the options it accepts.
        val selected = { command.selectedItem as? InkoCommand ?: InkoCommand.RUN }
        fun accepts(option: (InkoCommand) -> Boolean) = object : ComponentPredicate() {
            override fun invoke() = option(selected())
            override fun addListener(listener: (Boolean) -> Unit) = command.addItemListener { listener(invoke()) }
        }
        row { cell(release) }.enabledIf(accepts { it.release })
        row { cell(staticLinking) }.enabledIf(accepts { it.static })
        row("Target:") {
            cell(compileTarget).align(AlignX.FILL).comment("Leave empty for this computer. Other targets need their runtime installed (see Inko's cross-compilation guide).")
        }.enabledIf(accepts { it.target })
        row("Working directory:") {
            cell(workingDirectory).align(AlignX.FILL).comment("Defaults to the project root of the file.")
        }
        row("Environment variables:") { cell(environment).align(AlignX.FILL) }
    }
}
