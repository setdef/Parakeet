package com.setdef.parakeet

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel

/** Settings | Languages & Frameworks | Inko */
class InkoConfigurable : BoundConfigurable("Inko") {
    override fun createPanel(): DialogPanel {
        val state = InkoSettings.getInstance().state
        val detected = InkoExecutable.detect()?.toString()
        return panel {
            row("Inko executable:") {
                textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("Select Inko Executable"),
                )
                    .bindText({ state.executablePath.orEmpty() }, { state.executablePath = it.ifBlank { null } })
                    .align(AlignX.FILL)
                    .comment(
                        if (detected != null) "Leave empty to use the detected executable: $detected"
                        else "No inko executable found on PATH or in ivm's install directory.",
                    )
            }
        }
    }
}
