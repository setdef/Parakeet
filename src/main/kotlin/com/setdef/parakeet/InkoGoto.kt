package com.setdef.parakeet

import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Icon
import com.setdef.parakeet.InkoDefinitionKind as Kind

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

/** Indexes every Inko project when the IDE project opens, so Go to Class/Symbol has results right away. */
class InkoIndexOnStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        // Finding projects uses the IDE's file name index, which needs a read action once indexing is done.
        val roots = smartReadAction(project) { InkoProjectRoots.find(project) }
        val index = InkoDocIndex.getInstance(project)
        roots.forEach { index.symbols(it) }
    }
}

/** A definition in Go to Class/Symbol and Search Everywhere. */
class InkoDefinitionItem(private val project: Project, val definition: InkoDefinition) : NavigationItem, ItemPresentation {
    override fun getName(): String = definition.name

    override fun getPresentation(): ItemPresentation = this

    override fun getPresentableText(): String = definition.name

    override fun getLocationString(): String = listOfNotNull(definition.module, definition.owner).joinToString(".")

    override fun getIcon(unused: Boolean): Icon = definition.icon

    override fun canNavigate(): Boolean = true

    override fun canNavigateToSource(): Boolean = true

    override fun navigate(requestFocus: Boolean) {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(definition.file) ?: return
        val psiFile = PsiManager.getInstance(project).findFile(file)
        val document = psiFile?.viewProvider?.document
        val offset = if (psiFile != null && document != null) {
            InkoDefinitionSite.find(psiFile, definition, Inko.offsetOf(document, definition.line, definition.column)).textOffset
        } else {
            0
        }
        OpenFileDescriptor(project, file, offset).navigate(requestFocus)
    }
}

/** Offers definitions of [kinds] from every Inko project's index, including the standard library and dependencies. */
abstract class InkoGotoContributor(private val kinds: Set<Kind>) : ChooseByNameContributorEx {
    private fun symbols(project: Project): List<InkoSymbols> {
        val index = InkoDocIndex.getInstance(project)
        return InkoProjectRoots.find(project).mapNotNull(index::symbols)
    }

    override fun processNames(processor: Processor<in String>, scope: GlobalSearchScope, filter: IdFilter?) {
        val project = scope.project ?: return
        symbols(project).flatMap { symbols -> symbols.names.filter { name -> symbols.named(name).any { it.kind in kinds } } }
            .distinct()
            .forEach { if (!processor.process(it)) return }
    }

    override fun processElementsWithName(name: String, processor: Processor<in NavigationItem>, parameters: FindSymbolParameters) {
        val project = parameters.project
        val scope = parameters.searchScope
        for (definition in symbols(project).flatMap { it.named(name) }.filter { it.kind in kinds }.distinct()) {
            val file = LocalFileSystem.getInstance().findFileByNioFile(definition.file)
            // Project files are always offered; the standard library and dependencies only when searching libraries.
            val inScope = file != null && scope.contains(file) || parameters.isSearchInLibraries
            if (inScope && !processor.process(InkoDefinitionItem(project, definition))) return
        }
    }
}

/** Go to Class (⌘O): types and traits. */
class InkoGotoClassContributor : InkoGotoContributor(setOf(Kind.TYPE, Kind.TRAIT))

/** Go to Symbol (⌥⌘O): every named definition. */
class InkoGotoSymbolContributor : InkoGotoContributor(Kind.entries.toSet() - Kind.MODULE)
