package com.setdef.parakeet

import com.intellij.openapi.observable.properties.ObservableMutableProperty
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer

/** The form both New Project wizards show. */
object InkoNewProjectForm {
    fun Panel.inkoProjectSettings(settings: InkoNewProjectSettings, moduleName: ObservableMutableProperty<String>) {
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
