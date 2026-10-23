package com.setdef.parakeet

import com.intellij.ide.actions.CreateFileFromTemplateAction
import com.intellij.ide.actions.CreateFileFromTemplateDialog
import com.intellij.ide.fileTemplates.FileTemplate
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile

/** File | New | Inko File: a module, a test file, or a program with `Main`. */
open class InkoNewFileAction : CreateFileFromTemplateAction("Inko File", "Create an Inko module, test, or program", InkoIcons.FILE), DumbAware {
    override fun buildDialog(project: Project, directory: PsiDirectory, builder: CreateFileFromTemplateDialog.Builder) {
        builder.setTitle("New Inko File")
            .addKind("Module", InkoIcons.FILE, MODULE)
            .addKind("Test (test_*.inko)", InkoIcons.FILE, TEST)
            .addKind("Program (with Main)", InkoIcons.FILE, PROGRAM)
            .setValidator(object : InputValidatorEx {
                override fun getErrorText(inputString: String): String? = InkoModuleNames.problem(inputString.removeSuffix(".${Inko.EXTENSION}"))
                override fun checkInput(inputString: String): Boolean = getErrorText(inputString) == null
                override fun canClose(inputString: String): Boolean = checkInput(inputString)
            })
    }

    override fun getActionName(directory: PsiDirectory?, newName: String, templateName: String?): String = "Create Inko file $newName"

    // `inko test` only runs files named test_*.inko.
    override fun createFile(name: String, templateName: String, dir: PsiDirectory): PsiFile? {
        val base = name.removeSuffix(".${Inko.EXTENSION}")
        return super.createFile(if (templateName == TEST && !base.startsWith("test_")) "test_$base" else base, templateName, dir)
    }

    companion object {
        const val MODULE = "Inko Module"
        const val TEST = "Inko Test"
        const val PROGRAM = "Inko Program"
    }
}
