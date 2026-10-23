package com.setdef.parakeet

import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFileHandler
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap

/**
 * Moving an Inko file (or a directory of them) to another directory updates
 * the imports of its module: `import app.util (…)` becomes `import lib.util
 * (…)`. Moving a file rebinds its references (see [InkoReference.bindToElement]);
 * moving a directory asks this for its files' imports. This also warns when
 * the new place is outside `src` and `test`, where imports can't name it.
 * Inko imports are absolute, so the moved files' own imports don't change.
 */
class InkoMoveFileHandler : MoveFileHandler() {
    override fun canProcessElement(element: PsiFile): Boolean = element is InkoFile

    override fun prepareMovedFile(file: PsiFile, moveDestination: PsiDirectory, oldToNewMap: MutableMap<PsiElement, PsiElement>) {}

    /** An import of [module] (a file being moved). */
    class ModuleUsage(reference: InkoReference, val module: PsiFile) : UsageInfo(reference)

    override fun findUsages(psiFile: PsiFile, newParent: PsiDirectory, searchInComments: Boolean, searchInNonJavaFiles: Boolean): List<UsageInfo> =
        ReferencesSearch.search(psiFile).findAll().filterIsInstance<InkoReference>().map { ModuleUsage(it, psiFile) }

    override fun detectConflicts(
        conflicts: MultiMap<PsiElement, String>,
        elementsToMove: Array<out PsiElement>,
        usages: Array<out UsageInfo>,
        targetDirectory: PsiDirectory,
    ) {
        val target = targetDirectory.virtualFile.fileSystem.getNioPath(targetDirectory.virtualFile) ?: return
        for (file in elementsToMove.filterIsInstance<InkoFile>()) {
            val imported = usages.any { usage -> (usage.reference as? InkoReference)?.let { file in it.multiResolve(false).map { r -> r.element } } == true }
            if (imported && Inko.moduleName(target.resolve(file.name)) == null) {
                conflicts.putValue(file, "${file.name} would be outside src/ and test/, so its imports couldn't name it.")
            }
        }
    }

    override fun retargetUsages(usageInfos: List<UsageInfo>, oldToNewMap: Map<PsiElement, PsiElement>) {
        for (usage in usageInfos.filterIsInstance<ModuleUsage>()) {
            val reference = usage.reference as? InkoReference ?: continue
            // A file moved with its directory keeps its PsiFile, now at the new path.
            val moved = (oldToNewMap[usage.module] ?: usage.module) as? InkoFile ?: continue
            reference.bindToElement(moved)
        }
    }

    override fun updateMovedFile(file: PsiFile) {}
}

object InkoModuleImports {
    /** Rewrites the module path of the import containing [segment] (its last segment) to [module]. */
    fun rewrite(segment: InkoName, module: String) {
        val file = segment.containingFile
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return
        PsiDocumentManager.getInstance(file.project).doPostponedOperationsAndUnblockDocument(document)
        val import = InkoImports.parse(document.charsSequence).at(segment.textRange.startOffset) ?: return
        val start = document.charsSequence.indexOf(import.module, import.range.startOffset)
        if (start < 0) return
        document.replaceString(start, start + import.module.length, module)
        PsiDocumentManager.getInstance(file.project).commitDocument(document)
    }
}
