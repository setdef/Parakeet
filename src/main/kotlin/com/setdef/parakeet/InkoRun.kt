package com.setdef.parakeet

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configuration.EnvironmentVariablesTextFieldWithBrowseButton
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.LocatableRunConfigurationOptions
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.components.fields.ExpandableTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.execution.ParametersListUtil
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent

enum class InkoCommand(val cli: String, val label: String) {
    RUN("run", "run — compile and run a file"),
    TEST("test", "test — run the project's tests"),
    BUILD("build", "build — compile an executable"),
    CHECK("check", "check — type-check without compiling"),
}

class InkoRunConfigurationType : ConfigurationTypeBase(
    ID, "Inko", "Run Inko programs and tests", NotNullLazyValue.createValue { InkoIcons.FILE },
) {
    init {
        addFactory(InkoRunConfigurationFactory(this))
    }

    companion object {
        const val ID = "ParakeetInkoRunConfiguration"

        fun getInstance(): InkoRunConfigurationType = ConfigurationTypeUtil.findConfigurationType(InkoRunConfigurationType::class.java)
    }
}

class InkoRunConfigurationFactory(type: InkoRunConfigurationType) : ConfigurationFactory(type) {
    override fun getId(): String = "Inko"
    override fun createTemplateConfiguration(project: Project): RunConfiguration = InkoRunConfiguration(project, this, "Inko")
    override fun getOptionsClass() = InkoRunConfigurationOptions::class.java
}

class InkoRunConfigurationOptions : LocatableRunConfigurationOptions() {
    var command by string(InkoCommand.RUN.cli)

    /** The file for run/build/check; for test, an optional filter (a test file path or a test name). */
    var target by string()
    var arguments by string()
    var workingDirectory by string()
    var environment by map<String, String>()
    var passParentEnvironment by property(true)
}

class InkoRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<InkoRunConfigurationOptions>(project, factory, name) {

    public override fun getOptions(): InkoRunConfigurationOptions = super.getOptions() as InkoRunConfigurationOptions

    val command: InkoCommand
        get() = InkoCommand.entries.firstOrNull { it.cli == options.command } ?: InkoCommand.RUN

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = InkoRunConfigurationEditor(project)

    override fun checkConfiguration() {
        if (InkoExecutable.find(project) == null) {
            throw RuntimeConfigurationError("No inko executable found. Choose one in Settings | Languages & Frameworks | Inko.")
        }
        if (command == InkoCommand.RUN && options.target.isNullOrBlank()) {
            throw RuntimeConfigurationError("Choose the Inko file to run.")
        }
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState = InkoRunState(environment, this)
}

class InkoRunState(environment: ExecutionEnvironment, private val configuration: InkoRunConfiguration) : CommandLineState(environment) {
    private val workingDirectory: Path? = configuration.options.workingDirectory?.takeIf { it.isNotBlank() }?.let(Path::of)
        ?: configuration.options.target?.takeIf { it.isNotBlank() }?.let(Path::of)?.takeIf { it.isAbsolute && Files.exists(it) }?.let(Inko::projectRoot)
        ?: environment.project.basePath?.let(Path::of)

    init {
        addConsoleFilters(InkoFileLinkFilter(environment.project, workingDirectory))
    }

    override fun startProcess(): ProcessHandler {
        val options = configuration.options
        val inko = InkoExecutable.find(environment.project)
            ?: throw ExecutionException("No inko executable found. Choose one in Settings | Languages & Frameworks | Inko.")
        val command = GeneralCommandLine(inko.toString(), configuration.command.cli)
            .withCharset(StandardCharsets.UTF_8)
            .withEnvironment(options.environment)
            .withParentEnvironmentType(
                if (options.passParentEnvironment) GeneralCommandLine.ParentEnvironmentType.CONSOLE
                else GeneralCommandLine.ParentEnvironmentType.NONE,
            )
        options.target?.takeIf { it.isNotBlank() }?.let(command::addParameter)
        command.addParameters(ParametersListUtil.parse(options.arguments.orEmpty()))
        workingDirectory?.let { command.withWorkDirectory(it.toFile()) }

        val handler = KillableColoredProcessHandler(command)
        ProcessTerminatedListener.attach(handler)
        return handler
    }
}

class InkoRunConfigurationEditor(project: Project) : SettingsEditor<InkoRunConfiguration>() {
    private val command = ComboBox(InkoCommand.entries.toTypedArray()).apply {
        renderer = textListCellRenderer { it?.label }
    }
    private val target = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("Select Inko File"))
    }
    private val arguments = ExpandableTextField()
    private val workingDirectory = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Select Working Directory"))
    }
    // The (Project) constructor that replaces this one doesn't exist in 2025.2, the oldest supported version.
    @Suppress("DEPRECATION")
    private val environment = EnvironmentVariablesTextFieldWithBrowseButton()

    override fun resetEditorFrom(configuration: InkoRunConfiguration) {
        val options = configuration.options
        command.selectedItem = configuration.command
        target.text = options.target.orEmpty()
        arguments.text = options.arguments.orEmpty()
        workingDirectory.text = options.workingDirectory.orEmpty()
        environment.envs = options.environment
        environment.isPassParentEnvs = options.passParentEnvironment
    }

    override fun applyEditorTo(configuration: InkoRunConfiguration) {
        val options = configuration.options
        options.command = (command.selectedItem as? InkoCommand ?: InkoCommand.RUN).cli
        options.target = target.text.trim().ifEmpty { null }
        options.arguments = arguments.text.trim().ifEmpty { null }
        options.workingDirectory = workingDirectory.text.trim().ifEmpty { null }
        options.environment = environment.envs.toMutableMap()
        options.passParentEnvironment = environment.isPassParentEnvs
    }

    override fun createEditor(): JComponent = panel {
        row("Command:") { cell(command) }
        row("File or filter:") {
            cell(target).align(AlignX.FILL)
                .comment("The file to run, build, or check. For test, optionally a test file or a test name to filter by.")
        }
        row("Arguments:") { cell(arguments).align(AlignX.FILL) }
        row("Working directory:") {
            cell(workingDirectory).align(AlignX.FILL).comment("Defaults to the project root of the file.")
        }
        row("Environment variables:") { cell(environment).align(AlignX.FILL) }
    }
}

/** What a run configuration should do for a spot in an Inko file. */
data class InkoRunTarget(val command: InkoCommand, val target: String?, val name: String, val workingDirectory: Path)

object InkoRunTargets {
    private val MAIN_TYPE = Regex("""\btype\s+async\s+Main\b""")

    fun fromContext(context: ConfigurationContext): InkoRunTarget? {
        val element = context.psiLocation ?: return null
        val file = element as? PsiFile ?: element.containingFile ?: return null
        val leaf = if (element is PsiFile) null else PsiTreeUtil.getDeepestFirst(element)
        return forElement(file, leaf)
    }

    /** The target for a gutter marker on [leaf], if it marks a runnable spot. */
    fun forMarker(leaf: PsiElement): InkoRunTarget? {
        val file = leaf.containingFile ?: return null
        val prev = PsiTreeUtil.prevVisibleLeaf(leaf)
        val prevPrev = prev?.let(PsiTreeUtil::prevVisibleLeaf)
        val isMarker = when (leaf.text) {
            "main" -> prev?.text == "async" && prevPrev?.text == "fn"
            "tests" -> prev?.text == "pub" && prevPrev?.text == "fn"
            "test" -> testName(leaf) != null
            else -> false
        }
        return if (isMarker) forElement(file, leaf) else null
    }

    private fun forElement(file: PsiFile, leaf: PsiElement?): InkoRunTarget? {
        val path = Inko.pathOf(file.virtualFile) ?: return null
        val root = Inko.projectRoot(path)
        val fileName = path.fileName.toString()

        if (path.startsWith(root.resolve("test"))) {
            leaf?.let(::testName)?.let { return InkoRunTarget(InkoCommand.TEST, it, "Test '$it'", root) }
            return InkoRunTarget(InkoCommand.TEST, path.toString(), "Tests in $fileName", root)
        }
        if (MAIN_TYPE.containsMatchIn(file.text)) {
            return InkoRunTarget(InkoCommand.RUN, path.toString(), "Run $fileName", root)
        }
        return null
    }

    /** For the `test` in `t.test('name', ...)`, the test's name. */
    private fun testName(leaf: PsiElement): String? {
        if (leaf.text != "test" || leaf.node.elementType != InkoTokens.IDENTIFIER) return null
        if (PsiTreeUtil.prevVisibleLeaf(leaf)?.node?.elementType != InkoTokens.DOT) return null
        val paren = PsiTreeUtil.nextVisibleLeaf(leaf)?.takeIf { it.node.elementType == InkoTokens.LPAREN } ?: return null
        val string = PsiTreeUtil.nextVisibleLeaf(paren)?.takeIf { it.node.elementType == InkoTokens.STRING } ?: return null
        return string.text.drop(1).dropLast(1).takeIf { it.isNotEmpty() && '$' !in it }
    }
}

class InkoRunConfigurationProducer : LazyRunConfigurationProducer<InkoRunConfiguration>() {
    override fun getConfigurationFactory(): ConfigurationFactory = InkoRunConfigurationType.getInstance().configurationFactories[0]

    override fun setupConfigurationFromContext(
        configuration: InkoRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val target = InkoRunTargets.fromContext(context) ?: return false
        configuration.options.command = target.command.cli
        configuration.options.target = target.target
        configuration.options.workingDirectory = target.workingDirectory.toString()
        configuration.name = target.name
        return true
    }

    override fun isConfigurationFromContext(configuration: InkoRunConfiguration, context: ConfigurationContext): Boolean {
        val target = InkoRunTargets.fromContext(context) ?: return false
        return configuration.command == target.command && configuration.options.target == target.target
    }
}

class InkoRunLineMarkerContributor : RunLineMarkerContributor() {
    override fun getInfo(element: PsiElement): Info? {
        if (element.node?.elementType != InkoTokens.IDENTIFIER) return null
        InkoRunTargets.forMarker(element) ?: return null
        return withExecutorActions(AllIcons.RunConfigurations.TestState.Run)
    }
}

/**
 * Links `path/to/file.inko:LINE[:COLUMN]` in console output (compiler errors
 * and panic backtraces) to the file. Paths may contain spaces, so the longest
 * existing path ending at `.inko` wins.
 */
class InkoFileLinkFilter(private val project: Project, private val workingDirectory: Path?) : Filter {
    private val location = Regex("""\.inko:(\d+)(?::(\d+))?""")

    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val lineStart = entireLength - line.length
        val items = location.findAll(line).mapNotNull { match ->
            val pathEnd = match.range.first + ".inko".length
            val start = (0 until match.range.first)
                .filter { !line[it].isWhitespace() && (it == 0 || line[it - 1].isWhitespace() || line[it - 1] in "'\"(") }
                .firstOrNull { resolve(line.substring(it, pathEnd)) != null } ?: return@mapNotNull null
            val file = resolve(line.substring(start, pathEnd)) ?: return@mapNotNull null
            val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file) ?: return@mapNotNull null
            val lineNumber = match.groupValues[1].toInt() - 1
            val column = match.groupValues[2].toIntOrNull()?.minus(1) ?: 0
            Filter.ResultItem(lineStart + start, lineStart + match.range.last + 1, OpenFileHyperlinkInfo(project, virtualFile, lineNumber, column))
        }.toList()
        return if (items.isEmpty()) null else Filter.Result(items)
    }

    private fun resolve(text: String): Path? {
        val path = try {
            Path.of(text)
        } catch (_: Exception) {
            return null
        }
        val absolute = if (path.isAbsolute) path else workingDirectory?.resolve(path) ?: return null
        return absolute.takeIf { Files.isRegularFile(it) }
    }
}
