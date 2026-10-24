package com.setdef.parakeet

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.changeSignature.ChangeSignatureHandler
import com.intellij.refactoring.ui.RefactoringDialog
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.EditableModel
import javax.swing.JComponent
import javax.swing.table.AbstractTableModel

/** Change Signature (⌘F6 / Ctrl+F6) for Inko methods: from a method's name or a call to it. */
class InkoChangeSignatureHandler : ChangeSignatureHandler {
    override fun findTargetMember(element: PsiElement): PsiElement? {
        val name = PsiTreeUtil.getParentOfType(element, InkoName::class.java, false) ?: return null
        val declaration = name as? InkoDeclaration ?: InkoResolver.resolve(name).singleOrNull() as? InkoDeclaration ?: return null
        return declaration.takeIf(InkoCallHierarchy::isMethod)
    }

    override fun invoke(project: Project, editor: Editor, file: PsiFile, dataContext: DataContext?) {
        val offset = editor.caretModel.offset
        val element = file.findElementAt(offset)?.takeIf { it.parent is InkoName } ?: file.findElementAt(maxOf(offset - 1, 0))
        val target = element?.let(::findTargetMember)
            ?: return CommonRefactoringUtil.showErrorHint(project, editor, targetNotFoundMessage, TITLE, null)
        open(project, target as InkoDeclaration, editor)
    }

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) {
        val method = elements.singleOrNull()?.let(::findTargetMember) as? InkoDeclaration ?: return
        open(project, method, dataContext?.let { CommonDataKeys.EDITOR.getData(it) })
    }

    private fun open(project: Project, method: InkoDeclaration, editor: Editor?) {
        if (!Cuttlebone.canSearch(method)) return Cuttlebone.showNeeded(project, editor, TITLE)
        InkoChangeSignature.unsupported(method)?.let { message ->
            if (editor != null) CommonRefactoringUtil.showErrorHint(project, editor, message, TITLE, null)
            else CommonRefactoringUtil.showErrorMessage(TITLE, message, null, project)
            return
        }
        val signature = InkoSignatures.at(method.containingFile.text, method.textRange.startOffset) ?: return
        InkoChangeSignatureDialog(project, method, signature).show()
    }

    override fun getTargetNotFoundMessage(): String = "Put the caret on an Inko method's name, or on a call to it."

    private companion object {
        const val TITLE = "Change Signature"
    }
}

/** The method's name, return type, and parameters (add, remove, reorder, rename, retype). */
class InkoChangeSignatureDialog(project: Project, private val method: InkoDeclaration, signature: InkoSignature) :
    RefactoringDialog(project, true) {
    private class Row(val oldIndex: Int, var name: String, var type: String, var value: String = "")

    private val nameField = JBTextField(signature.name)
    private val returnTypeField = JBTextField(signature.returnType.orEmpty())
    private val rows = signature.parameters.mapIndexed { index, parameter -> Row(index, parameter.name, parameter.type) }.toMutableList()

    private val model = object : AbstractTableModel(), EditableModel {
        override fun getRowCount() = rows.size
        override fun getColumnCount() = 3
        override fun getColumnName(column: Int) = listOf("Name", "Type", "Value for calls")[column]
        override fun getValueAt(row: Int, column: Int): Any = rows[row].let {
            when (column) {
                0 -> it.name
                1 -> it.type
                else -> if (it.oldIndex >= 0) "(unchanged)" else it.value
            }
        }

        // Existing parameters keep the arguments calls already pass.
        override fun isCellEditable(row: Int, column: Int) = column < 2 || rows[row].oldIndex < 0

        override fun setValueAt(value: Any?, row: Int, column: Int) {
            val text = value?.toString().orEmpty()
            when (column) {
                0 -> rows[row].name = text.trim()
                1 -> rows[row].type = text.trim()
                else -> rows[row].value = text
            }
            fireTableCellUpdated(row, column)
        }

        override fun addRow() {
            val taken = rows.map { it.name }.toSet()
            val name = generateSequence(1) { it + 1 }.map { if (it == 1) "value" else "value$it" }.first { it !in taken }
            rows += Row(-1, name, "")
            fireTableRowsInserted(rows.size - 1, rows.size - 1)
        }

        override fun removeRow(index: Int) {
            rows.removeAt(index)
            fireTableRowsDeleted(index, index)
        }

        override fun exchangeRows(oldIndex: Int, newIndex: Int) {
            rows.add(newIndex, rows.removeAt(oldIndex))
            fireTableDataChanged()
        }

        override fun canExchangeRows(oldIndex: Int, newIndex: Int) = true
    }

    private val table = JBTable(model).apply { setShowGrid(false) }

    init {
        title = "Change Signature"
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Name:") { cell(nameField).align(AlignX.FILL) }
        row("Return type:") {
            cell(returnTypeField).align(AlignX.FILL).comment("Leave empty to return nothing (Nil).")
        }
        row {
            cell(ToolbarDecorator.createDecorator(table).createPanel()).align(Align.FILL)
                .comment("Calls pass new parameters the value you enter, since Inko has no default arguments.")
        }.resizableRow()
    }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    fun change(): InkoSignatureChange = InkoSignatureChange(
        nameField.text.trim(),
        rows.map { InkoParameterChange(it.oldIndex, it.name, it.type, it.value) },
        returnTypeField.text.trim().ifEmpty { null },
    )

    override fun canRun() {
        if (table.isEditing) table.cellEditor.stopCellEditing()
        InkoChangeSignature.invalid(change())?.let { throw ConfigurationException(it) }
    }

    override fun doAction() {
        invokeRefactoring(InkoChangeSignatureProcessor(project, method, change()))
    }
}
