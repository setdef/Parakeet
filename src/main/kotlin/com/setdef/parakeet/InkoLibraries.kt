package com.setdef.parakeet

import com.intellij.icons.AllIcons
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsListener
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Icon
import kotlin.io.path.name

/** An entry under External Libraries: the standard library, or a project's dependencies. */
class InkoLibrary(private val label: String, val root: VirtualFile) : SyntheticLibrary(), ItemPresentation {
    override fun getSourceRoots(): Collection<VirtualFile> = listOf(root)

    override fun getPresentableText(): String = label

    override fun getIcon(unused: Boolean): Icon = AllIcons.Nodes.PpLibFolder

    override fun equals(other: Any?): Boolean = other is InkoLibrary && other.label == label && other.root == root

    override fun hashCode(): Int = 31 * label.hashCode() + root.hashCode()
}

/**
 * The standard library (of the project's Inko) and each Inko project's `dep`
 * directory, as libraries: browsable under External Libraries and included in
 * Go to File and Find in Files' library scopes. `dep` and `build` are excluded
 * from the project, so dependencies and build output aren't project code.
 */
@Service(Service.Level.PROJECT)
class InkoLibraries(private val project: Project, private val scope: CoroutineScope) {
    @Volatile var libraries: List<InkoLibrary> = emptyList()
        private set

    /** Recomputes the libraries in the background (synchronously when tests call it on the UI thread). */
    fun refresh() {
        val application = ApplicationManager.getApplication()
        if (application.isUnitTestMode && application.isDispatchThread) update() else scope.launch(Dispatchers.IO) { update() }
    }

    private fun update() {
        val roots = InkoProjectRootsCache.find(project)
        exclude(roots)
        val new = buildList {
            val cli = InkoCli.getInstance(project)
            InkoTestRuns.standardLibrary(cli)?.let(::find)?.let { std ->
                add(InkoLibrary(listOfNotNull("Inko", cli.version(), "standard library").joinToString(" "), std))
            }
            for (root in roots) {
                val dep = root.resolve("dep").takeIf(Files::isDirectory)?.let(::find) ?: continue
                add(InkoLibrary(if (roots.size > 1) "Inko dependencies (${root.name})" else "Inko dependencies", dep))
            }
        }
        val old = libraries
        if (old == new) return
        libraries = new
        val change = {
            WriteAction.run<RuntimeException> {
                AdditionalLibraryRootsListener.fireAdditionalLibraryChanged(
                    project, "Inko", old.map { it.root }, new.map { it.root }, "Inko",
                )
            }
        }
        if (ApplicationManager.getApplication().isDispatchThread) change() else ApplicationManager.getApplication().invokeAndWait(change)
    }

    /** Excludes each new Inko project's `dep` and `build` from its module, once. */
    private fun exclude(roots: List<Path>) {
        val settings = InkoProjectSettings.getInstance(project)
        for (root in roots) {
            if (root.toString() in settings.state.excludedRoots) continue
            val directory = find(root) ?: continue
            val module = ApplicationManager.getApplication().runReadAction<com.intellij.openapi.module.Module?> {
                ModuleUtilCore.findModuleForFile(directory, project)
            } ?: continue
            val change = {
                ModuleRootModificationUtil.updateModel(module) { model ->
                    val entry = model.contentEntries.firstOrNull { it.file?.let { content -> com.intellij.openapi.vfs.VfsUtilCore.isAncestor(content, directory, false) } == true }
                        ?: return@updateModel
                    for (name in listOf("dep", "build")) {
                        val url = directory.url + "/" + name
                        if (entry.excludeFolderUrls.none { it == url }) entry.addExcludeFolder(url)
                    }
                }
            }
            if (ApplicationManager.getApplication().isDispatchThread) change() else ApplicationManager.getApplication().invokeAndWait(change)
            // Assigning (rather than adding to) the set marks the settings as changed, so they're saved.
            settings.state.excludedRoots = (settings.state.excludedRoots + root.toString()).toMutableSet()
        }
    }

    private fun find(path: Path): VirtualFile? = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)

    companion object {
        fun getInstance(project: Project): InkoLibraries = project.service()
    }
}

class InkoLibraryRootsProvider : AdditionalLibraryRootsProvider() {
    // Skips roots deleted since the last refresh (e.g. `dep` removed), until the next refresh drops them.
    private fun current(project: Project) = InkoLibraries.getInstance(project).libraries.filter { it.root.isValid }

    override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> = current(project)

    override fun getRootsToWatch(project: Project): Collection<VirtualFile> = current(project).map { it.root }
}

/** Finding Inko projects uses the file name index, so it needs a (smart) read action. */
private object InkoProjectRootsCache {
    fun find(project: Project): List<Path> = ApplicationManager.getApplication().runReadAction<List<Path>> { InkoProjectRoots.find(project) }
}
