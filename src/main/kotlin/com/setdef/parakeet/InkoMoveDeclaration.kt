package com.setdef.parakeet

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.move.MoveCallback
import com.intellij.refactoring.move.MoveHandlerDelegate
import com.intellij.refactoring.ui.RefactoringDialog
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.ui.TextFieldWithAutoCompletion
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.usageView.BaseUsageViewDescriptor
import com.intellij.usageView.UsageInfo
import com.intellij.usageView.UsageViewDescriptor
import com.intellij.util.containers.MultiMap
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent

/**
 * Move (F6) for a top-level Inko declaration (a type, trait, constant, or
 * module method) to another module, new or existing. A type takes its `impl`
 * blocks from the same file along. Imports follow: modules that imported it
 * from the old module import it from the new one, the old module imports it
 * if it still uses it, and the moved code brings the imports it needs.
 */
class InkoMoveDeclarationHandler : MoveHandlerDelegate() {
    override fun canMove(elements: Array<out PsiElement>, targetContainer: PsiElement?, reference: PsiReference?): Boolean =
        targetContainer == null && elements.singleOrNull()?.let(InkoMoveDeclaration::movable) != null

    override fun tryToMove(element: PsiElement, project: Project, dataContext: DataContext?, reference: PsiReference?, editor: Editor?): Boolean {
        val declaration = InkoMoveDeclaration.movable(element) ?: return false
        if (!Cuttlebone.canSearch(declaration)) {
            Cuttlebone.showNeeded(project, editor, "Move")
            return true
        }
        InkoMoveDeclarationDialog(project, declaration).show()
        return true
    }

    override fun doMove(project: Project, elements: Array<out PsiElement>, targetContainer: PsiElement?, callback: MoveCallback?) {
        val declaration = elements.singleOrNull()?.let(InkoMoveDeclaration::movable) ?: return
        if (!Cuttlebone.canSearch(declaration)) return Cuttlebone.showNeeded(project, null, "Move")
        InkoMoveDeclarationDialog(project, declaration).show()
    }

    override fun getActionName(elements: Array<out PsiElement>): String = "Move ${elements.singleOrNull()?.let { (it as? InkoDeclaration)?.name } ?: "Declaration"}…"
}

object InkoMoveDeclaration {
    private val MODULE = Regex("""[a-z_][a-z0-9_]*(\.[a-z_][a-z0-9_]*)*""")

    /**
     * The top-level declaration (a type, trait, constant, or module method)
     * [element] is in or names, if it can be moved: like Move Class, the caret
     * can be anywhere in it, not just on its name. Not inside an `impl`, which
     * moves with its type.
     */
    fun movable(element: PsiElement): InkoDeclaration? {
        if (element is PsiFile) return null
        val file = element.containingFile as? InkoFile ?: return null
        if (Inko.pathOf(file.virtualFile)?.let(Inko::moduleName) == null) return null
        val offset = element.textRange.startOffset
        val node = file.inkoOutline.roots.firstOrNull { it.range.containsOffset(offset) || it.nameOffset == offset } ?: return null
        if (node.kind == InkoOutline.Kind.IMPL) return null
        return PsiTreeUtil.getParentOfType(file.findElementAt(node.nameOffset), InkoDeclaration::class.java, false)
    }

    fun topLevelNode(declaration: InkoDeclaration): InkoOutline.Node? =
        declaration.containingFile.inkoOutline.roots.firstOrNull { it.nameOffset == declaration.textRange.startOffset }

    /** What's wrong with moving to [module], or null if it's fine. */
    fun invalidTarget(declaration: InkoDeclaration, module: String): String? {
        val source = Inko.pathOf(declaration.containingFile.virtualFile)?.let(Inko::moduleName)
        return when {
            !MODULE.matches(module) -> "Enter a module name like app.util: lowercase words separated by dots."
            module == source -> "${declaration.name} is already in $module."
            else -> null
        }
    }

    /** Where the declaration and (for a type) its impls are, as whole lines with their comments, in file order. */
    fun ranges(declaration: InkoDeclaration): List<TextRange> {
        val text = declaration.containingFile.text
        val outline = declaration.containingFile.inkoOutline
        val node = topLevelNode(declaration) ?: return emptyList()
        val nodes = listOf(node) + if (node.kind == InkoOutline.Kind.TYPE) {
            outline.roots.filter { it.kind == InkoOutline.Kind.IMPL && outline.implTarget(it) == declaration.name }
        } else {
            emptyList()
        }
        return nodes.map { InkoDeclarations.wholeLines(text, it.range) }.sortedBy { it.startOffset }
    }

    /** The file for [module] under the project's `src/`. */
    fun moduleFile(root: Path, module: String): Path = root.resolve("src").resolve(module.replace('.', '/') + ".${Inko.EXTENSION}")
}

/** A use of the declaration being moved, in a file other than where it's going. */
class InkoMovedUsage(element: PsiElement) : UsageInfo(element)

class InkoMoveDeclarationProcessor(
    project: Project,
    private val declaration: InkoDeclaration,
    private val target: String,
) : BaseRefactoringProcessor(project) {
    private val name = declaration.name
    private val sourceFile = declaration.containingFile
    private val sourcePath = Inko.pathOf(sourceFile.virtualFile)!!
    private val source = Inko.moduleName(sourcePath)!!
    private val root = Inko.projectRoot(sourcePath)
    private val targetPath = InkoMoveDeclaration.moduleFile(root, target)
    private val ranges = InkoMoveDeclaration.ranges(declaration)

    override fun createUsageViewDescriptor(usages: Array<out UsageInfo>): UsageViewDescriptor = BaseUsageViewDescriptor(declaration)

    override fun getCommandName(): String = "Move $name to $target"

    override fun findUsages(): Array<UsageInfo> =
        ReferencesSearch.search(declaration, GlobalSearchScope.projectScope(myProject)).findAll()
            .map { it.element }
            // Uses inside what moves (an impl's `Self`-like references) move with it.
            .filter { it.containingFile != sourceFile || ranges.none { range -> range.contains(it.textRange) } }
            .map { InkoMovedUsage(it) }
            .toTypedArray()

    override fun preprocessUsages(refUsages: Ref<Array<UsageInfo>>): Boolean {
        val conflicts = MultiMap<PsiElement, String>()
        val targetFile = existingTargetFile()
        if (targetFile != null && targetFile.inkoOutline.roots.any { it.kind != InkoOutline.Kind.IMPL && it.label == name }) {
            conflicts.putValue(targetFile, "$target already defines $name.")
        }
        val public = isPublic(declaration)
        if (!public && refUsages.get().isNotEmpty()) {
            conflicts.putValue(declaration, "$name isn't public (pub), so the code still using it can't import it from $target. Make it pub first, or the code won't compile.")
        }
        // Names the moved code uses from its old module must be importable from there.
        val needed = InkoPasteImports.needed(sourceFile, ranges)
        val sourceOutline = sourceFile.inkoOutline
        for (import in needed.filter { it.module == source && it.name != name }) {
            val node = sourceOutline.roots.firstOrNull { it.label == import.name && it.kind != InkoOutline.Kind.IMPL } ?: continue
            if (!Regex("""\bpub\b""").containsMatchIn(sourceFile.text.substring(node.range.startOffset, node.nameOffset))) {
                conflicts.putValue(declaration, "The moved code uses ${import.name}, which isn't public in $source, so $target can't import it.")
            }
        }
        return showConflicts(conflicts, refUsages.get())
    }

    private fun isPublic(declaration: InkoDeclaration): Boolean {
        val node = InkoMoveDeclaration.topLevelNode(declaration) ?: return false
        return Regex("""\bpub\b""").containsMatchIn(declaration.containingFile.text.substring(node.range.startOffset, node.nameOffset))
    }

    private fun existingTargetFile(): PsiFile? =
        com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(targetPath)?.let { PsiManager.getInstance(myProject).findFile(it) }

    override fun performRefactoring(usages: Array<out UsageInfo>) {
        val documents = PsiDocumentManager.getInstance(myProject)
        val sourceText = sourceFile.text
        val moved = ranges.joinToString("\n") { sourceText.substring(it.startOffset, it.endOffset).trimEnd('\n') + "\n" }
        val neededImports = InkoPasteImports.needed(sourceFile, ranges).filter { it.name != name }

        // The target module, created if it doesn't exist yet.
        val targetVirtual = existingTargetFile()?.virtualFile ?: run {
            val directory = VfsUtil.createDirectoryIfMissing(targetPath.parent.toString()) ?: return
            directory.createChildData(this, targetPath.fileName.toString())
        }
        val targetFile = PsiManager.getInstance(myProject).findFile(targetVirtual) ?: return
        val targetDocument = documents.getDocument(targetFile) ?: return

        // Uses elsewhere: per file, the qualified `module.Name` uses and whether it imported Name by name.
        val byFile = usages.mapNotNull { it.element }.groupBy { it.containingFile }
        val sourceStillUses = byFile[sourceFile].orEmpty().isNotEmpty()
        val markers = mutableListOf<Pair<Document, RangeMarker>>()
        val importFixes = mutableListOf<Pair<PsiFile, Document>>()
        for ((file, elements) in byFile) {
            if (file == sourceFile) continue
            val document = documents.getDocument(file) ?: continue
            for (element in elements) {
                // `module.Name` through an import of the old module: drop the qualifier, and import Name instead.
                val dot = element.previousToken()?.takeIf { it.isToken(InkoTokens.DOT) } ?: continue
                val qualifier = dot.previousToken() ?: continue
                markers += document to document.createRangeMarker(qualifier.textRange.startOffset, dot.textRange.endOffset)
            }
            importFixes += file to document
        }

        // Everything below edits text; references above were all found first.
        for ((document, marker) in markers.sortedByDescending { it.second.startOffset }) {
            if (marker.isValid) document.deleteString(marker.startOffset, marker.endOffset)
        }
        for ((file, document) in importFixes) {
            documents.commitDocument(document)
            val imports = InkoImports.parse(document.charsSequence)
            val fromSource = imports.statements.firstOrNull { it.module == source && it.symbols.values.contains(name) }
            val local = fromSource?.symbols?.entries?.firstOrNull { it.value == name }?.key ?: name
            if (fromSource != null) InkoImportEdits.removeSymbol(document, fromSource, local)
            // The target module needs no import of its own declaration.
            if (file.virtualFile != targetVirtual) InkoImportEdits.add(document, target, if (local == name) name else "$name as $local")
            documents.commitDocument(document)
        }

        // Out of the old module (with the blank line after each part), keeping its imports of what remains.
        val sourceDocument = documents.getDocument(sourceFile) ?: return
        for (range in ranges.sortedByDescending { it.startOffset }) {
            var end = range.endOffset
            val text = sourceDocument.charsSequence
            if (end < text.length && text[end] == '\n') end++
            sourceDocument.deleteString(range.startOffset, minOf(end, text.length))
        }
        documents.commitDocument(sourceDocument)
        if (sourceStillUses) InkoImportEdits.add(sourceDocument, target, name)
        documents.commitDocument(sourceDocument)

        // Into the new module, with the imports the moved code needs.
        val existing = targetDocument.charsSequence
        val separator = if (existing.isEmpty()) "" else if (existing.endsWith("\n\n")) "" else if (existing.endsWith("\n")) "\n" else "\n\n"
        targetDocument.insertString(targetDocument.textLength, separator + moved)
        documents.commitDocument(targetDocument)
        for (import in InkoPasteImports.missing(targetFile, neededImports)) InkoPasteImports.add(targetDocument, import)
        documents.commitDocument(targetDocument)
    }

    override fun getElementsToWrite(descriptor: UsageViewDescriptor): Collection<PsiElement> = listOf(sourceFile)
}

/** Move Declaration: which module to move it to (new or existing). */
class InkoMoveDeclarationDialog(project: Project, private val declaration: InkoDeclaration) : RefactoringDialog(project, true) {
    private val modules: List<String> = run {
        val path = Inko.pathOf(declaration.containingFile.virtualFile)
        val root = path?.let(Inko::projectRoot)
        val src = root?.resolve("src")
        if (src == null || !Files.isDirectory(src)) emptyList() else Files.walk(src).use { paths ->
            paths.filter { it.toString().endsWith(".${Inko.EXTENSION}") }.map(Inko::moduleName).toList().filterNotNull().sorted()
        }
    }
    private val moduleField = TextFieldWithAutoCompletion.create(project, modules, true, "")

    init {
        title = "Move ${declaration.name}"
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("To module:") {
            cell(moduleField).align(AlignX.FILL)
                .comment("An existing module, or a new one to create under src/ (like app.shapes).")
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = moduleField

    override fun canRun() {
        InkoMoveDeclaration.invalidTarget(declaration, moduleField.text.trim())?.let { throw ConfigurationException(it) }
    }

    override fun doAction() {
        invokeRefactoring(InkoMoveDeclarationProcessor(project, declaration, moduleField.text.trim()))
    }
}
