package com.setdef.parakeet

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.layout.ComponentPredicate
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox

/** Settings | Languages & Frameworks | Inko (per project). */
class InkoConfigurable(private val project: Project) : BoundConfigurable("Inko") {
    private val settings = InkoProjectSettings.getInstance(project)
    private lateinit var combo: JComboBox<InkoToolchain>
    private var custom = InkoToolchain.Custom("")

    /** Auto-detect, ivm's default, each installed ivm version, the current choice if missing, and a custom path. */
    private fun choices(current: InkoToolchain): List<InkoToolchain> =
        InkoToolchain.available().let { if (current in it || current is InkoToolchain.Custom) it else it + current } + custom

    override fun createPanel(): DialogPanel {
        val current = settings.toolchain
        custom = (current as? InkoToolchain.Custom) ?: InkoToolchain.Custom("")
        var selected: InkoToolchain = if (current is InkoToolchain.Custom) custom else current
        var customPath = custom.path
        val selectedIsIvmVersion = object : ComponentPredicate() {
            override fun invoke() = combo.selectedItem is InkoToolchain.IvmVersion
            override fun addListener(listener: (Boolean) -> Unit) {
                combo.addItemListener { listener(invoke()) }
            }
        }

        return panel {
            row("Toolchain:") {
                combo = comboBox(choices(current), textListCellRenderer { it?.let(::describe) })
                    .bindItem({ selected }, { selected = it ?: InkoToolchain.Auto })
                    .align(AlignX.FILL)
                    .comment("Auto-detect uses <code>inko</code> on PATH, then ivm's default version.")
                    .component
            }
            row("Executable:") {
                textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("Select Inko Executable"),
                    project,
                )
                    .bindText({ customPath }, { customPath = it })
                    .align(AlignX.FILL)
            }.visibleIf(object : ComponentPredicate() {
                override fun invoke() = combo.selectedItem is InkoToolchain.Custom
                override fun addListener(listener: (Boolean) -> Unit) {
                    combo.addItemListener { listener(invoke()) }
                }
            })
            row("ivm:") {
                if (IvmCli.getInstance().isAvailable) {
                    button("Install Version…") { installVersion() }
                    button("Remove Selected Version") { removeSelectedVersion() }.enabledIf(selectedIsIvmVersion)
                    rowComment("ivm builds Inko from source, which needs Rust and LLVM 18 and can take several minutes.")
                } else {
                    browserLink("Install ivm to manage Inko versions", InkoIvm.IVM_DOCS)
                }
            }
            onApply {
                settings.toolchain = if (selected is InkoToolchain.Custom) InkoToolchain.Custom(customPath.trim()) else selected
                DaemonCodeAnalyzer.getInstance(project).settingsChanged()
                InkoDocIndex.getInstance(project).invalidateAll()
                InkoIvm.toolchainsChanged()
            }
        }
    }

    private fun installVersion() {
        val known = ProgressManager.getInstance().runProcessWithProgressSynchronously<List<String>, RuntimeException>(
            { InkoIvm.knownVersions() }, "Finding Inko versions", true, project,
        )
        val installable = known - Ivm.installedVersions().toSet()
        if (installable.isEmpty()) {
            Messages.showInfoMessage(project, "Every Inko version ivm knows about is already installed.", "Install Inko")
            return
        }
        val version = Messages.showEditableChooseDialog(
            "Inko version to install with ivm:", "Install Inko", null, installable.toTypedArray(), installable.first(), null,
        ) ?: return
        InkoIvm.install(project, version) { refresh(select = InkoToolchain.IvmVersion(version)) }
    }

    private fun removeSelectedVersion() {
        val version = (combo.selectedItem as? InkoToolchain.IvmVersion)?.version ?: return
        val answer = Messages.showYesNoDialog(project, "Remove Inko $version from ivm?", "Remove Inko", Messages.getQuestionIcon())
        if (answer == Messages.YES) InkoIvm.remove(project, version) { refresh(select = InkoToolchain.Auto) }
    }

    /** Re-lists toolchains after ivm installs or removes one. */
    private fun refresh(select: InkoToolchain) {
        combo.model = DefaultComboBoxModel(choices(settings.toolchain).toTypedArray())
        combo.selectedItem = select
    }

    private fun describe(toolchain: InkoToolchain): String {
        if (toolchain is InkoToolchain.Custom) return "Custom executable…"
        val path = toolchain.executable() ?: return "${toolchain.label} (not found)"
        return "${toolchain.label} — $path"
    }
}
