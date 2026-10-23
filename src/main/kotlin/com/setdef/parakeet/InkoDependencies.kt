package com.setdef.parakeet

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.toolbar.floating.AbstractFloatingToolbarProvider
import com.intellij.openapi.editor.toolbar.floating.FloatingToolbarComponent
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import java.nio.file.Path
import java.util.function.Function
import javax.swing.JComponent

/** Running `inko pkg …` for a project, in the background, then picking up what it changed. */
object InkoDependencies {
    /**
     * Runs `inko pkg [arguments]` (then `inko pkg sync`, if [thenSync]) in
     * [root], after saving inko.pkg, and refreshes the IDE's view of the project.
     */
    fun run(project: Project, root: Path, title: String, arguments: List<String>, thenSync: Boolean = true) {
        FileDocumentManager.getInstance().saveAllDocuments()
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                val cli = InkoCli.getInstance(project)
                val commands = listOf(arguments) + if (thenSync && arguments != listOf("sync")) listOf(listOf("sync")) else emptyList()
                for (command in commands) {
                    // Syncing downloads packages, which can take a while.
                    val result = cli.run(listOf("pkg") + command, root, timeoutMs = 10 * 60_000, indicator = indicator)
                    when {
                        result == null -> return notify(project, "No inko executable found. Choose one in Settings | Languages & Frameworks | Inko.")
                        result.interrupted -> return
                        !result.succeeded -> return notify(project, result.stderr.ifBlank { result.stdout }.trim().ifEmpty { "inko pkg ${command.first()} failed" })
                    }
                }
            }

            override fun onFinished() = changed(project, root)
        })
    }

    /** After inko.pkg or dep/ changed: refresh files, definitions, libraries, and banners. */
    fun changed(project: Project, root: Path) {
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)?.let { VfsUtil.markDirtyAndRefresh(true, true, true, it) }
        InkoDocIndex.getInstance(project).invalidate(root.resolve(Inko.MANIFEST))
        InkoLibraries.getInstance(project).refresh()
        EditorNotifications.getInstance(project).updateAllNotifications()
    }

    private fun notify(project: Project, message: String) {
        NotificationGroupManager.getInstance().getNotificationGroup(Inko.NOTIFICATION_GROUP)
            .createNotification("Inko dependencies", message, NotificationType.ERROR)
            .notify(project)
    }

    /** The Inko project an action applies to: the one containing the file in context, or the only one. */
    fun rootFor(project: Project, file: VirtualFile?): Path? {
        if (file?.name == Inko.MANIFEST) file.parent?.let { return it.toNioPath() }
        Inko.pathOf(file)?.let { return Inko.projectRoot(it) }
        val roots = ApplicationManager.getApplication().runReadAction<List<Path>> { InkoProjectRoots.find(project) }
        return roots.singleOrNull()
    }
}

/** A dependency action, available when there's an Inko project with an inko.pkg to act on. */
abstract class InkoDependencyAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    protected fun root(e: AnActionEvent): Path? {
        val project = e.project ?: return null
        return InkoDependencies.rootFor(project, e.getData(CommonDataKeys.VIRTUAL_FILE))
            ?.takeIf { it.resolve(Inko.MANIFEST).toFile().isFile }
    }

    override fun update(e: AnActionEvent) {
        // In an editor's context menu, only for Inko files and inko.pkg (not, say, Java files in the same project).
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        if (e.isFromContextMenu && file?.name != Inko.MANIFEST && file?.extension != Inko.EXTENSION) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        e.presentation.isEnabledAndVisible = e.project != null && root(e) != null
    }
}

/** `inko pkg sync`: download and install the dependencies inko.pkg requires. */
class InkoSyncDependenciesAction : InkoDependencyAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        InkoDependencies.run(project, root(e) ?: return, "Syncing Inko dependencies", listOf("sync"))
    }
}

/** `inko pkg update` (to the newest compatible versions), then sync. */
class InkoUpdateDependenciesAction : InkoDependencyAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        InkoDependencies.run(project, root(e) ?: return, "Updating Inko dependencies", listOf("update"))
    }
}

/** `inko pkg add URL VERSION`, then sync. */
class InkoAddDependencyAction : InkoDependencyAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val root = root(e) ?: return
        val dialog = AddDependencyDialog(project)
        if (!dialog.showAndGet()) return
        InkoDependencies.run(project, root, "Adding ${dialog.url}", listOf("add", dialog.url.trim(), dialog.version.trim()))
    }

    private class AddDependencyDialog(project: Project) : DialogWrapper(project) {
        var url = ""
        var version = ""

        init {
            title = "Add Inko Dependency"
            init()
        }

        override fun createCenterPanel(): JComponent = panel {
            row("URL:") {
                textField().bindText(::url).align(AlignX.FILL).focused()
                    .comment("e.g. <code>github.com/yorickpeterse/inko-markdown</code>")
                    .validationOnApply { if (it.text.isBlank()) error("Enter the package's URL.") else null }
            }
            row("Version:") {
                textField().bindText(::version)
                    .comment("The minimum version, e.g. <code>0.24.0</code>")
                    .validationOnApply { if (!Regex("\\d+\\.\\d+\\.\\d+").matches(it.text.trim())) error("Enter a version like 1.2.3.") else null }
            }
        }
    }
}

/** `inko pkg remove URL` for a dependency chosen from inko.pkg, then sync. */
class InkoRemoveDependencyAction : InkoDependencyAction() {
    override fun update(e: AnActionEvent) {
        super.update(e)
        if (e.presentation.isEnabled) e.presentation.isEnabled = root(e)?.let(InkoManifest::read)?.dependencies?.isNotEmpty() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val root = root(e) ?: return
        val urls = InkoManifest.read(root)?.dependencies?.map { it.url }.orEmpty()
        if (urls.isEmpty()) return
        val url = Messages.showEditableChooseDialog("Dependency to remove:", "Remove Inko Dependency", null, urls.toTypedArray(), urls.first(), null)
            ?: return
        InkoDependencies.run(project, root, "Removing $url", listOf("remove", url))
    }
}

/**
 * The floating "Sync dependencies" button on inko.pkg (like Gradle's "Load
 * changes"), shown while the text differs from what was last synced.
 */
class InkoManifestFloatingToolbarProvider : AbstractFloatingToolbarProvider("Parakeet.InkoManifestToolbar") {
    override val autoHideable: Boolean = false

    // Not isApplicable: it's deprecated in 2026.2, and its replacement doesn't exist in 2025.2. Other editors just never show it.
    override fun register(dataContext: DataContext, component: FloatingToolbarComponent, parentDisposable: Disposable) {
        super.register(dataContext, component, parentDisposable)
        val editor = dataContext.getData(CommonDataKeys.EDITOR)?.takeIf { it.virtualFile?.name == Inko.MANIFEST } ?: return
        val project = editor.project ?: return
        val root = editor.virtualFile?.parent?.toNioPath() ?: return
        fun update() {
            val separator = editor.virtualFile?.let { FileDocumentManager.getInstance().getLineSeparator(it, project) } ?: "\n"
            val text = InkoDependencySync.asSaved(editor.document.immutableCharSequence, separator)
            if (InkoDependencySync.needed(root, text)) component.scheduleShow() else component.scheduleHide()
        }
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = update()
        }, parentDisposable)
        // A sync (from the IDE or a terminal) rewrites dep/hash.
        project.messageBus.connect(parentDisposable).subscribe(VirtualFileManagerTopic.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { it.path.startsWith(root.resolve("dep").toString()) }) update()
            }
        })
        update()
    }
}

private object VirtualFileManagerTopic {
    val VFS_CHANGES = com.intellij.openapi.vfs.VirtualFileManager.VFS_CHANGES
}

/** On Inko files: a banner when inko.pkg's dependencies haven't been synced (or dep/ is missing). */
class InkoDependenciesBanner : EditorNotificationProvider {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        val path = Inko.pathOf(file) ?: return null
        val root = Inko.projectRoot(path)
        if (!InkoDependencySync.needed(root)) return null
        return Function { editor ->
            EditorNotificationPanel(editor, EditorNotificationPanel.Status.Warning).apply {
                text = "The dependencies in inko.pkg aren't synced."
                icon(AllIcons.Actions.BuildLoadChanges)
                createActionLabel("Sync dependencies") { InkoDependencies.run(project, root, "Syncing Inko dependencies", listOf("sync")) }
            }
        }
    }
}
