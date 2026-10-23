package com.setdef.parakeet

import com.intellij.ide.fileTemplates.FileTemplateManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testIntegration.TestCreator
import com.intellij.testIntegration.TestFinder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Where a module's tests live, by the layout `inko init` and the standard
 * library use: `src/app/util.inko` is tested by `test/app/test_util.inko`.
 */
object InkoTestFiles {
    fun isTest(path: Path): Boolean {
        val root = Inko.projectRoot(path)
        return path.startsWith(root.resolve("test")) && path.fileName.toString().startsWith("test_")
    }

    /** The test file for the source file at [path] (which may not exist yet). */
    fun testFor(path: Path): Path? {
        val root = Inko.projectRoot(path)
        val src = root.resolve("src")
        if (!path.startsWith(src)) return null
        val relative = src.relativize(path)
        return root.resolve("test").resolve(relative).resolveSibling("test_${relative.fileName}")
    }

    /** The source file the test file at [path] tests. */
    fun sourceFor(path: Path): Path? {
        if (!isTest(path)) return null
        val root = Inko.projectRoot(path)
        val relative = root.resolve("test").relativize(path)
        return root.resolve("src").resolve(relative).resolveSibling(relative.fileName.toString().removePrefix("test_"))
    }
}

/** Go to Test (⌘⇧T / Ctrl+Shift+T) between a module and its test file. */
class InkoTestFinder : TestFinder {
    override fun findSourceElement(from: PsiElement): PsiElement? = from.containingFile as? InkoFile

    override fun findTestsForClass(element: PsiElement): Collection<PsiElement> =
        listOfNotNull(Inko.pathOf(element.containingFile?.virtualFile)?.let(InkoTestFiles::testFor)?.let { psiFile(element, it) })

    override fun findClassesForTest(element: PsiElement): Collection<PsiElement> =
        listOfNotNull(Inko.pathOf(element.containingFile?.virtualFile)?.let(InkoTestFiles::sourceFor)?.let { psiFile(element, it) })

    override fun isTest(element: PsiElement): Boolean = Inko.pathOf(element.containingFile?.virtualFile)?.let(InkoTestFiles::isTest) == true

    private fun psiFile(context: PsiElement, path: Path): PsiFile? {
        val file = LocalFileSystem.getInstance().findFileByNioFile(path) ?: return null
        return PsiManager.getInstance(context.project).findFile(file)
    }
}

/** Create Test (from Go to Test, when there's none yet): a test file from the "Inko Test" template, in its place under `test/`. */
class InkoTestCreator : TestCreator {
    override fun isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean {
        val test = Inko.pathOf(file.virtualFile)?.let(InkoTestFiles::testFor) ?: return false
        return !Files.exists(test)
    }

    override fun createTest(project: Project, editor: Editor, file: PsiFile) {
        val test = Inko.pathOf(file.virtualFile)?.let(InkoTestFiles::testFor) ?: return
        val content = FileTemplateManager.getInstance(project).getInternalTemplate(InkoNewFileAction.TEST).getText(FileTemplateManager.getInstance(project).defaultProperties)
        val created = WriteCommandAction.writeCommandAction(project).withName("Create Inko Test").compute<com.intellij.openapi.vfs.VirtualFile?, Exception> {
            val directory = VfsUtil.createDirectoryIfMissing(test.parent.toString()) ?: return@compute null
            directory.findChild(test.fileName.toString()) ?: directory.createChildData(this, test.fileName.toString()).also {
                VfsUtil.saveText(it, content)
            }
        } ?: return
        FileEditorManager.getInstance(project).openFile(created, true)
    }
}
