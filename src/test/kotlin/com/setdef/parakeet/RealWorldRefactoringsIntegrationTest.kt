package com.setdef.parakeet

import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.safeDelete.SafeDeleteHandler
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.usageView.UsageInfo
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import kotlin.io.path.invariantSeparatorsPathString

/**
 * Find Usages and the refactorings on real-world Inko projects ([RealWorldTestCase]), against the real `inko`
 * and `cuttlebone`, in copies of the projects.
 *
 * - Find Usages: for a sample of each kind of declaration (types, traits, their methods and the methods
 *   implementing them, other methods and functions, fields, constants, locals), Parakeet finds exactly the
 *   uses Cuttlebone's `textDocument/references` lists, but for a trait's method, whose search also counts
 *   the calls to the methods implementing it (README), and none of them is a "possible usage".
 * - Rename, Safe Delete, Extract Method, Inline Method and Variable, Introduce Variable, Parameter and
 *   Constant, Change Signature, and Move, each applied at a sample of sites the way the IDE runs them: after
 *   each, the copy must still type check, and its tests (if it has any) still pass. A refactoring may refuse,
 *   if it says why. The copy is put back as it was after each. Tests that fail before any refactoring may fail
 *   after one. Extract Method's placeholder type `Type`, which its template asks to be filled in where the
 *   compiler's type can't be written, counts as asking, not as broken code.
 *
 * scripts/check.sh --full runs these (`-PrealWorld`); the per-commit check doesn't. To run only some of them,
 * set PARAKEET_SWEEP_ONLY to a part of their names (`Find Usages`, `Inline Method`, …), comma-separated.
 */
class RealWorldRefactoringsIntegrationTest : RealWorldTestCase() {
    fun testOpenflow() = project("yorickpeterse", "openflow")
    fun testKvi() = project("yorickpeterse", "kvi")
    fun testInkoMarkdown() = project("yorickpeterse", "inko-markdown")
    fun testInkoSyntax() = project("yorickpeterse", "inko-syntax")
    fun testInkoBuilder() = project("yorickpeterse", "inko-builder")
    fun testInkoWobsite() = project("yorickpeterse", "inko-wobsite")
    fun testYorickpeterseCom() = project("yorickpeterse", "yorickpeterse.com")
    fun testIdoc() = project("inko-lang", "idoc")
    fun testWebsite() = project("inko-lang", "website")

    private val problems: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val outcomes = mutableListOf<String>()

    override fun setUp() {
        super.setUp()
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
    }
    private lateinit var root: Path
    private lateinit var projectName: String

    private fun project(owner: String, name: String) {
        root = open(owner, name) ?: return
        projectName = name
        // inko doc's definitions (for traits and their implementations), as built in the background.
        inBackground(600) { InkoDocIndex.getInstance(project).symbols(root) }
        openInEditor(files().first())

        if (picked("Find Usages")) findUsages()
        refactorings()
        report("refactorings:\n  ${outcomes.joinToString("\n  ")}")

        assertTrue("$name logged errors:\n${errors.joinToString("\n\n")}", errors.isEmpty())
        assertTrue("$name:\n${problems.joinToString("\n")}", problems.isEmpty())
    }

    /** Whether [name] is one of the refactorings PARAKEET_SWEEP_ONLY picks (all, without it). */
    private fun picked(name: String): Boolean =
        System.getenv("PARAKEET_SWEEP_ONLY")?.takeIf { it.isNotBlank() }?.split(',')?.any { name.contains(it.trim()) } ?: true

    private fun report(message: String) = println("$projectName: $message")

    // The project's Inko files, by path.
    private fun files(): List<VirtualFile> = listOf("src", "test").flatMap { directory ->
        val dir = root.resolve(directory).takeIf(Files::isDirectory) ?: return@flatMap emptyList()
        Files.walk(dir).use { paths -> paths.filter { it.toString().endsWith(".${Inko.EXTENSION}") }.sorted().toList() }
    }.map { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it)!! }

    private fun psi(file: VirtualFile): PsiFile = PsiManager.getInstance(project).findFile(file)!!

    private fun relative(file: VirtualFile): String = root.relativize(Path.of(file.path)).invariantSeparatorsPathString

    private fun at(file: VirtualFile, offset: Int): String {
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        return "${relative(file)}:${document.getLineNumber(offset) + 1}"
    }

    /** What kind of declaration [declaration] is, telling a trait's methods and the methods implementing one apart. */
    private fun kindOf(declaration: InkoDeclaration): String {
        val kind = InkoDeclarations.kindOf(declaration)
        if (kind != "method") return kind
        val outline = declaration.containingFile.inkoOutline
        val owner = outline.enclosing(declaration.textRange.startOffset).dropLast(1).lastOrNull() ?: return kind
        return when {
            owner.kind == InkoOutline.Kind.TRAIT -> "trait method"
            owner.kind == InkoOutline.Kind.IMPL && outline.implTrait(owner) != null -> "implementing method"
            else -> kind
        }
    }

    /** Up to [count] of [items], spread through them. */
    private fun <T> spread(items: List<T>, count: Int): List<T> =
        if (items.size <= count) items else (0 until count).map { items[it * (items.size - 1) / (count - 1)] }.distinct()

    /** A sample of the project's declarations of each kind, spread through its files: kind to declarations. */
    private fun declarations(perKind: Int = 3): Map<String, List<InkoDeclaration>> = inBackground {
        files().flatMap { PsiTreeUtil.findChildrenOfType(psi(it), InkoDeclaration::class.java) }
            .groupBy { kindOf(it) }
            .filterKeys { it != "impl" }
            .mapValues { (_, all) -> spread(all, perKind) }
    }

    // Find Usages

    private data class Use(val path: String, val offset: Int)

    private fun use(file: VirtualFile, offset: Int) = Use(file.path, offset)

    private fun describe(use: Use): String {
        val file = LocalFileSystem.getInstance().findFileByPath(use.path)!!
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        val line = document.getLineNumber(use.offset)
        val text = document.getText(com.intellij.openapi.util.TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line))).trim()
        return "${at(file, use.offset)} ($text)"
    }

    /** Where Cuttlebone's [locations] are, as uses. */
    private fun uses(locations: List<org.eclipse.lsp4j.Location>): Set<Use> = locations.mapNotNull { location ->
        val file = LocalFileSystem.getInstance().findFileByNioFile(Path.of(java.net.URI(location.uri))) ?: return@mapNotNull null
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return@mapNotNull null
        CuttleboneQueries.offset(document, location.range.start)?.let { use(file, it) }
    }.toSet()

    /** What Find Usages finds for [declaration], as the IDE runs it, and which of them are possible usages. */
    private fun parakeetUses(declaration: InkoDeclaration): Pair<Set<Use>, Set<Use>> = inBackground(600) {
        val factory = InkoFindUsagesHandlerFactory()
        val handler = if (factory.canFindUsages(declaration)) factory.createFindUsagesHandler(declaration, false) else object : FindUsagesHandler(declaration) {}
        val options = FindUsagesOptions(project).apply {
            isUsages = true
            searchScope = GlobalSearchScope.projectScope(project)
        }
        val found = Collections.synchronizedList(mutableListOf<UsageInfo>())
        handler.processElementUsages(declaration, { found += it; true }, options)
        val names = found.mapNotNull { info -> info.element?.let { PsiTreeUtil.getParentOfType(it, InkoName::class.java, false) } }
        val possible = InkoSearch.readOnly(declaration) { names.filter(InkoSearch::isPossible) }
        names.map { use(it.containingFile.virtualFile, it.textRange.startOffset) }.toSet() to
            possible.map { use(it.containingFile.virtualFile, it.textRange.startOffset) }.toSet()
    }

    /** Cuttlebone's `textDocument/references` for [declaration], without the declaration itself; null if none. */
    private fun cuttleboneUses(declaration: InkoDeclaration): Set<Use>? = inBackground(600) {
        val answer = CuttleboneQueries.getInstance(project).references(declaration.containingFile, declaration.textRange.startOffset, 120_000)
        answer?.value?.let(::uses)?.minus(use(declaration.containingFile.virtualFile, declaration.textRange.startOffset))
    }

    private fun findUsages() {
        val sample = declarations()
        val counts = mutableListOf<String>()
        for ((kind, declarations) in sample.toSortedMap()) {
            for (declaration in declarations) {
                val where = "Find Usages of $kind `${declaration.text}` (${at(declaration.containingFile.virtualFile, declaration.textRange.startOffset)})"
                val expected = cuttleboneUses(declaration) ?: run { problems += "$where: Cuttlebone has no references"; continue }
                val (found, possible) = parakeetUses(declaration)
                for (use in possible) problems += "$where: a possible usage where Cuttlebone answers: ${describe(use)}"
                for (use in expected - found) problems += "$where: misses ${describe(use)}, which Cuttlebone lists"
                val extra = found - expected
                // A trait's method also counts the calls to the methods implementing it, which Cuttlebone gives ids of their own.
                val allowed = if (kind == "trait method") extra.filter { implements(it, declaration) }.toSet() else emptySet()
                for (use in extra - allowed) problems += "$where: finds ${describe(use)}, which Cuttlebone doesn't list"
                counts += "$kind `${declaration.text}` ${found.size}" + if (allowed.isNotEmpty()) " (${allowed.size} through implementations)" else ""
            }
        }
        report("Find Usages: ${counts.joinToString()}")
    }

    /** Whether Cuttlebone resolves [use] to a method of [method]'s name that isn't [method]: one implementing it. */
    private fun implements(use: Use, method: InkoDeclaration): Boolean = inBackground {
        val file = psi(LocalFileSystem.getInstance().findFileByPath(use.path)!!)
        val queries = CuttleboneQueries.getInstance(project)
        val target = queries.definition(file, use.offset, 60_000)?.value?.firstOrNull()?.let(queries::target) as? InkoDeclaration
        target != null && target != method && target.text == method.text && InkoCallHierarchy.isMethod(target)
    }

    // Refactorings

    /**
     * The project's Inko sources, by path from the root. Not the IDE's temporary files (`x.inko~`), which
     * saving writes and removes, so a file can also be gone by the time it's read.
     */
    private fun snapshot(): Map<String, String> = listOf("src", "test").flatMap { directory ->
        val dir = root.resolve(directory).takeIf(Files::isDirectory) ?: return@flatMap emptyList()
        // The walk fails if a temporary file goes while it lists the directory: list it again.
        generateSequence { runCatching { Files.walk(dir).use { paths -> paths.filter { it.toString().endsWith(".${Inko.EXTENSION}") }.toList() } } }
            .take(10).firstNotNullOfOrNull { it.getOrNull() } ?: error("couldn't list $dir")
    }.mapNotNull { path ->
        runCatching { Files.readString(path) }.getOrNull()?.let { root.relativize(path).invariantSeparatorsPathString to it }
    }.toMap()

    private lateinit var original: Map<String, String>

    /** The tests that fail before any refactoring (inko-wobsite has one), or null if there are no tests. */
    private var failingBefore: Set<String>? = null

    /** The names of the tests `inko test` says failed, from its "1. Test: name" lines. */
    private fun failures(output: String): Set<String> =
        Regex("""^\s*\d+\. Test: (.+)$""", RegexOption.MULTILINE).findAll(withoutColors(output).orEmpty()).map { it.groupValues[1].trim() }.toSet()

    private fun saveAll() {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        FileDocumentManager.getInstance().saveAllDocuments()
    }

    /**
     * Finishes every editor's template (Extract Method's names, Introduce Variable's and Constant's in-place
     * renames) as accepting it would, and runs what that leaves for later, so nothing edits the copy after it's
     * put back.
     */
    private fun finishTemplates() {
        for (editor in com.intellij.openapi.editor.EditorFactory.getInstance().allEditors) {
            TemplateManagerImpl.getTemplateState(editor)?.gotoEnd(false)
            com.intellij.refactoring.rename.inplace.InplaceRefactoring.getActiveInplaceRenamer(editor)?.finish(true)
        }
        com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    /**
     * Puts the copy back as it was, through the IDE, so its documents and Cuttlebone have it too. With no editor
     * open, Cuttlebone reads the files from disk: a document left open sometimes had its answers describe the
     * text before (SESSION-real-world-refactorings.md).
     */
    private fun restore() {
        finishTemplates()
        saveAll()
        com.intellij.openapi.fileEditor.ex.FileEditorManagerEx.getInstanceEx(project).closeAllFiles()
        val now = snapshot()
        if (now == original) return
        WriteCommandAction.runWriteCommandAction(project) {
            for (path in now.keys - original.keys) LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve(path))?.delete(this)
            for ((path, text) in original) {
                if (now[path] == text) continue
                val target = root.resolve(path)
                val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)
                    ?: VfsUtil.createDirectoryIfMissing(target.parent.toString())!!.createChildData(this, target.fileName.toString())
                // Through the file, so the disk has it even where its document already did (and the document follows).
                FileDocumentManager.getInstance().getDocument(file)!!.setText(text)
                FileDocumentManager.getInstance().saveDocument(FileDocumentManager.getInstance().getDocument(file)!!)
                if (Files.readString(target) != text) VfsUtil.saveText(file, text)
            }
        }
        saveAll()
        // Nothing left for later may change it again.
        finishTemplates()
        saveAll()
        val left = snapshot().let { now -> (now.keys + original.keys).filter { now[it] != original[it] } }
        check(left.isEmpty()) { "the copy wasn't put back: $left" }
        // A Cuttlebone of its own for the next site, as when the project opens: within one, answers after a site
        // sometimes described a file's text before it was put back (SESSION-real-world-refactorings.md).
        CuttleboneService.getInstance(project).settingsChanged()
        openInEditor(files().first())
        com.intellij.openapi.fileEditor.ex.FileEditorManagerEx.getInstanceEx(project).closeAllFiles()
    }

    /** The lines [now] has that the original doesn't, and the other way round, per file. */
    private fun diff(now: Map<String, String>): String = (now.keys + original.keys).sorted().filter { now[it] != original[it] }.joinToString("\n") { path ->
        val before = original[path]?.lines().orEmpty()
        val after = now[path]?.lines().orEmpty()
        val removed = before.filter { it !in after }.take(12).map { "    - $it" }
        val added = after.filter { it !in before }.take(12).map { "    + $it" }
        "  $path:\n" + (removed + added).joinToString("\n")
    }

    /**
     * Runs [refactoring] (named [name], at [where]), then: if it refused, it must have said why and changed
     * nothing; otherwise the copy must type check, and its tests pass. Either way, the copy is put back.
     */
    private fun attempt(name: String, where: String, refactoring: () -> Unit) {
        if (!picked(name)) return
        val site = "$name at $where"
        val refusal = try {
            refactoring()
            null
        } catch (e: CommonRefactoringUtil.RefactoringErrorHintException) {
            e.message.orEmpty()
        } catch (e: BaseRefactoringProcessor.ConflictsInTestsException) {
            e.messages.joinToString("; ")
        } catch (e: Refused) {
            e.message.orEmpty()
        } catch (e: Throwable) {
            problems += "$site: threw ${e.stackTraceToString().lines().take(8).joinToString("\n")}"
            restore()
            return
        }
        finishTemplates()
        saveAll()
        val now = snapshot()
        when {
            refusal != null -> {
                if (refusal.isBlank()) problems += "$site: refused without saying why"
                if (now != original) problems += "$site: refused, but changed the code:\n${diff(now)}"
                outcomes += "$site: refused: ${refusal.lines().first().take(160)}"
            }
            now == original -> outcomes += "$site: changed nothing"
            else -> {
                val check = cli.run(listOf("check"), root, timeoutMs = 600_000)
                val test = if (check?.succeeded == true && failingBefore != null) cli.run(listOf("test"), root, timeoutMs = 600_000) else null
                // Tests that failed before the refactoring may fail after it too.
                val newlyFailing = test?.takeIf { !it.succeeded }?.let { failures(it.stdout + it.stderr) }
                // Where Extract Method has no type it can write, its template asks for one at the placeholder `Type`,
                // which this run accepts as it is.
                val errors = withoutColors(check?.stderr + check?.stdout).orEmpty().lines().filter { "error(" in it }
                val asksForAType = errors.isNotEmpty() && errors.all { "the symbol '${InkoExtractMethod.PLACEHOLDER}' is undefined" in it }
                when {
                    check?.succeeded != true && asksForAType -> outcomes += "$site: asks for a type (the placeholder `${InkoExtractMethod.PLACEHOLDER}`, ${errors.size} times)"
                    check?.succeeded != true -> problems += "$site: doesn't type check after it:\n" +
                        withoutColors(check?.stderr + check?.stdout).orEmpty().trim().lines().take(6).joinToString("\n") { "    $it" } + "\n${diff(now)}"
                    test != null && !test.succeeded && (newlyFailing.isNullOrEmpty() || !failingBefore!!.containsAll(newlyFailing)) -> problems += "$site: the tests fail after it:\n" +
                        withoutColors(test.stdout + test.stderr).orEmpty().trim().lines().takeLast(10).joinToString("\n") { "    $it" } + "\n${diff(now)}"
                    else -> outcomes += "$site: ok (${now.count { original[it.key] != it.value } + (original.keys - now.keys).size} files changed)"
                }
            }
        }
        restore()
    }

    /** A refactoring's own check said no, before it would show its message. */
    private class Refused(message: String) : RuntimeException(message)

    private fun refuse(message: String?): Nothing = throw Refused(message.orEmpty())

    /** Opens [file], with the caret at [offset]. */
    private fun openAt(file: VirtualFile, offset: Int) {
        // One editor at a time, each opened afresh, as after the copy was put back.
        com.intellij.openapi.fileEditor.ex.FileEditorManagerEx.getInstanceEx(project).closeAllFiles()
        openInEditor(file)
        myFixture.editor.caretModel.moveToOffset(offset)
        myFixture.editor.selectionModel.removeSelection()
    }

    private fun where(declaration: InkoDeclaration) = "`${declaration.text}` (${at(declaration.containingFile.virtualFile, declaration.textRange.startOffset)})"

    /** The declaration at [offset] in [file], found again after the copy was put back. */
    private fun declarationAt(file: VirtualFile, offset: Int): InkoDeclaration =
        PsiTreeUtil.getParentOfType(psi(file).findElementAt(offset), InkoDeclaration::class.java, false)!!

    // What a program or its tests start from: renaming or moving them leaves nothing to run.
    private fun isEntryPoint(declaration: InkoDeclaration) = declaration.text in setOf("main", "Main", "tests")

    /** A new name of the same kind as [declaration]'s. */
    private fun renamed(declaration: InkoDeclaration): String {
        val name = declaration.name
        return when {
            name.first().isUpperCase() && name.none(Char::isLowerCase) -> "${name}_RENAMED"
            name.first().isUpperCase() -> "${name}Renamed"
            name.endsWith("?") || name.endsWith("=") -> name.dropLast(1) + "_renamed" + name.last()
            else -> "${name}_renamed"
        }
    }

    /** Where to refactor: a declaration's name at [offset], or the expression from [offset] to [end]. */
    private class Site(val file: VirtualFile, val offset: Int, val label: String, val end: Int = offset) {
        val range: TextRange get() = TextRange(offset, end)
    }

    private fun sites(declarations: List<InkoDeclaration>): List<Site> =
        declarations.map { Site(it.containingFile.virtualFile, it.textRange.startOffset, where(it)) }

    /** The methods and functions with a body of at least [statements] statements, with their body's statements. */
    private fun methodBodies(statements: Int): List<Pair<VirtualFile, List<TextRange>>> = files().flatMap { file ->
        val text = psi(file).text
        val stream = InkoTokenStream(text)
        psi(file).inkoOutline.all.filter { it.kind == InkoOutline.Kind.METHOD }.mapNotNull { node ->
            val signature = InkoSignatures.at(text, node.nameOffset) ?: return@mapNotNull null
            val brace = stream.indexAt(signature.headerRange.endOffset).takeIf { it < stream.tokens.size && stream.tokens[it].type == InkoTokens.LBRACE } ?: return@mapNotNull null
            if (stream.match(brace) < 0 || stream.tokens[stream.match(brace)].end > node.range.endOffset) return@mapNotNull null
            val ranges = stream.statementsIn(brace).map { TextRange(stream.tokens[it.first].start, stream.tokens[it.last].end) }
            ranges.takeIf { it.size >= statements }?.let { file to it }
        }
    }

    /** Expressions inside method bodies: member calls (`a.b`) and literals, spread through the project. */
    private fun expressions(kind: String, count: Int): List<Site> {
        val found = files().flatMap { file ->
            val text = psi(file).text
            val stream = InkoTokenStream(text)
            val outline = psi(file).inkoOutline
            stream.tokens.indices.mapNotNull { index ->
                val token = stream.tokens[index]
                if (outline.enclosing(token.start).none { it.kind == InkoOutline.Kind.METHOD }) return@mapNotNull null
                val line = text.substring(text.lastIndexOf('\n', token.start - 1) + 1).trimStart()
                if (line.startsWith("import ")) return@mapNotNull null
                when (kind) {
                    "call" -> if (token.type == InkoTokens.IDENTIFIER && stream.tokens.getOrNull(index - 1)?.type == InkoTokens.DOT) {
                        InkoIntroduceVariable.candidatesAt(stream, token.start).firstOrNull()?.let {
                            Site(file, it.startOffset, "`${text.substring(it.startOffset, it.endOffset).replace(Regex("\\s+"), " ").take(60)}` (${at(file, token.start)})", it.endOffset)
                        }
                    } else null
                    else -> if (token.type == InkoTokens.NUMBER || (token.type == InkoTokens.STRING && token.text.length > 2)) {
                        Site(file, token.start, "`${token.text.take(40)}` (${at(file, token.start)})", token.end)
                    } else null
                }
            }
        }
        return spread(found, count)
    }

    private fun refactorings() {
        original = snapshot()
        if (Files.isDirectory(root.resolve("test"))) {
            val test = cli.run(listOf("test"), root, timeoutMs = 600_000)
            failingBefore = if (test?.succeeded == true) emptySet() else failures(test?.stdout + test?.stderr)
            if (failingBefore!!.isNotEmpty()) report("these tests fail before any refactoring, so they may fail after one: $failingBefore")
        }
        // As files and offsets: putting the copy back after each refactoring makes new PSI.
        val declarations = declarations(perKind = 2)
        val sample = declarations.mapValues { (_, all) -> sites(all.filter { !isEntryPoint(it) }) }
        fun of(vararg kinds: String) = kinds.flatMap { sample[it].orEmpty() }
        val lets = sites(inBackground {
            val inSample = declarations["local variable"].orEmpty().filter { PsiTreeUtil.prevVisibleLeaf(it)?.text in setOf("let", "mut") }
            val all = files().flatMap { file -> PsiTreeUtil.findChildrenOfType(psi(file), InkoDeclaration::class.java).filter { it.isLocal && PsiTreeUtil.prevVisibleLeaf(it)?.text == "let" } }
            (inSample + spread(all, 3)).distinct()
        })

        for (site in of("type", "trait", "trait method", "implementing method", "method", "function", "field", "constant", "enum constructor", "local variable")) {
            attempt("Rename", site.label) {
                val declaration = declarationAt(site.file, site.offset)
                myFixture.renameElement(declaration, renamed(declaration), false, false)
            }
        }
        // Used ones (which it refuses, saying where they're used), and unused ones, which it deletes.
        val candidates = declarations(perKind = 1000).filterKeys { it in setOf("type", "trait", "method", "function", "field", "constant") }.values.flatten()
        val unused = sites(spread(inBackground(600) {
            candidates.filter { !isEntryPoint(it) && ReferencesSearch.search(it, GlobalSearchScope.projectScope(project)).findFirst() == null }
        }, 4))
        for (site in of("type", "trait", "method", "function", "field", "constant") + unused) {
            attempt("Safe Delete", site.label) {
                val declaration = declarationAt(site.file, site.offset)
                if (!InkoRefactoringSupportProvider().isSafeDeleteAvailable(declaration)) refuse("Safe Delete isn't available for it")
                SafeDeleteHandler.invoke(project, arrayOf(declaration), false)
            }
        }
        for ((file, statements) in spread(methodBodies(3), 3)) {
            val statement = statements[statements.size / 2]
            attempt("Extract Method", "the statement at ${at(file, statement.startOffset)}") {
                openAt(file, statement.startOffset)
                myFixture.editor.selectionModel.setSelection(statement.startOffset, statement.endOffset)
                InkoExtractMethodHandler().invoke(project, myFixture.editor, psi(file), null)
            }
        }
        for (site in expressions("call", 3)) {
            attempt("Extract Method", site.label) {
                openAt(site.file, site.offset)
                val range = site.range
                myFixture.editor.selectionModel.setSelection(range.startOffset, range.endOffset)
                InkoExtractMethodHandler().invoke(project, myFixture.editor, psi(site.file), null)
            }
        }
        for (site in lets) {
            attempt("Inline Variable", site.label) {
                openAt(site.file, site.offset)
                InkoInlineVariableHandler().inlineElement(project, myFixture.editor, declarationAt(site.file, site.offset))
            }
        }
        for (site in of("method", "function", "implementing method")) {
            attempt("Inline Method", site.label) {
                openAt(site.file, site.offset)
                InkoInlineMethodHandler().inlineElement(project, myFixture.editor, declarationAt(site.file, site.offset))
            }
        }
        for (site in expressions("call", 3)) {
            attempt("Introduce Variable", site.label) {
                openAt(site.file, site.offset)
                val stream = InkoTokenStream(myFixture.editor.document.immutableCharSequence)
                val range = site.range
                // As the handler has it: the compiler's type, asked off the EDT.
                inBackground { InkoIntroduceVariable.borrowsFromATemporary(psi(site.file) as InkoFile, range) }?.let(::refuse)
                val plan = InkoIntroduceVariable.plan(stream, range, InkoIntroduceVariable.occurrences(stream, range).ifEmpty { listOf(range) })
                    .getOrElse { refuse(it.message) }
                InkoIntroduceVariable.apply(project, myFixture.editor, plan)
            }
        }
        for (site in expressions("literal", 3)) {
            attempt("Introduce Constant", site.label) {
                openAt(site.file, site.offset)
                val stream = InkoTokenStream(myFixture.editor.document.immutableCharSequence)
                val range = site.range
                InkoIntroduceConstant.unsupported(stream, range)?.let(::refuse)
                val plan = InkoIntroduceConstant.plan(stream, range, InkoIntroduceVariable.occurrences(stream, range, fileWide = true).ifEmpty { listOf(range) })
                    .getOrElse { refuse(it.message) }
                InkoIntroduceConstant.apply(project, myFixture.editor, plan)
            }
        }
        for (site in expressions("call", 2) + expressions("literal", 2)) {
            attempt("Introduce Parameter", site.label) {
                openAt(site.file, site.offset)
                // As the handler has it: the compiler's type, asked off the EDT.
                val type = inBackground { InkoIntroduceParameterHandler.compilerType(psi(site.file), site.range) }
                val plan = InkoIntroduceParameter.plan(psi(site.file), site.range) { type }.getOrElse { refuse(it.message) }
                InkoIntroduceParameter.processor(project, plan, plan.name, plan.type ?: refuse("its type couldn't be worked out"), replaceAll = true).run()
            }
        }
        for (site in of("method", "function", "trait method", "implementing method")) {
            attempt("Change Signature (add a parameter)", site.label) {
                val method = declarationAt(site.file, site.offset)
                InkoChangeSignature.unsupported(method)?.let(::refuse)
                val signature = InkoSignatures.at(psi(site.file).text, site.offset) ?: refuse("no signature")
                val parameters = signature.parameters.mapIndexed { index, it -> InkoParameterChange(index, it.name, it.type) } +
                    InkoParameterChange(-1, "extra", "Int", "0")
                InkoChangeSignatureProcessor(project, method, InkoSignatureChange(signature.name, parameters, signature.returnType)).run()
            }
            attempt("Change Signature (swap the first two parameters)", site.label) {
                val method = declarationAt(site.file, site.offset)
                InkoChangeSignature.unsupported(method)?.let(::refuse)
                val signature = InkoSignatures.at(psi(site.file).text, site.offset) ?: refuse("no signature")
                if (signature.parameters.size < 2) refuse("fewer than two parameters (not a refusal of Parakeet's)")
                val parameters = signature.parameters.mapIndexed { index, it -> InkoParameterChange(index, it.name, it.type) }
                InkoChangeSignatureProcessor(project, method, InkoSignatureChange(signature.name, listOf(parameters[1], parameters[0]) + parameters.drop(2), signature.returnType)).run()
            }
        }
        for (site in of("type", "trait", "function", "constant")) {
            attempt("Move", site.label) {
                val declaration = InkoMoveDeclaration.movable(declarationAt(site.file, site.offset)) ?: refuse("not movable")
                val module = Inko.moduleName(Path.of(site.file.path)) ?: refuse("not in a module")
                InkoMoveDeclaration.invalidTarget(declaration, "${module}_moved")?.let(::refuse)
                InkoMoveDeclarationProcessor(project, declaration, "${module}_moved").run()
            }
        }
    }
}
