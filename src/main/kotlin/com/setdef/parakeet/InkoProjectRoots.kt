package com.setdef.parakeet

import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import java.nio.file.Files
import java.nio.file.Path

/**
 * The Inko projects inside an IDE project: directories with an `inko.pkg`, or
 * content roots whose `src` directory holds Inko files. (A `src` directory
 * alone isn't enough: plenty of non-Inko projects have one.)
 */
object InkoProjectRoots {
    fun find(project: Project): List<Path> {
        val manifests = FilenameIndex.getVirtualFilesByName(Inko.MANIFEST, GlobalSearchScope.projectScope(project))
            .mapNotNull { it.parent?.toNioPath() }
        val contentRoots = ProjectRootManager.getInstance(project).contentRoots
            .mapNotNull { it.fileSystem.getNioPath(it) }
            .filter { hasInkoSources(it.resolve("src")) }
        return (manifests + contentRoots).distinct()
    }

    private fun hasInkoSources(src: Path): Boolean {
        if (!Files.isDirectory(src)) return false
        return Files.walk(src).use { paths -> paths.anyMatch { it.toString().endsWith(".${Inko.EXTENSION}") } }
    }
}

/** Indexes every Inko project when the IDE project opens, and finds their libraries. */
class InkoIndexOnStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        // Finding projects uses the IDE's file name index, which needs a read action once indexing is done.
        val roots = smartReadAction(project) { InkoProjectRoots.find(project) }
        val index = InkoDocIndex.getInstance(project)
        roots.forEach { index.symbols(it) }
        InkoLibraries.getInstance(project).refresh()
    }
}
