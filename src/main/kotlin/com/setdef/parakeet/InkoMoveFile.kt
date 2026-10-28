package com.setdef.parakeet

import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.PsiManager
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFileHandler
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap
import java.nio.file.Files
import java.nio.file.Path

/**
 * Moving or renaming Inko files and directories: Cuttlebone's edits (`workspace/willRenameFiles`) to every import
 * of each module that moves (`import app.util (…)` becomes `import lib.util (…)`) and to its uses by its own name,
 * found by the compiler's ids before the move, and made after it. Inko imports are absolute, so the moved files'
 * own imports change only where they import one another.
 */
object InkoFileMoves {
    /** One of Cuttlebone's edits, kept by a marker while the files move. */
    class Edit(file: PsiFile, val marker: RangeMarker, val text: String) : UsageInfo(file, marker.startOffset, marker.endOffset)

    /** Where Cuttlebone has no answer for the imports of what moves (its project doesn't type check, say). */
    class Unanswered(item: PsiFileSystemItem) : UsageInfo(item)

    fun conflict(item: PsiElement) =
        "Cuttlebone has no answer for which imports name the modules in ${(item as PsiFileSystemItem).name} (the project doesn't type check), so they aren't updated."

    /** Where [file] (or a directory) is on disk. */
    fun path(file: com.intellij.openapi.vfs.VirtualFile): Path? = file.fileSystem.getNioPath(file)

    fun modules(path: Path): List<Path> =
        if (Files.isDirectory(path)) Files.walk(path).use { paths -> paths.filter { it.toString().endsWith(".${Inko.EXTENSION}") }.toList() } else listOf(path)

    /** The edits for moving [item] to [to]: none where no module's name changes, [Unanswered] without Cuttlebone's answer. */
    fun edits(item: PsiFileSystemItem, to: Path): List<UsageInfo> {
        val from = path(item.virtualFile) ?: return emptyList()
        // Out of src/ and test/, nothing can import it (a conflict says so), and Cuttlebone has no edits.
        val moved = modules(from).filter { module ->
            val name = Inko.moduleName(module)
            val renamed = Inko.moduleName(to.resolve(from.relativize(module)))
            name != null && renamed != null && name != renamed
        }
        val first = moved.firstNotNullOfOrNull { com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByNioFile(it) } ?: return emptyList()
        val manager = PsiManager.getInstance(item.project)
        val file = manager.findFile(first) ?: return emptyList()
        val answer = CuttleboneQueries.offTheEdt(item.project, "Finding Imports") { CuttleboneQueries.getInstance(item.project).fileRenames(file, from, to) }
            ?.takeIf { !it.editing } ?: return listOf(Unanswered(item))
        return answer.value.orEmpty().flatMap { (uri, edits) ->
            val target = runCatching { com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByNioFile(Path.of(java.net.URI(uri))) }.getOrNull()
            val psi = target?.let(manager::findFile) ?: return@flatMap emptyList()
            val document = FileDocumentManager.getInstance().getDocument(target) ?: return@flatMap emptyList()
            edits.mapNotNull { edit ->
                val start = CuttleboneQueries.offset(document, edit.range.start) ?: return@mapNotNull null
                val end = CuttleboneQueries.offset(document, edit.range.end) ?: return@mapNotNull null
                Edit(psi, document.createRangeMarker(start, end), edit.newText)
            }
        }
    }

    fun make(usages: Collection<UsageInfo>) {
        for (edit in usages.filterIsInstance<Edit>().filter { it.marker.isValid }) {
            val document = edit.marker.document
            val documents = PsiDocumentManager.getInstance(edit.project)
            documents.doPostponedOperationsAndUnblockDocument(document)
            document.replaceString(edit.marker.startOffset, edit.marker.endOffset, edit.text)
            documents.commitDocument(document)
        }
    }
}

/**
 * Moving Inko files or directories: Cuttlebone's edits to their imports ([InkoFileMoves]). They're asked for in
 * [detectConflicts], which IntelliJ gives what moves (a directory, rather than each file in it) and where to: it
 * hands [findUsages] each file of a directory with the directory's destination, not the file's.
 */
class InkoMoveFileHandler : MoveFileHandler() {
    /** The edits for the move being made, until it's made. */
    private val edits = mutableListOf<UsageInfo>()

    override fun canProcessElement(element: PsiFile): Boolean = element is InkoFile

    override fun prepareMovedFile(file: PsiFile, moveDestination: PsiDirectory, oldToNewMap: MutableMap<PsiElement, PsiElement>) {}

    override fun findUsages(psiFile: PsiFile, newParent: PsiDirectory, searchInComments: Boolean, searchInNonJavaFiles: Boolean): List<UsageInfo> = emptyList()

    override fun detectConflicts(
        conflicts: MultiMap<PsiElement, String>,
        elementsToMove: Array<out PsiElement>,
        usages: Array<out UsageInfo>,
        targetDirectory: PsiDirectory,
    ) {
        val target = InkoFileMoves.path(targetDirectory.virtualFile) ?: return
        val moving = elementsToMove.filterIsInstance<PsiFileSystemItem>().filter { it is InkoFile || it is PsiDirectory }
        val found = moving.flatMap { InkoFileMoves.edits(it, target.resolve(it.name)) }
        synchronized(edits) {
            edits.clear()
            edits += found
        }
        for (usage in found.filterIsInstance<InkoFileMoves.Unanswered>()) usage.element?.let { conflicts.putValue(it, InkoFileMoves.conflict(it)) }
        for (file in elementsToMove.filterIsInstance<InkoFile>()) {
            if (Inko.pathOf(file.virtualFile)?.let(Inko::moduleName) != null && Inko.moduleName(target.resolve(file.name)) == null) {
                conflicts.putValue(file, "${file.name} would be outside src/ and test/, so its imports couldn't name it.")
            }
        }
    }

    // Once per moved file: the first makes them all.
    override fun retargetUsages(usageInfos: List<UsageInfo>, oldToNewMap: Map<PsiElement, PsiElement>) =
        InkoFileMoves.make(synchronized(edits) { edits.toList().also { edits.clear() } })

    override fun updateMovedFile(file: PsiFile) {}
}

/** Renaming a directory of Inko modules: Cuttlebone's edits to their imports ([InkoFileMoves]), asked before the rename. */
class InkoDirectoryRenameProcessor : RenamePsiElementProcessor() {
    private val edits = java.util.concurrent.ConcurrentHashMap<PsiDirectory, List<UsageInfo>>()

    override fun canProcessElement(element: PsiElement): Boolean =
        element is PsiDirectory && InkoFileMoves.path(element.virtualFile)?.let { InkoFileMoves.modules(it).isNotEmpty() } == true

    override fun prepareRenaming(element: PsiElement, newName: String, allRenames: MutableMap<PsiElement, String>) {
        val directory = element as PsiDirectory
        val to = InkoFileMoves.path(directory.virtualFile)?.resolveSibling(newName) ?: return
        edits[directory] = InkoFileMoves.edits(directory, to)
    }

    override fun findExistingNameConflicts(element: PsiElement, newName: String, conflicts: MultiMap<PsiElement, String>) {
        if (edits[element].orEmpty().any { it is InkoFileMoves.Unanswered }) conflicts.putValue(element, InkoFileMoves.conflict(element))
    }

    override fun renameElement(element: PsiElement, newName: String, usages: Array<out UsageInfo>, listener: RefactoringElementListener?) {
        val made = edits.remove(element).orEmpty()
        super.renameElement(element, newName, usages, listener)
        InkoFileMoves.make(made)
    }
}
