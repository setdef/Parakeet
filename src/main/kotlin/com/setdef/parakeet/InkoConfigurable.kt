package com.setdef.parakeet

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.layout.ComponentPredicate
import javax.swing.JComboBox

/** Settings | Languages & Frameworks | Inko (per project). */
class InkoConfigurable(private val project: Project) : BoundConfigurable("Inko") {
    private val settings = InkoProjectSettings.getInstance(project)

    override fun createPanel(): DialogPanel {
        val current = settings.toolchain
        val custom = (current as? InkoToolchain.Custom) ?: InkoToolchain.Custom("")
        val choices = InkoToolchain.available().let { if (current in it || current is InkoToolchain.Custom) it else it + current } + custom
        var selected: InkoToolchain = if (current is InkoToolchain.Custom) custom else current
        var customPath = custom.path
        lateinit var combo: JComboBox<InkoToolchain>

        return panel {
            row("Toolchain:") {
                combo = comboBox(choices, textListCellRenderer { it?.let(::describe) })
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
            onApply {
                settings.toolchain = if (selected is InkoToolchain.Custom) InkoToolchain.Custom(customPath.trim()) else selected
                DaemonCodeAnalyzer.getInstance(project).settingsChanged()
                InkoDocIndex.getInstance(project).invalidateAll()
            }
        }
    }

    private fun describe(toolchain: InkoToolchain): String {
        if (toolchain is InkoToolchain.Custom) return "Custom executable…"
        val path = toolchain.executable() ?: return "${toolchain.label} (not found)"
        return "${toolchain.label} — $path"
    }
}
