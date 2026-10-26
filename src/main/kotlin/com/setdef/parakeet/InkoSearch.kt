package com.setdef.parakeet

import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor
import org.eclipse.lsp4j.Location
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Which names a search finds (Find Usages, Rename, Safe Delete, Change Signature, Move, Call Hierarchy,
 * usage counts): the word index finds the candidates, as for any language, and each is a use of the target
 * by the compiler's answers. Cuttlebone's `textDocument/references` lists every use in the project from one
 * check of all of it ([InkoCompilerReferences]). Where it has no answer (a file in the project doesn't parse,
 * code a declaration's error may affect mentions the name, or the file is outside a project's `src/` and
 * `test/`), each candidate is resolved through `textDocument/definition` instead, a file's requests sent
 * together. Only Find Usages, which changes nothing, then counts Parakeet's own reading of code Cuttlebone
 * can't check, as possible usages.
 */
object InkoSearch {
    /** What Find Usages is searching for now: for these, code being edited counts by Parakeet's own reading. */
    private val readOnly: MutableSet<PsiElement> = ConcurrentHashMap.newKeySet()

    /** Runs [action] (a Find Usages search for [target]), letting code being edited count by Parakeet's reading. */
    fun <T> readOnly(target: PsiElement, action: () -> T): T {
        readOnly += target
        try {
            return action()
        } finally {
            readOnly -= target
        }
    }

    /** Whether [name] (not a declaration, in a context whose target depends on types) uses [target]. */
    fun isReferenceTo(name: InkoName, target: InkoDeclaration): Boolean {
        InkoCompilerReferences.getInstance(name.project).uses(name, target)?.let { return it }
        askAboutTheFile(name)
        val resolution = InkoResolver.resolution(name, exact = target !in readOnly)
        val targets = alsoImplementations(target)
        // A compound assignment (`c.value += 1`) resolves to the setter, and calls the getter too.
        val uses = resolution.targets + InkoResolver.getters(name, resolution.targets)
        return uses.any { found -> targets.any { name.manager.areElementsEquivalent(found, it) } }
    }

    private val implementations = ConcurrentHashMap<PsiElement, Pair<Long, List<PsiElement>>>()

    /** [target], and if it's a trait's method, the methods implementing it: a search for it counts their uses too. */
    fun alsoImplementations(target: InkoDeclaration): List<PsiElement> {
        val stamp = PsiModificationTracker.getInstance(target.project).modificationCount
        implementations[target]?.takeIf { it.first == stamp }?.let { return it.second }
        // Only a method: a trait's implementations are types, which aren't uses of the trait.
        val also = if (InkoCallHierarchy.isMethod(target)) InkoTraitNavigation.implementations(target) else emptyList()
        return (listOf(target) + also).distinct().also { implementations[target] = stamp to it }
    }

    /** Whether Find Usages counted [name] without the compiler's answer for it: Cuttlebone can't check the code there. */
    fun isPossible(name: InkoName): Boolean {
        if (name is InkoDeclaration || !InkoResolver.isGuessed(InkoNameContext.of(name, InkoImports.parse(name.containingFile.text)))) return false
        if (InkoCompilerReferences.getInstance(name.project).covers(name)) return false
        return !InkoResolver.resolution(name, exact = false).exact
    }

    /**
     * The names a search for [target] couldn't decide by the compiler's answers: in code Cuttlebone can't check,
     * where its stale answer or Parakeet's own reading says they may be uses. What changes code leaves them
     * alone, so Rename lists them as conflicts first. On the EDT (as Rename's conflicts are found), only
     * answers the search already had count.
     */
    fun unchecked(target: InkoDeclaration): List<InkoName> {
        val word = target.text.removePrefix("@").removeSuffix("=")
        val references = InkoCompilerReferences.getInstance(target.project)
        val found = mutableListOf<InkoName>()
        PsiSearchHelper.getInstance(target.project).processElementsWithWord({ element, _ ->
            val name = PsiTreeUtil.getParentOfType(element, InkoName::class.java, false)
            if (name != null && name !is InkoDeclaration && references.uses(name, target) == null &&
                InkoResolver.isGuessed(InkoNameContext.of(name, InkoImports.parse(name.containingFile.text)))) {
                val loose = InkoResolver.resolution(name, exact = false)
                val targets = alsoImplementations(target)
                val uses = loose.targets + InkoResolver.getters(name, loose.targets)
                if (!loose.exact && uses.any { found -> targets.any { name.manager.areElementsEquivalent(found, it) } }) found += name
            }
            true
        }, GlobalSearchScope.projectScope(target.project), word, UsageSearchContext.IN_CODE, true)
        return found.distinct()
    }

    private val asked = ConcurrentHashMap<Pair<VirtualFile, String>, Long>()

    /**
     * Asks for the definitions of every name in [name]'s file spelled as it is, at once: after the file's first
     * check, Cuttlebone answers each from the check it kept, so a file's requests together cost about one check.
     */
    private fun askAboutTheFile(name: InkoName) {
        val file = name.containingFile ?: return
        val virtualFile = file.virtualFile ?: return
        val stamp = PsiModificationTracker.getInstance(name.project).modificationCount
        if (asked.put(virtualFile to name.text, stamp) == stamp) return
        val queries = CuttleboneQueries.getInstance(name.project)
        for (other in PsiTreeUtil.findChildrenOfType(file, InkoName::class.java)) {
            if (other.text == name.text && other !is InkoDeclaration) queries.prefetchDefinition(file, other.textRange.startOffset)
        }
    }
}

/**
 * The uses of a declaration by Cuttlebone's `textDocument/references`: every use in an Inko project's `src/`
 * and `test/`, matched by the compiler's ids, from one check of the whole project, kept until the code changes.
 *
 * A request names a position in the project, so for a declaration outside it (the standard library, a
 * dependency) a use is found first: candidates are resolved through `textDocument/definition`, a file at a
 * time, until one is the declaration. A trait's method counts its implementations' uses too: a call on a type
 * that implements it calls the implementation, which the compiler gives its own id.
 */
@Service(Service.Level.PROJECT)
class InkoCompilerReferences(private val project: Project) {
    /** One Inko project's uses of a declaration: the names' start offsets, by file path. */
    private class Uses(val offsets: Map<String, Set<Int>>)

    private val cache = ConcurrentHashMap<Pair<PsiElement, Path>, Optional<Uses>>()
    private val cachedAt = AtomicLong(-1)

    /** For measuring: resolve each candidate through `textDocument/definition`, as when references have no answer. */
    @Volatile
    @org.jetbrains.annotations.TestOnly
    var eachCandidate = false

    /** Whether [name] is a use of [target] by Cuttlebone's references, or null where they have no answer for [name]'s file. */
    fun uses(name: InkoName, target: InkoDeclaration): Boolean? {
        if (eachCandidate) return null
        val path = checkedPath(name) ?: return null
        val root = Inko.projectRoot(path)
        val stamp = PsiModificationTracker.getInstance(project).modificationCount
        if (cachedAt.getAndSet(stamp) != stamp) cache.clear()
        val uses = cache.computeIfAbsent(target to root) { Optional.ofNullable(compute(target, root)) }.orElse(null) ?: return null
        return name.textRange.startOffset in uses.offsets[name.containingFile.virtualFile.path].orEmpty()
    }

    /**
     * Whether a search found [name] in the references of its target, with the code as it is: by the compiler's
     * ids. Since milestone 8, references are given for each name apart (none while code Cuttlebone can't check
     * mentions it), so another search's references say nothing about [name].
     */
    fun covers(name: InkoName): Boolean {
        if (checkedPath(name) == null) return false
        if (cachedAt.get() != PsiModificationTracker.getInstance(project).modificationCount) return false
        val path = name.containingFile.virtualFile.path
        return cache.values.any { uses -> uses.orElse(null)?.offsets?.get(path)?.contains(name.textRange.startOffset) == true }
    }

    private fun checkedPath(name: InkoName): Path? {
        val path = Inko.pathOf(name.containingFile?.virtualFile) ?: return null
        return path.takeIf { isChecked(Inko.projectRoot(it), it) }
    }

    private fun isChecked(root: Path, path: Path) = path.startsWith(root.resolve("src")) || path.startsWith(root.resolve("test"))

    private fun compute(target: InkoDeclaration, root: Path): Uses? {
        val queries = CuttleboneQueries.getInstance(project)
        val offsets = HashMap<String, MutableSet<Int>>()
        val targets = InkoSearch.alsoImplementations(target).filterIsInstance<InkoDeclaration>()
        val elsewhere = mutableListOf<InkoDeclaration>()
        for (declaration in targets) {
            val path = Inko.pathOf(declaration.containingFile.virtualFile)
            if (path == null || Inko.projectRoot(path) != root || !isChecked(root, path)) {
                elsewhere += declaration
                continue
            }
            // No answer while anything in the project can't be type checked (it comes back as code being edited).
            val answer = queries.references(declaration.containingFile, declaration.textRange.startOffset)?.takeIf { !it.editing } ?: return null
            add(answer.value.orEmpty(), offsets)
        }
        if (elsewhere.isNotEmpty() && !throughAUse(elsewhere, root, offsets)) return null
        return Uses(offsets)
    }

    /**
     * Adds the references of each of [declarations] (outside the project at [root]) from a use of it in the
     * project. One that's never used there has none. False if Cuttlebone has no answer.
     */
    private fun throughAUse(declarations: List<InkoDeclaration>, root: Path, offsets: MutableMap<String, MutableSet<Int>>): Boolean {
        val queries = CuttleboneQueries.getInstance(project)
        val word = declarations.first().text.removePrefix("@").removeSuffix("=")
        val spellings = setOf(word, "@$word", "$word=")
        val files = mutableListOf<VirtualFile>()
        PsiSearchHelper.getInstance(project).processCandidateFilesForText(GlobalSearchScope.projectScope(project), UsageSearchContext.IN_CODE, true, word) { file ->
            Inko.pathOf(file)?.takeIf { Inko.projectRoot(it) == root && isChecked(root, it) }?.let { files += file }
            true
        }
        val left = declarations.toMutableList()
        for (virtualFile in files.sortedBy { it.path }) {
            val file = PsiManager.getInstance(project).findFile(virtualFile) as? InkoFile ?: continue
            val names = PsiTreeUtil.findChildrenOfType(file, InkoName::class.java).filter { it.text in spellings && it !is InkoDeclaration }
            names.forEach { queries.prefetchDefinition(file, it.textRange.startOffset) }
            for (name in names) {
                ProgressManager.checkCanceled()
                val answer = queries.definition(file, name.textRange.startOffset, budgetMs = CuttleboneQueries.SEARCH_BUDGET_MS) ?: return false
                if (answer.editing) return false
                val target = answer.value?.firstOrNull()?.let(queries::target) ?: continue
                val declaration = left.firstOrNull { name.manager.areElementsEquivalent(it, target) } ?: continue
                add((queries.references(file, name.textRange.startOffset)?.takeIf { !it.editing } ?: return false).value.orEmpty(), offsets)
                left -= declaration
                if (left.isEmpty()) return true
            }
        }
        return true
    }

    private fun add(locations: List<Location>, offsets: MutableMap<String, MutableSet<Int>>) {
        for (location in locations) {
            val path = runCatching { Path.of(java.net.URI(location.uri)) }.getOrNull()
            val file = path?.let { LocalFileSystem.getInstance().findFileByNioFile(it) }
                ?: com.intellij.openapi.vfs.VirtualFileManager.getInstance().findFileByUrl(location.uri)
                ?: continue
            val document = FileDocumentManager.getInstance().getDocument(file) ?: continue
            val offset = CuttleboneQueries.offset(document, location.range.start) ?: continue
            offsets.getOrPut(file.path) { mutableSetOf() } += offset
        }
    }

    companion object {
        fun getInstance(project: Project): InkoCompilerReferences = project.service()
    }
}

/** Find Usages for Inko declarations: the one search that counts code Cuttlebone can't check, as possible usages. */
class InkoFindUsagesHandlerFactory : FindUsagesHandlerFactory() {
    // Without Cuttlebone, none: IntelliJ asks this before InkoFindUsagesProvider.canFindUsagesFor.
    override fun canFindUsages(element: PsiElement): Boolean = element is InkoDeclaration && !element.isLocal && Cuttlebone.canSearch(element)

    override fun createFindUsagesHandler(element: PsiElement, forHighlightUsages: Boolean): FindUsagesHandler =
        object : FindUsagesHandler(element) {
            override fun processElementUsages(element: PsiElement, processor: Processor<in UsageInfo>, options: FindUsagesOptions): Boolean =
                InkoSearch.readOnly(element) { super.processElementUsages(element, processor, options) }
        }
}
